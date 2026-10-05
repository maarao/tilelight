#include <metal_stdlib>
using namespace metal;

// Gate 4: screen-space reflections on water (and other flat translucent surfaces), applied to the
// low-res world colour before the upscale.
//
// Water pixels are found without a G-buffer: `terrain_depth` is copied right after the Metal terrain
// pass, `world_depth` after vanilla's translucents. Where the world depth is in front of the terrain
// and the surface faces up, a translucent horizontal surface was drawn over it.

struct WaterParams {
	float4x4 view_proj;      // camera-relative world -> clip (Minecraft conventions)
	float4x4 inv_view_proj;
	float4 sky_horizon;      // rgb
	float4 sky_zenith;       // rgb, a = reflection strength
	float4 misc;             // x max distance, y steps, z frame index
	float4 sun;              // xyz towards the sun, w = sun glint intensity (0 at night or in rain)
};

struct FullscreenOut {
	float4 position [[position]];
	float2 uv;
};

static float3 reconstruct(constant WaterParams &p, float2 uv, float d) {
	float4 w = p.inv_view_proj * float4(uv * 2.0 - 1.0, d, 1.0); // texel row 0 is NDC y = -1
	return w.xyz / w.w;
}

fragment half4 tl_water_fs(FullscreenOut in [[stage_in]],
                           half4 color [[color(0)]],
                           depth2d<float> world_depth [[texture(0)]],
                           depth2d<float> terrain_depth [[texture(1)]],
                           texture2d<half> scene [[texture(2)]],
                           sampler linear [[sampler(0)]],
                           constant WaterParams &p [[buffer(0)]]) {
	uint2 px = uint2(in.position.xy);
	float dw = world_depth.read(px);
	float dt = terrain_depth.read(px);
	// Reverse-Z: larger is closer. No translucent surface in front of the terrain here.
	if (dw <= 0.0 || dw <= dt + 1e-7) return color;

	float3 pos = reconstruct(p, in.uv, dw);
	float3 n = normalize(cross(dfdx(pos), dfdy(pos)));
	if (n.y < 0.0) n = -n;
	if (n.y < 0.9) return color; // only flat, upward surfaces (water, ice); not glass panes

	float3 v = normalize(pos);
	float3 r = reflect(v, float3(0.0, 1.0, 0.0));
	float cos_v = saturate(-v.y);
	float fresnel = 0.02 + 0.98 * pow(1.0 - cos_v, 5.0);

	// Sky seen in the reflection, used where the ray leaves the screen or hits nothing.
	float3 sky = mix(p.sky_horizon.rgb, p.sky_zenith.rgb, smoothstep(0.0, 0.5, r.y));
	float3 refl = sky;

	// March the reflected ray in world space, checking it against the scene's depth.
	int steps = int(p.misc.y);
	float max_dist = p.misc.x;
	float t = 0.5;
	float growth = pow(max_dist / 0.5, 1.0 / float(steps));
	float2 dims = float2(world_depth.get_width(), world_depth.get_height());
	for (int i = 0; i < steps; i++) {
		float3 q = pos + r * t;
		float4 c = p.view_proj * float4(q, 1.0);
		if (c.w <= 0.0) break;
		float3 ndc = c.xyz / c.w;
		float2 uv = ndc.xy * 0.5 + 0.5;
		if (any(uv < 0.0) || any(uv > 1.0)) break;
		float scene_d = world_depth.read(uint2(uv * dims));
		// The ray is behind visible geometry (and not hugely behind it): treat as a hit.
		if (scene_d > ndc.z && scene_d > 0.0) {
			float3 hit = reconstruct(p, uv, scene_d);
			if (distance(hit, q) < t * 0.35 + 1.0) {
				float2 edge = smoothstep(0.0, 0.08, uv) * smoothstep(0.0, 0.08, 1.0 - uv);
				refl = mix(sky, float3(scene.sample(linear, uv).rgb), edge.x * edge.y);
			}
			break;
		}
		t *= growth;
	}

	float strength = p.sky_zenith.a * fresnel;
	float3 result = mix(float3(color.rgb), refl, strength);
	// Sun glint: a tight highlight where the water mirrors the sun (bloom spreads it into a glow).
	float glint = pow(saturate(dot(r, p.sun.xyz)), 900.0) * 6.0 + pow(saturate(dot(r, p.sun.xyz)), 60.0) * 0.15;
	float3 sun_tint = mix(float3(1.0, 0.55, 0.25), float3(1.0, 0.95, 0.85), smoothstep(0.0, 0.4, p.sun.y));
	result += sun_tint * glint * p.sun.w;
	return half4(half3(saturate(result)), color.a);
}
