// Internal interface between interop.m and shadows.m.
#pragma once
#import <Metal/Metal.h>
#include "tilelight.h"

int32_t tl_shadows_init(id<MTLDevice> device, id<MTLLibrary> library);

// Draws the queued shadow map into cb if one is due and not yet drawn this frame.
int32_t tl_shadows_encode_map(id<MTLCommandBuffer> cb);

// Finishes the queued shadow work: draws the map if still due and, in overlay mode, darkens `color`
// in place. Clears the queue. Call once per frame, before upscaling.
int32_t tl_shadows_encode_apply(id<MTLCommandBuffer> cb, id<MTLTexture> color);

id<MTLTexture> tl_shadows_map(void);
id<MTLSamplerState> tl_shadows_compare_sampler(void);

// A shared uint32 index buffer of sequential quads (0,1,2,2,3,0, ...) with at least index_count entries.
id<MTLBuffer> tl_shadows_quad_indices(uint32_t index_count);

void tl_shadows_shutdown(void);
