// C ABI exported to Java through the Foreign Function & Memory API.
// Keep this surface small and plain-C: ints, pointers and lengths only.
#pragma once
#include <stdint.h>

// Error codes returned by every tl_* function (0 = success).
enum {
	TL_OK = 0,
	TL_ERR_NO_DEVICE = -1,
	TL_ERR_NOT_INITIALIZED = -2,
	TL_ERR_LIBRARY = -3,
	TL_ERR_PIPELINE = -4,
	TL_ERR_GPU = -5,
	TL_ERR_MISMATCH = -6,
	TL_ERR_BUFFER = -7,
	TL_ERR_NOT_COMMITTED = -8, // no command buffer was committed, so nothing will signal the event
};

// Creates the Metal device and command queue and loads the shader library at metallib_path.
// Safe to call more than once; later calls are no-ops that return TL_OK.
int32_t tl_init(const char *metallib_path);

// Copies the device name (UTF-8, NUL-terminated, truncated to fit) into buf.
int32_t tl_device_name(char *buf, int32_t buf_len);

// Bit flags describing what the device supports (see TL_CAP_*).
int64_t tl_capabilities(void);
enum {
	TL_CAP_RAYTRACING = 1 << 0,     // MTLDevice.supportsRaytracing
	TL_CAP_APPLE9 = 1 << 1,         // MTLGPUFamilyApple9 (M3 / A17 and later)
	TL_CAP_UNIFIED_MEMORY = 1 << 2, // MTLDevice.hasUnifiedMemory
	TL_CAP_METAL3 = 1 << 3,         // MTLGPUFamilyMetal3
};

// Runs a tiny compute kernel over n elements and checks the result on the CPU.
// Returns TL_OK and writes the GPU time in nanoseconds to gpu_ns on success.
int32_t tl_selftest(int32_t n, int64_t *gpu_ns);

// Releases everything created by tl_init.
void tl_shutdown(void);

// --- Interop with Minecraft's Vulkan (MoltenVK) device, see interop.m ---

// What tl_interop_frame does between the event wait and signal.
enum {
	TL_MODE_NOOP = 0,     // nothing: measures the cost of the hand-off itself
	TL_MODE_BILINEAR = 1, // plain bilinear upscale in a fragment shader
	TL_MODE_METALFX = 2,  // MetalFX spatial upscale
	TL_MODE_TEMPORAL = 3, // MetalFX temporal upscale (needs tl_interop_set_temporal each frame)
};

// Adopts Minecraft's MTLDevice (exported via VK_EXT_metal_objects) and creates our own queue on it.
int32_t tl_interop_init(void *mtl_device, const char *metallib_path);

// Encodes and commits one command buffer on our queue: wait for shared_event >= wait_value, run mode
// reading in_tex and writing out_tex, then set shared_event to signal_value. Never blocks the CPU.
int32_t tl_interop_frame(int32_t mode, void *in_tex, void *out_tex, void *shared_event, uint64_t wait_value,
                         uint64_t signal_value);

// --- Gate 2: sun shadows over vanilla's image, see shadows.m ---

// Per-frame inputs. Matrices are column-major (JOML layout) and act on camera-relative world positions.
typedef struct {
	float view_proj[16];        // camera-relative world -> camera clip space
	float inv_view_proj[16];    // inverse of view_proj
	float shadow_view_proj[16]; // camera-relative world -> shadow clip space, for drawing the map this frame
	float shadow_sample[16];    // camera-relative world -> shadow clip space of the map as last drawn
	float sun_dir[3];           // unit vector towards the sun
	float strength;             // how dark full shadow is: colour *= 1 - strength
	int32_t debug;              // 0 normal, 1 reconstructed position, 2 shadow factor
	int32_t shadow_map_size;    // texels per side
	int32_t render_map;         // 0 = reuse the map from an earlier frame (draws are ignored)
	int32_t overlay;            // 1 = darken vanilla's colour at the end of the frame (Gate 2 mode)
} tl_shadow_params;

enum { TL_DRAW_CUTOUT = 1 };

// One section/layer draw for the shadow pass. index_buffer == NULL means sequential quads (0,1,2,2,3,0).
typedef struct {
	void *vertex_buffer;   // MTLBuffer holding vanilla BLOCK vertices (28 bytes each)
	uint64_t vertex_offset;
	void *index_buffer;
	uint64_t index_offset;
	uint32_t index_count;
	uint32_t index_type;   // 0 = uint16, 1 = uint32
	float origin[3];       // section origin relative to the camera
	uint32_t flags;        // TL_DRAW_*
} tl_draw;

// Queues shadow work for the next tl_interop_frame: draw casters into the shadow map, then darken
// shadowed pixels of the low-res world colour. world_depth is the world's depth; hand_depth is
// non-zero only where the first-person hand was drawn, which is left alone. Inputs are copied.
int32_t tl_interop_set_shadows(const tl_shadow_params *params, const tl_draw *draws, int32_t draw_count,
                               void *world_depth, void *hand_depth, void *atlas_tex);

// --- Gate 3: Minecraft's opaque terrain drawn and lit on Metal, see terrain.m ---

// Matches TerrainParams in shaders/terrain.metal. Column-major matrices on camera-relative positions.
typedef struct {
	float view_proj[16];
	float shadow_sample[16]; // shadow clip space of the current shadow map
	float sun_dir_day[4];    // xyz towards the sun, w = daylight 0..1
	float fog_color[4];
	float fog[4];            // environmental start, end, render-distance start, end
	float light[4];          // sky brightness, shadow strength, shadow texel size, shadows on (0/1)
	float camera_offset[4];  // xyz = camera block position - camera position; draw origins are relative
	                         // to the camera block position in Gate 3
} tl_terrain_params;

// Commits a command buffer on our queue that waits for shared_event >= wait_value, draws the shadow
// map if one is queued (tl_interop_set_shadows), draws the SOLID and CUTOUT draws into colour/depth
// (the low-res world targets, mid-frame), and sets shared_event to signal_value.
int32_t tl_interop_terrain(const tl_terrain_params *params, const tl_draw *draws, int32_t draw_count, void *color_tex,
                           void *depth_tex, void *atlas_tex, void *shared_event, uint64_t wait_value, uint64_t signal_value);

// --- Gate 4: volumetric sun light, see volumetric.m ---

// Matches VolumetricParams in shaders/volumetric.metal.
typedef struct {
	float inv_view_proj[16];
	float shadow_sample[16];
	float sun_dir_day[4];  // xyz towards the sun, w = daylight 0..1
	float sun_color[4];    // rgb, a = intensity
	float medium[4];       // density per block, height falloff per block, camera world y, max distance
	float misc[4];         // anisotropy g, frame index, shadow texel size, steps
} tl_volumetric_params;

// Queues volumetric light for the next tl_interop_frame: marched against world_depth (the world's
// low-res depth) and the current shadow map, composited before the upscale. Needs a valid shadow map.
int32_t tl_interop_set_volumetric(const tl_volumetric_params *params, void *world_depth);

// --- Gate 4: water reflections, see water.m ---

// Matches WaterParams in shaders/water.metal.
typedef struct {
	float view_proj[16];     // camera-relative world -> clip, Minecraft conventions (row 0 = NDC y -1)
	float inv_view_proj[16];
	float sky_horizon[4];    // rgb
	float sky_zenith[4];     // rgb, a = reflection strength
	float misc[4];           // max distance, steps, frame index, unused
} tl_water_params;

// Queues water reflections for the next tl_interop_frame. world_depth is the world's low-res depth
// including translucents; the terrain-only depth comes from this frame's Metal terrain pass.
int32_t tl_interop_set_water(const tl_water_params *params, void *world_depth);

// --- Gate 5: MetalFX temporal upscaling, see temporal.m ---

// Matches TemporalParams in shaders/temporal.metal. Camera-relative, Minecraft clip conventions.
typedef struct {
	float inv_view_proj[16];      // this frame, jittered
	float view_proj[16];          // this frame, unjittered
	float prev_view_proj[16];     // last frame, unjittered
	float camera_delta[4];        // this camera position - last camera position
	float jitter[4];              // x, y in pixels; z = 1 to reset history
} tl_temporal_params;

// Inputs for TL_MODE_TEMPORAL in the next tl_interop_frame: world_depth is the world's low-res depth;
// hand_depth is non-zero only where the first-person hand was drawn.
int32_t tl_interop_set_temporal(const tl_temporal_params *params, void *world_depth, void *hand_depth);

// Returns and resets the GPU time and count of completed interop frames, and failed command buffers.
int32_t tl_interop_take_stats(int64_t *gpu_ns_total, int64_t *frames, int32_t *errors);

// Drains our queue and releases everything. If shared_event is non-NULL it is first signalled to the
// maximum value, so command buffers waiting on a Vulkan signal that never came can finish.
void tl_interop_shutdown(void *shared_event);
