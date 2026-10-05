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
	float4 camera_offset;     // xyz = camera block position - camera position (vanilla's CameraOffset)
	float4 sky_color;         // rgb = vanilla's sky colour (sRGB), a = exposure
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
	// Same arithmetic as vanilla's terrain.vsh (Position + (ChunkPosition - CameraBlockPos) + CameraOffset)
	// so our depth matches what vanilla would have written, for coplanar overlays drawn afterwards.
	float3 world = in.position + float3(origin) + p.camera_offset.xyz;
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

static float3 srgb_to_linear(float3 c) {
	return select(pow((c + 0.055) / 1.055, 2.4), c / 12.92, c <= 0.04045);
}

static float3 linear_to_srgb(float3 c) {
	return select(1.055 * pow(c, 1.0 / 2.4) - 0.055, c * 12.92, c <= 0.0031308);
}

// Filmic curve (Narkowicz's ACES fit): keeps sunlit surfaces from clipping and gives the image contrast.
static float3 tonemap(float3 x) {
	return saturate(x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14));
}

// Vanilla bakes Overworld face shading into vertex colour (CardinalLighting.DEFAULT). Our sun and sky
// terms shade faces themselves, so take it back out on axis-aligned faces.
static float vanilla_face_shade(float3 n) {
	float3 a = abs(n);
	if (a.y > 0.99) return n.y > 0.0 ? 1.0 : 0.5;
	if (a.z > 0.99) return 0.8;
	if (a.x > 0.99) return 0.6;
	return 1.0;
}

// Sunlight colour: white-yellow overhead, orange near the horizon where it crosses more atmosphere.
static float3 sun_light(float sun_y) {
	float3 high = float3(1.0, 0.93, 0.82);
	float3 low = float3(1.0, 0.48, 0.18);
	float elevation = smoothstep(-0.02, 0.45, sun_y);
	return mix(low, high, elevation) * 2.6 * smoothstep(-0.03, 0.08, sun_y);
}

static half4 shade(TerrainOut in, half4 albedo, constant TerrainParams &p, depth2d<float> shadow_map, sampler compare) {
	// Block faces are flat, so screen-space derivatives give the exact face normal.
	float3 n = normalize(cross(dfdx(in.world), dfdy(in.world)));
	if (dot(n, -in.world) < 0.0) n = -n;
	float3 sun = p.sun_dir_day.xyz;
	float block = in.light.x;
	float sky = in.light.y;

	float3 base = srgb_to_linear(float3(albedo.rgb)) / (vanilla_face_shade(n) * vanilla_face_shade(n));

	// Sun: warm, shadowed. Sky light level says how open the spot is, so no sun deep under cover even
	// outside the shadow map's range.
	float ndotl = dot(n, sun);
	float3 direct = 0.0;
	if (ndotl > 0.0) {
		float vis = smoothstep(0.5, 0.95, sky) * sun_visibility(in.world, n, ndotl, p, shadow_map, compare);
		direct = sun_light(sun.y) * ndotl * vis * p.light.x;
	}
	// Sky: the open sky's own colour, strongest on upward faces. This is what fills shadows, which is
	// why they come out cool next to the warm sunlit faces.
	float3 sky_lin = srgb_to_linear(p.sky_color.rgb);
	float3 ambient = sky_lin * (0.55 + 0.45 * n.y) * 1.1 * sky * sky;
	// Night: vanilla's night sky colour is nearly black, so add cool moonlight (from opposite the sun,
	// unshadowed: the shadow map follows the sun) and a dim blue sky fill, both scaled by how open
	// the spot is. Brings open ground at night close to vanilla's brightness.
	float night = smoothstep(0.05, -0.15, sun.y);
	float open = smoothstep(0.5, 0.95, sky);
	ambient += float3(0.06, 0.075, 0.12) * (0.6 + 0.4 * n.y) * sky * sky * night;
	direct += float3(0.45, 0.55, 0.85) * 0.35 * saturate(dot(n, -sun)) * open * night * p.light.x;
	// Block light (torches, lava): warm, falling off like vanilla's light levels.
	float3 lamp = float3(1.0, 0.62, 0.32) * 1.6 * pow(block, 2.4);
	float3 light = direct + ambient + lamp + float3(0.006); // never fully black, like vanilla's minimum

	float3 hdr = base * light * p.sky_color.a;
	// The per-channel curve oversaturates bright, strongly tinted surfaces (sunlit leaves and grass);
	// pull those slightly toward their luminance.
	float lum = dot(hdr, float3(0.2126, 0.7152, 0.0722));
	hdr = mix(float3(lum), hdr, mix(1.0, 0.8, saturate(lum)));
	float3 color = linear_to_srgb(tonemap(hdr));

	// Fog in display space, so far terrain fades into vanilla's (untonemapped) sky exactly as before.
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
