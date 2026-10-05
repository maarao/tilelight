#include <metal_stdlib>
using namespace metal;

// Vanilla's BLOCK vertex format: float3 position, rgba8 colour, float2 uv, short2 lightmap uv (28 bytes).
struct BlockVertex {
	float3 position [[attribute(0)]];
	float4 color [[attribute(1)]];
	float2 uv [[attribute(2)]];
};

struct ShadowVertexOut {
	float4 position [[position]];
	float2 uv;
};

vertex ShadowVertexOut tl_shadow_vs(BlockVertex in [[stage_in]],
                                    constant float4x4 &shadow_view_proj [[buffer(1)]],
                                    constant packed_float3 &origin [[buffer(2)]]) {
	ShadowVertexOut out;
	out.position = shadow_view_proj * float4(in.position + float3(origin), 1.0);
	out.uv = in.uv;
	return out;
}

// Cutout layers (leaves, grass, flowers) cast shadows only where the texture is opaque,
// using vanilla's cutout-terrain threshold of 0.5.
fragment void tl_shadow_cutout_fs(ShadowVertexOut in [[stage_in]],
                                  texture2d<half> atlas [[texture(0)]],
                                  sampler nearest [[sampler(0)]]) {
	if (atlas.sample(nearest, in.uv).a < 0.5h) discard_fragment();
}

struct ApplyParams {
	float4x4 inv_view_proj;
	float4x4 shadow_view_proj;
	float4 sun_dir_strength; // xyz = towards the sun, w = shadow strength
	int debug;
	float texel;             // shadow map texel size in UV
};

struct FullscreenOut {
	float4 position [[position]];
	float2 uv;
};

// Darkens shadowed pixels in place. Reads the current colour through programmable blending, so the
// low-res colour never leaves tile memory for this pass.
fragment half4 tl_shadow_apply_fs(FullscreenOut in [[stage_in]],
                                  half4 color [[color(0)]],
                                  depth2d<float> depth [[texture(0)]],
                                  depth2d<float> shadow_map [[texture(1)]],
                                  depth2d<float> hand_depth [[texture(2)]],
                                  sampler compare [[sampler(0)]],
                                  constant ApplyParams &p [[buffer(0)]]) {
	uint2 px = uint2(in.position.xy);
	if (hand_depth.read(px) > 0.0) return color; // first-person hand: drawn with its own projection
	float d = depth.read(px);
	if (d <= 0.0) return color; // reverse-Z: 0 is the cleared far plane (sky)

	// Minecraft never flips Y (positive viewport, no flip in its shader compiler), so texel row 0 is
	// NDC y = -1, the same as the u/v -> NDC mapping below.
	float2 ndc = in.uv * 2.0 - 1.0;
	float4 world4 = p.inv_view_proj * float4(ndc, d, 1.0);
	float3 world = world4.xyz / world4.w;

	if (p.debug == 1) return half4(half3(fract(world * 0.25)), 1.0h);

	// Geometric normal from screen-space derivatives, flipped to face the camera (at the origin).
	float3 n = normalize(cross(dfdx(world), dfdy(world)));
	if (dot(n, -world) < 0.0) n = -n;
	float3 sun = p.sun_dir_strength.xyz;
	float ndotl = dot(n, sun);

	// Only cast shadows are added. Faces turned away from the sun are already darkened by vanilla's
	// directional face shading, so they (and anything outside the map, like clouds) stay as they are.
	float lit = 1.0;
	if (ndotl > 0.0) {
		// Normal offset scaled with slope keeps acne off surfaces at grazing sun angles.
		float3 biased = world + n * (0.05 + 0.15 * (1.0 - ndotl));
		float4 s = p.shadow_view_proj * float4(biased, 1.0);
		s.xyz /= s.w;
		float2 suv = float2(s.x * 0.5 + 0.5, 0.5 - s.y * 0.5);
		if (all(suv > 0.0) && all(suv < 1.0) && s.z < 1.0) {
			// Four taps of the hardware 2x2 comparison filter, half a texel apart: a smooth 3x3 footprint.
			float sum = 0.0;
			for (int i = 0; i < 4; i++) {
				float2 o = float2((i & 1) ? 0.5 : -0.5, (i & 2) ? 0.5 : -0.5) * p.texel;
				sum += shadow_map.sample_compare(compare, suv + o, s.z - 0.0005);
			}
			// Fade out over the outer 15% of the map so its edge never shows as a line.
			float2 edge = abs(suv * 2.0 - 1.0);
			float fade = smoothstep(0.85, 1.0, max(edge.x, edge.y));
			lit = mix(sum * 0.25, 1.0, fade);
		}
	}

	// Fade shadows out as the sun reaches the horizon.
	float strength = p.sun_dir_strength.w * smoothstep(0.0, 0.15, sun.y);
	float factor = mix(1.0 - strength, 1.0, lit);
	if (p.debug == 2) return half4(half3(factor), 1.0h);
	return half4(color.rgb * half(factor), color.a);
}
