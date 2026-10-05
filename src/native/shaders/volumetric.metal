#include <metal_stdlib>
using namespace metal;

// Gate 4: volumetric sun light (shafts and atmospheric haze), ray-marched through the shadow map at
// quarter resolution and composited over the world before upscaling.

struct VolumetricParams {
	float4x4 inv_view_proj;  // camera clip -> camera-relative world
	float4x4 shadow_sample;  // camera-relative world -> shadow clip space
	float4 sun_dir_day;      // xyz towards the sun, w = daylight 0..1
	float4 sun_color;        // rgb, a = intensity
	float4 medium;           // x density per block, y height falloff per block, z camera world y, w max distance
	float4 misc;             // x anisotropy g, y frame index (noise), z shadow texel, w steps
};

// Interleaved gradient noise: cheap per-pixel jitter that hides banding from the low step count.
static float ign(float2 p, float frame) {
	p += frame * 5.588238;
	return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));
}

static float henyey_greenstein(float cos_theta, float g) {
	float g2 = g * g;
	return (1.0 - g2) / (4.0 * M_PI_F * pow(1.0 + g2 - 2.0 * g * cos_theta, 1.5));
}

// Writes rgb = in-scattered light, a = transmittance, for one quarter-resolution pixel.
kernel void tl_volumetric_march(texture2d<half, access::write> out [[texture(0)]],
                                depth2d<float> depth [[texture(1)]],
                                depth2d<float> shadow_map [[texture(2)]],
                                sampler compare [[sampler(0)]],
                                constant VolumetricParams &p [[buffer(0)]],
                                uint2 gid [[thread_position_in_grid]]) {
	uint2 size = uint2(out.get_width(), out.get_height());
	if (any(gid >= size)) return;
	float2 uv = (float2(gid) + 0.5) / float2(size);
	uint2 dsize = uint2(depth.get_width(), depth.get_height());
	// Nearest of the 4x4 block's depths would be ideal; the centre texel is close enough at this scale.
	float d = depth.read(min(uint2(uv * float2(dsize)), dsize - 1));

	// Minecraft's texel row 0 is NDC y = -1 (see shadow.metal).
	float2 ndc = uv * 2.0 - 1.0;
	float4 far4 = p.inv_view_proj * float4(ndc, d > 0.0 ? d : 1e-6, 1.0);
	float3 end = far4.xyz / far4.w;
	float dist = length(end);
	float3 dir = end / max(dist, 1e-4);
	dist = min(dist, p.medium.w);

	int steps = int(p.misc.w);
	float step_len = dist / float(steps);
	float jitter = ign(float2(gid), p.misc.y);
	float density = p.medium.x;
	float falloff = p.medium.y;
	float cam_y = p.medium.z;

	float3 sun = p.sun_dir_day.xyz;
	float phase = henyey_greenstein(dot(dir, sun), p.misc.x) * 4.0 * M_PI_F; // 1 for isotropic
	float transmittance = 1.0;
	float scatter = 0.0;
	for (int i = 0; i < steps; i++) {
		float t = (float(i) + jitter) * step_len;
		float3 pos = dir * t;
		// Haze thins out with height above the camera, thickens below it.
		float sigma = density * exp(-max(cam_y + pos.y - 63.0, 0.0) * falloff);
		float4 s = p.shadow_sample * float4(pos, 1.0);
		s.xyz /= s.w;
		float2 suv = float2(s.x * 0.5 + 0.5, 0.5 - s.y * 0.5);
		float lit = 1.0;
		if (all(suv > 0.0) && all(suv < 1.0) && s.z < 1.0) {
			lit = shadow_map.sample_compare(compare, suv, s.z - 0.001);
		}
		float step_t = exp(-sigma * step_len);
		scatter += transmittance * (1.0 - step_t) * lit;
		transmittance *= step_t;
	}
	float3 light = p.sun_color.rgb * p.sun_color.a * p.sun_dir_day.w * phase * scatter;
	out.write(half4(half3(light), half(transmittance)), gid);
}

struct FullscreenOut {
	float4 position [[position]];
	float2 uv;
};

// Composites the march result over the low-res colour in place (programmable blending).
fragment half4 tl_volumetric_apply_fs(FullscreenOut in [[stage_in]],
                                      half4 color [[color(0)]],
                                      texture2d<half> fog [[texture(0)]],
                                      sampler linear [[sampler(0)]]) {
	half4 f = fog.sample(linear, in.uv);
	return half4(color.rgb * f.a + f.rgb, color.a);
}
