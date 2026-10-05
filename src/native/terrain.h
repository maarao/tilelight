// Internal interface between interop.m and terrain.m.
#pragma once
#import <Metal/Metal.h>
#include "tilelight.h"

int32_t tl_terrain_init(id<MTLDevice> device, id<MTLLibrary> library);
void tl_terrain_shutdown(void);

// Provided by interop.m: a command buffer on our queue, and GPU-time/error accounting for it.
id<MTLCommandBuffer> tl_interop_new_command_buffer(void);
void tl_interop_track(id<MTLCommandBuffer> cb);
void tl_interop_check_tracking(id<MTLResource> r, const char *what);
