#include <metal_stdlib>
using namespace metal;

struct FullscreenOut {
	float4 position [[position]];
	float2 uv;
};

// One triangle that covers the whole target; no vertex buffer needed.
vertex FullscreenOut tl_fullscreen_vs(uint vid [[vertex_id]]) {
	float2 uv = float2((vid << 1) & 2, vid & 2);
	FullscreenOut out;
	out.position = float4(uv * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
	out.uv = uv;
	return out;
}

// Plain bilinear upscale; the reference point MetalFX has to beat.
fragment half4 tl_bilinear_fs(FullscreenOut in [[stage_in]],
                              texture2d<half> src [[texture(0)]],
                              sampler linear [[sampler(0)]]) {
	return src.sample(linear, in.uv);
}
