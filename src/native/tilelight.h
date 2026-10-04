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
