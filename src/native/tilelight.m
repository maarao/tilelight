#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <string.h>
#include "tilelight.h"

// Global renderer state. Only touched from the render thread.
static id<MTLDevice> g_device;
static id<MTLCommandQueue> g_queue;
static id<MTLLibrary> g_library;

int32_t tl_init(const char *metallib_path) {
	if (g_device) return TL_OK;
	@autoreleasepool {
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		if (!device) return TL_ERR_NO_DEVICE;

		NSError *error = nil;
		NSURL *url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:metallib_path]];
		id<MTLLibrary> library = [device newLibraryWithURL:url error:&error];
		if (!library) {
			NSLog(@"tilelight: failed to load %s: %@", metallib_path, error);
			return TL_ERR_LIBRARY;
		}

		g_device = device;
		g_queue = [device newCommandQueue];
		g_queue.label = @"tilelight";
		g_library = library;
	}
	return TL_OK;
}

int32_t tl_device_name(char *buf, int32_t buf_len) {
	if (!g_device) return TL_ERR_NOT_INITIALIZED;
	if (buf_len <= 0) return TL_ERR_BUFFER;
	strlcpy(buf, g_device.name.UTF8String, (size_t)buf_len);
	return TL_OK;
}

int64_t tl_capabilities(void) {
	if (!g_device) return 0;
	int64_t caps = 0;
	if (g_device.supportsRaytracing) caps |= TL_CAP_RAYTRACING;
	if ([g_device supportsFamily:MTLGPUFamilyApple9]) caps |= TL_CAP_APPLE9;
	if (g_device.hasUnifiedMemory) caps |= TL_CAP_UNIFIED_MEMORY;
	if ([g_device supportsFamily:MTLGPUFamilyMetal3]) caps |= TL_CAP_METAL3;
	return caps;
}

int32_t tl_selftest(int32_t n, int64_t *gpu_ns) {
	if (!g_device) return TL_ERR_NOT_INITIALIZED;
	if (n <= 0) return TL_ERR_BUFFER;
	@autoreleasepool {
		NSError *error = nil;
		id<MTLFunction> fn = [g_library newFunctionWithName:@"tl_selftest_kernel"];
		if (!fn) return TL_ERR_LIBRARY;
		id<MTLComputePipelineState> pso = [g_device newComputePipelineStateWithFunction:fn error:&error];
		if (!pso) {
			NSLog(@"tilelight: selftest pipeline: %@", error);
			return TL_ERR_PIPELINE;
		}

		// Shared storage: on Apple Silicon the CPU reads the GPU's output in place, no copy.
		id<MTLBuffer> out = [g_device newBufferWithLength:(NSUInteger)n * sizeof(uint32_t)
		                                          options:MTLResourceStorageModeShared];
		if (!out) return TL_ERR_BUFFER;

		id<MTLCommandBuffer> cb = [g_queue commandBuffer];
		id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
		[enc setComputePipelineState:pso];
		[enc setBuffer:out offset:0 atIndex:0];
		uint32_t count = (uint32_t)n;
		[enc setBytes:&count length:sizeof(count) atIndex:1];
		NSUInteger group = MIN(pso.maxTotalThreadsPerThreadgroup, (NSUInteger)256);
		[enc dispatchThreads:MTLSizeMake((NSUInteger)n, 1, 1) threadsPerThreadgroup:MTLSizeMake(group, 1, 1)];
		[enc endEncoding];
		[cb commit];
		[cb waitUntilCompleted];
		if (cb.status != MTLCommandBufferStatusCompleted) {
			NSLog(@"tilelight: selftest command buffer failed: %@", cb.error);
			return TL_ERR_GPU;
		}

		// Must match tl_selftest_kernel in shaders/selftest.metal.
		const uint32_t *values = out.contents;
		for (uint32_t i = 0; i < count; i++) {
			if (values[i] != i * 2654435761u) return TL_ERR_MISMATCH;
		}
		if (gpu_ns) *gpu_ns = (int64_t)((cb.GPUEndTime - cb.GPUStartTime) * 1e9);
	}
	return TL_OK;
}

void tl_shutdown(void) {
	g_library = nil;
	g_queue = nil;
	g_device = nil;
}
