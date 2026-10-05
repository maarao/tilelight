#include <metal_stdlib>
using namespace metal;

// Bloom: a soft glow around the sun, sun glints and other near-white highlights. Works on the
// display-space low-res colour: bright parts are extracted at quarter resolution, blurred (MPS) and
// added back before anti-aliasing and upscaling.

struct BloomParams {
	float threshold; // luminance where glow starts
	float knee;      // width of the soft transition above the threshold
	float intensity;
	float pad;
};

// Each output texel averages a 4x4 block of the source (4 bilinear taps) and keeps only the part
// above the threshold.
kernel void tl_bloom_extract(texture2d<half> src [[texture(0)]],
                             texture2d<half, access::write> dst [[texture(1)]],
                             sampler linear [[sampler(0)]],
                             constant BloomParams &p [[buffer(0)]],
                             uint2 gid [[thread_position_in_grid]]) {
	uint2 size = uint2(dst.get_width(), dst.get_height());
	if (any(gid >= size)) return;
	float2 texel = 1.0 / float2(src.get_width(), src.get_height());
	float2 uv = (float2(gid) + 0.5) / float2(size);
	float3 c = 0.0;
	for (int i = 0; i < 4; i++) {
		float2 o = float2((i & 1) ? 1.0 : -1.0, (i & 2) ? 1.0 : -1.0) * texel;
		c += float3(src.sample(linear, uv + o).rgb);
	}
	c *= 0.25;
	float lum = dot(c, float3(0.2126, 0.7152, 0.0722));
	float w = smoothstep(p.threshold, p.threshold + p.knee, lum);
	dst.write(half4(half3(c * w), 1.0h), gid);
}

struct FullscreenOut {
	float4 position [[position]];
	float2 uv;
};

fragment half4 tl_bloom_apply_fs(FullscreenOut in [[stage_in]],
                                 half4 color [[color(0)]],
                                 texture2d<half> glow [[texture(0)]],
                                 sampler linear [[sampler(0)]],
                                 constant BloomParams &p [[buffer(0)]]) {
	half3 g = glow.sample(linear, in.uv).rgb * half(p.intensity);
	// Screen blend: brightens without clipping straight to white.
	return half4(1.0h - (1.0h - color.rgb) * (1.0h - g), color.a);
}
