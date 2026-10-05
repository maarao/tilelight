// Internal interface between interop.m and bloom.m.
#pragma once
#import <Metal/Metal.h>
#include "tilelight.h"

int32_t tl_bloom_init(id<MTLDevice> device, id<MTLLibrary> library);

// Adds bloom to `color` (the low-res world colour) in place, if enabled with tl_interop_set_bloom.
int32_t tl_bloom_encode(id<MTLCommandBuffer> cb, id<MTLTexture> color);

void tl_bloom_shutdown(void);
