#include <metal_stdlib>
using namespace metal;

// Gate 3: Minecraft's opaque terrain, drawn and lit on Metal.
//
// Forward shading on purpose: Apple GPUs remove hidden opaque fragments before shading, so each
// visible pixel is lit about once without a G-buffer. Only cutout geometry (which discards) loses
// that, and leaves are cheap to shade.

struct TerrainVertex {
	float3 position [[attribute(0)]];
	float4 color [[attribute(1)]];  // biome tint x baked ambient occlusion x vanilla face shading
	float2 uv [[attribute(2)]];
	short2 light [[attribute(3)]];  // block light, sky light; 0..240 in steps of 16
};

struct TerrainParams {
	float4x4 view_proj;
	float4x4 shadow_sample;   // camera-relative world -> shadow clip space
	float4 sun_dir_day;       // xyz towards the sun, w = daylight 0..1
	float4 fog_color;
	float4 fog;               // environmental start, end, render-distance start, end
	float4 light;             // x sky brightness (rain), y shadow strength, z shadow texel, w shadows on
};

struct TerrainOut {
	float4 position [[position]];
	float3 world;             // camera-relative
	float4 color;
	float2 uv;
	float2 light;             // block, sky in 0..1
};

vertex TerrainOut tl_terrain_vs(TerrainVertex in [[stage_in]],
                                constant TerrainParams &p [[buffer(1)]],
                                constant packed_float3 &origin [[buffer(2)]]) {
	TerrainOut out;
	float3 world = in.position + float3(origin);
	out.position = p.view_proj * float4(world, 1.0);
	// Minecraft's Vulkan path puts NDC y = -1 on texel row 0; Metal puts y = +1 there. Flip so our
	// terrain lands upright in Minecraft's textures, matching its depth for later vanilla passes.
	out.position.y = -out.position.y;
	out.world = world;
	out.color = in.color;
	out.uv = in.uv;
	out.light = float2(in.light) / 240.0;
	return out;
}

static float linear_fog(float d, float start, float end) {
	return saturate((d - start) / max(end - start, 1e-4));
}

static float sun_visibility(float3 world, float3 n, float ndotl, constant TerrainParams &p,
                            depth2d<float> shadow_map, sampler compare) {
	if (p.light.w < 0.5) return 1.0;
	float3 biased = world + n * (0.05 + 0.15 * (1.0 - ndotl));
	float4 s = p.shadow_sample * float4(biased, 1.0);
	s.xyz /= s.w;
	float2 suv = float2(s.x * 0.5 + 0.5, 0.5 - s.y * 0.5);
	if (any(suv <= 0.0) || any(suv >= 1.0) || s.z >= 1.0) return 1.0;
	float sum = 0.0;
	for (int i = 0; i < 4; i++) {
		float2 o = float2((i & 1) ? 0.5 : -0.5, (i & 2) ? 0.5 : -0.5) * p.light.z;
		sum += shadow_map.sample_compare(compare, suv + o, s.z - 0.0005);
	}
	float2 edge = abs(suv * 2.0 - 1.0);
	return mix(sum * 0.25, 1.0, smoothstep(0.85, 1.0, max(edge.x, edge.y)));
}

static half4 shade(TerrainOut in, half4 albedo, constant TerrainParams &p, depth2d<float> shadow_map, sampler compare) {
	// Block faces are flat, so screen-space derivatives give the exact face normal.
	float3 n = normalize(cross(dfdx(in.world), dfdy(in.world)));
	if (dot(n, -in.world) < 0.0) n = -n;
	float3 sun = p.sun_dir_day.xyz;
	float day = p.sun_dir_day.w;
	float block = in.light.x;
	float sky = in.light.y;

	float ndotl = dot(n, sun);
	float direct = 0.0;
	if (ndotl > 0.0 && day > 0.0) {
		// Sky light says how open the spot is: no sun deep under cover even outside the shadow map.
		direct = ndotl * day * smoothstep(0.5, 0.95, sky) * sun_visibility(in.world, n, ndotl, p, shadow_map, compare);
	}
	float skyLevel = sky * sky * mix(0.06, 1.0, day) * p.light.x;

	// Work in the same sRGB-encoded space as vanilla so the overall look stays familiar: the sky
	// term gives vanilla's daylight brightness, the sun adds directional light on top and is what
	// shadows take away.
	float strength = p.light.y;
	float3 light = float3(skyLevel * (1.0 - strength * 0.5))
	             + float3(1.0, 0.96, 0.88) * direct * strength * 0.75
	             + float3(1.0, 0.82, 0.6) * pow(block, 1.6);
	light = max(light, float3(0.03));
	float3 color = float3(albedo.rgb) * light;

	float spherical = length(in.world);
	float cylindrical = max(length(in.world.xz), abs(in.world.y));
	float fog = max(linear_fog(spherical, p.fog.x, p.fog.y), linear_fog(cylindrical, p.fog.z, p.fog.w));
	color = mix(color, p.fog_color.rgb, fog * p.fog_color.a);
	return half4(half3(saturate(color)), 1.0h);
}

fragment half4 tl_terrain_fs(TerrainOut in [[stage_in]],
                             texture2d<half> atlas [[texture(0)]],
                             depth2d<float> shadow_map [[texture(1)]],
                             sampler nearest [[sampler(0)]],
                             sampler compare [[sampler(1)]],
                             constant TerrainParams &p [[buffer(0)]]) {
	half4 albedo = atlas.sample(nearest, in.uv) * half4(in.color);
	return shade(in, albedo, p, shadow_map, compare);
}

fragment half4 tl_terrain_cutout_fs(TerrainOut in [[stage_in]],
                                    texture2d<half> atlas [[texture(0)]],
                                    depth2d<float> shadow_map [[texture(1)]],
                                    sampler nearest [[sampler(0)]],
                                    sampler compare [[sampler(1)]],
                                    constant TerrainParams &p [[buffer(0)]]) {
	half4 albedo = atlas.sample(nearest, in.uv) * half4(in.color);
	if (albedo.a < 0.5h) discard_fragment(); // vanilla's cutout-terrain threshold
	return shade(in, albedo, p, shadow_map, compare);
}
