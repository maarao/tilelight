// Internal interface between interop.m/terrain.m and water.m.
#pragma once
#import <Metal/Metal.h>
#include "tilelight.h"

int32_t tl_water_init(id<MTLDevice> device, id<MTLLibrary> library);

// Called by the terrain pass right after drawing terrain: keeps a copy of the terrain-only depth.
int32_t tl_water_capture_terrain_depth(id<MTLCommandBuffer> cb, id<MTLTexture> depth);

// Applies queued water reflections to `color` (the low-res world colour), if queued.
int32_t tl_water_encode(id<MTLCommandBuffer> cb, id<MTLTexture> color);

void tl_water_shutdown(void);
