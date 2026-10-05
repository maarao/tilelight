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
