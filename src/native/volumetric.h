// Internal interface between interop.m and volumetric.m.
#pragma once
#import <Metal/Metal.h>
#include "tilelight.h"

int32_t tl_volumetric_init(id<MTLDevice> device, id<MTLLibrary> library);

// Marches and composites queued volumetric light over `color` (the low-res world colour), if queued.
int32_t tl_volumetric_encode(id<MTLCommandBuffer> cb, id<MTLTexture> color);

void tl_volumetric_shutdown(void);
