// Internal interface between interop.m and temporal.m.
#pragma once
#import <Metal/Metal.h>
#include "tilelight.h"

int32_t tl_temporal_init(id<MTLDevice> device, id<MTLLibrary> library);

// Writes motion vectors and runs the MetalFX temporal scaler from `in` (low-res) to `out` (native).
// Needs this frame's tl_interop_set_temporal inputs.
int32_t tl_temporal_encode(id<MTLCommandBuffer> cb, id<MTLTexture> in, id<MTLTexture> out);

// Resolves TAA at internal resolution from `in` into a history texture, returned in *resolved, using
// this frame's tl_interop_set_temporal inputs.
int32_t tl_taa_encode(id<MTLCommandBuffer> cb, id<MTLTexture> in, id<MTLTexture> __strong *resolved);

void tl_temporal_shutdown(void);
