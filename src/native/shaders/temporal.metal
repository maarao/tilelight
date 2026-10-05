#include <metal_stdlib>
using namespace metal;

// Gate 5: camera motion vectors for MetalFX temporal upscaling.
//
// The world is static between frames except for entities, so camera reprojection of the depth
// buffer gives correct motion for terrain, water and sky; moving entities ghost slightly.

struct TemporalParams {
	float4x4 inv_view_proj;      // this frame, jittered (matches the depth buffer)
	float4x4 view_proj;          // this frame, unjittered
	float4x4 prev_view_proj;     // last frame, unjittered
	float4 camera_delta;         // this camera position - last camera position (world blocks)
	float4 jitter;               // x, y in pixels; z = reset history
};

// Writes, per low-res pixel, the offset in pixels from this frame's position to last frame's.
kernel void tl_motion_vectors(texture2d<half, access::write> motion [[texture(0)]],
                              depth2d<float> depth [[texture(1)]],
                              depth2d<float> hand_depth [[texture(2)]],
                              constant TemporalParams &p [[buffer(0)]],
                              uint2 gid [[thread_position_in_grid]]) {
	uint2 size = uint2(motion.get_width(), motion.get_height());
	if (any(gid >= size)) return;
	if (hand_depth.read(gid) > 0.0) { // the first-person hand moves with the camera
		motion.write(half4(0.0h), gid);
		return;
	}
	float2 uv = (float2(gid) + 0.5) / float2(size);
	float d = max(depth.read(gid), 1e-7); // sky (reverse-Z 0) reprojects as very far away
	// Minecraft's texel row 0 is NDC y = -1, so u/v map straight to NDC without a flip.
	float4 w = p.inv_view_proj * float4(uv * 2.0 - 1.0, d, 1.0);
	float3 pos = w.xyz / w.w;

	float4 cur = p.view_proj * float4(pos, 1.0);
	// Positions are camera-relative: last frame's camera saw this point shifted by the camera's move.
	float4 prev = p.prev_view_proj * float4(pos + p.camera_delta.xyz, 1.0);
	float2 cur_uv = cur.xy / cur.w * 0.5 + 0.5;
	float2 prev_uv = prev.xy / prev.w * 0.5 + 0.5;
	float2 m = (prev_uv - cur_uv) * float2(size);
	motion.write(half4(half2(m), 0.0h, 0.0h), gid);
}

// --- Our own TAA at internal resolution, followed by MetalFX spatial upscaling (upscaler "taa") ---

static float3 to_ycocg(float3 c) {
	return float3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}

static float3 from_ycocg(float3 c) {
	return float3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// Catmull-Rom history sample in 5 bilinear taps; keeps the image from softening as it is reprojected
// frame after frame (plain bilinear blurs a little more each frame).
static float3 sample_catmull_rom(texture2d<half> tex, sampler linear, float2 uv, float2 size) {
	float2 pos = uv * size;
	float2 center = floor(pos - 0.5) + 0.5;
	float2 f = pos - center;
	float2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
	float2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
	float2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
	float2 w3 = f * f * (-0.5 + 0.5 * f);
	float2 w12 = w1 + w2;
	float2 tc0 = (center - 1.0) / size;
	float2 tc3 = (center + 2.0) / size;
	float2 tc12 = (center + w2 / w12) / size;
	float3 c = float3(tex.sample(linear, float2(tc12.x, tc0.y)).rgb) * (w12.x * w0.y)
	         + float3(tex.sample(linear, float2(tc0.x, tc12.y)).rgb) * (w0.x * w12.y)
	         + float3(tex.sample(linear, tc12).rgb) * (w12.x * w12.y)
	         + float3(tex.sample(linear, float2(tc3.x, tc12.y)).rgb) * (w3.x * w12.y)
	         + float3(tex.sample(linear, float2(tc12.x, tc3.y)).rgb) * (w12.x * w3.y);
	float wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
	return max(c / wsum, 0.0);
}

kernel void tl_taa_resolve(texture2d<half, access::read> current [[texture(0)]],
                           texture2d<half> history [[texture(1)]],
                           texture2d<half, access::write> resolved [[texture(2)]],
                           depth2d<float> depth [[texture(3)]],
                           depth2d<float> hand_depth [[texture(4)]],
                           sampler linear [[sampler(0)]],
                           constant TemporalParams &p [[buffer(0)]],
                           uint2 gid [[thread_position_in_grid]]) {
	uint2 size = uint2(resolved.get_width(), resolved.get_height());
	if (any(gid >= size)) return;
	float3 cur = float3(current.read(gid).rgb);

	// Neighbourhood colour bounds (YCoCg) and the closest depth around this pixel (reverse-Z: max).
	float3 lo = to_ycocg(cur), hi = lo;
	float closest = depth.read(gid);
	for (int y = -1; y <= 1; y++) {
		for (int x = -1; x <= 1; x++) {
			if (x == 0 && y == 0) continue;
			uint2 q = uint2(clamp(int2(gid) + int2(x, y), int2(0), int2(size) - 1));
			float3 c = to_ycocg(float3(current.read(q).rgb));
			lo = min(lo, c);
			hi = max(hi, c);
			closest = max(closest, depth.read(q));
		}
	}

	float2 uv = (float2(gid) + 0.5) / float2(size);
	float2 prev_uv = uv; // the first-person hand moves with the camera
	if (hand_depth.read(gid) <= 0.0) {
		float4 w = p.inv_view_proj * float4(uv * 2.0 - 1.0, max(closest, 1e-7), 1.0);
		float3 pos = w.xyz / w.w;
		float4 prev = p.prev_view_proj * float4(pos + p.camera_delta.xyz, 1.0);
		prev_uv = prev.xy / prev.w * 0.5 + 0.5;
	}

	float3 out = cur;
	if (p.jitter.z == 0.0 && all(prev_uv > 0.0) && all(prev_uv < 1.0)) {
		float3 hist = to_ycocg(sample_catmull_rom(history, linear, prev_uv, float2(size)));
		hist = from_ycocg(clamp(hist, lo, hi));
		// Luminance-weighted blend damps flicker from bright, thin features.
		float wc = 0.1 / (1.0 + dot(cur, float3(0.299, 0.587, 0.114)));
		float wh = 0.9 / (1.0 + dot(hist, float3(0.299, 0.587, 0.114)));
		out = (cur * wc + hist * wh) / (wc + wh);
	}
	resolved.write(half4(half3(out), 1.0h), gid);
}
