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
};

// Adopts Minecraft's MTLDevice (exported via VK_EXT_metal_objects) and creates our own queue on it.
int32_t tl_interop_init(void *mtl_device, const char *metallib_path);

// Encodes and commits one command buffer on our queue: wait for shared_event >= wait_value, run mode
// reading in_tex and writing out_tex, then set shared_event to signal_value. Never blocks the CPU.
int32_t tl_interop_frame(int32_t mode, void *in_tex, void *out_tex, void *shared_event, uint64_t wait_value,
                         uint64_t signal_value);

// Returns and resets the GPU time and count of completed interop frames, and failed command buffers.
int32_t tl_interop_take_stats(int64_t *gpu_ns_total, int64_t *frames, int32_t *errors);

void tl_interop_shutdown(void);
