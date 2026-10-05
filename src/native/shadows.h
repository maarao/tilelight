// Internal interface between interop.m and shadows.m.
#pragma once
#import <Metal/Metal.h>
#include "tilelight.h"

int32_t tl_shadows_init(id<MTLDevice> device, id<MTLLibrary> library);

// Encodes queued shadow work (if any) into cb, darkening `color` in place. Call before upscaling.
int32_t tl_shadows_encode(id<MTLCommandBuffer> cb, id<MTLTexture> color);

void tl_shadows_shutdown(void);
