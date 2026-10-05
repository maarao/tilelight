// Gate 3: draws Minecraft's opaque terrain on Metal, mid-frame, in place of vanilla's terrain pass.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include "shadows.h"
#include "terrain.h"
#include "water.h"

static id<MTLDevice> t_device;
static id<MTLRenderPipelineState> t_solid_pso, t_cutout_pso;
static MTLPixelFormat t_color_format, t_depth_format;
static id<MTLLibrary> t_library;
static id<MTLDepthStencilState> t_depth_state;
static id<MTLSamplerState> t_atlas_sampler;

int32_t tl_terrain_init(id<MTLDevice> device, id<MTLLibrary> library) {
	t_device = device;
	t_library = library;
	MTLDepthStencilDescriptor *ds = [MTLDepthStencilDescriptor new];
	ds.depthCompareFunction = MTLCompareFunctionGreaterEqual; // Minecraft uses reverse-Z
	ds.depthWriteEnabled = YES;
	t_depth_state = [device newDepthStencilStateWithDescriptor:ds];

	MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
	sd.minFilter = MTLSamplerMinMagFilterNearest;
	sd.magFilter = MTLSamplerMinMagFilterNearest;
	sd.mipFilter = MTLSamplerMipFilterNearest;
	t_atlas_sampler = [device newSamplerStateWithDescriptor:sd];
	return t_depth_state && t_atlas_sampler ? TL_OK : TL_ERR_PIPELINE;
}

static int32_t ensure_pipelines(MTLPixelFormat color, MTLPixelFormat depth) {
	if (t_solid_pso && t_color_format == color && t_depth_format == depth) return TL_OK;
	MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
	vd.attributes[0].format = MTLVertexFormatFloat3;
	vd.attributes[0].offset = 0;
	vd.attributes[1].format = MTLVertexFormatUChar4Normalized;
	vd.attributes[1].offset = 12;
	vd.attributes[2].format = MTLVertexFormatFloat2;
	vd.attributes[2].offset = 16;
	vd.attributes[3].format = MTLVertexFormatShort2;
	vd.attributes[3].offset = 24;
	for (int i = 0; i < 4; i++) vd.attributes[i].bufferIndex = 0;
	vd.layouts[0].stride = 28;

	NSError *error = nil;
	MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
	d.vertexFunction = [t_library newFunctionWithName:@"tl_terrain_vs"];
	d.vertexDescriptor = vd;
	d.colorAttachments[0].pixelFormat = color;
	d.depthAttachmentPixelFormat = depth;
	d.fragmentFunction = [t_library newFunctionWithName:@"tl_terrain_fs"];
	d.label = @"tilelight terrain solid";
	id<MTLRenderPipelineState> solid = [t_device newRenderPipelineStateWithDescriptor:d error:&error];
	d.fragmentFunction = [t_library newFunctionWithName:@"tl_terrain_cutout_fs"];
	d.label = @"tilelight terrain cutout";
	id<MTLRenderPipelineState> cutout = [t_device newRenderPipelineStateWithDescriptor:d error:&error];
	if (!solid || !cutout) {
		NSLog(@"tilelight: terrain pipelines: %@", error);
		return TL_ERR_PIPELINE;
	}
	t_solid_pso = solid;
	t_cutout_pso = cutout;
	t_color_format = color;
	t_depth_format = depth;
	return TL_OK;
}

static int32_t encode_terrain(id<MTLCommandBuffer> cb, const tl_terrain_params *params, const tl_draw *draws,
                              int32_t draw_count, id<MTLTexture> color, id<MTLTexture> depth, id<MTLTexture> atlas) {
	int32_t rc = ensure_pipelines(color.pixelFormat, depth.pixelFormat);
	if (rc != TL_OK) return rc;
	uint32_t max_quad_indices = 0;
	for (int32_t i = 0; i < draw_count; i++) {
		if (!draws[i].index_buffer) max_quad_indices = MAX(max_quad_indices, draws[i].index_count);
	}
	id<MTLBuffer> quads = tl_shadows_quad_indices(max_quad_indices);
	if (!quads) return TL_ERR_BUFFER;

	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	// The sky is already drawn into colour; depth was cleared by Minecraft's clear pass.
	rp.colorAttachments[0].texture = color;
	rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	rp.depthAttachment.texture = depth;
	rp.depthAttachment.loadAction = MTLLoadActionLoad;
	rp.depthAttachment.storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
	if (!enc) return TL_ERR_GPU;
	enc.label = @"tilelight terrain";
	[enc setDepthStencilState:t_depth_state];
	// Same as vanilla's terrain pipelines (back faces culled, clockwise front). The clip-space Y flip
	// puts every triangle on the same pixels as Vulkan would, so the winding carries over unchanged.
	[enc setFrontFacingWinding:MTLWindingClockwise];
	[enc setCullMode:MTLCullModeBack];
	// Never sample a map that was not (fully) drawn.
	tl_terrain_params p = *params;
	if (!tl_shadows_map_valid()) p.light[3] = 0.0f;
	[enc setVertexBytes:&p length:sizeof(p) atIndex:1];
	[enc setFragmentBytes:&p length:sizeof(p) atIndex:0];
	[enc setFragmentTexture:atlas atIndex:0];
	[enc setFragmentTexture:tl_shadows_map() atIndex:1];
	[enc setFragmentSamplerState:t_atlas_sampler atIndex:0];
	[enc setFragmentSamplerState:tl_shadows_compare_sampler() atIndex:1];

	// Solid first so cutout fragments (which disable hidden-surface removal) are depth-rejected
	// wherever solid terrain covers them.
	for (int pass = 0; pass < 2; pass++) {
		BOOL cutout = pass == 1;
		[enc setRenderPipelineState:cutout ? t_cutout_pso : t_solid_pso];
		for (int32_t i = 0; i < draw_count; i++) {
			const tl_draw *dr = &draws[i];
			if (((dr->flags & TL_DRAW_CUTOUT) != 0) != cutout || dr->index_count == 0 || !dr->vertex_buffer) continue;
			[enc setVertexBuffer:(__bridge id<MTLBuffer>)dr->vertex_buffer offset:dr->vertex_offset atIndex:0];
			[enc setVertexBytes:dr->origin length:sizeof(dr->origin) atIndex:2];
			BOOL custom = dr->index_buffer != NULL;
			[enc drawIndexedPrimitives:MTLPrimitiveTypeTriangle
			                indexCount:dr->index_count
			                 indexType:custom && !dr->index_type ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32
			               indexBuffer:custom ? (__bridge id<MTLBuffer>)dr->index_buffer : quads
			         indexBufferOffset:custom ? dr->index_offset : 0];
		}
	}
	[enc endEncoding];
	return TL_OK;
}

int32_t tl_interop_terrain(const tl_terrain_params *params, const tl_draw *draws, int32_t draw_count, void *color_tex,
                           void *depth_tex, void *atlas_tex, void *shared_event, uint64_t wait_value, uint64_t signal_value) {
	if (!t_device) return TL_ERR_NOT_INITIALIZED;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = tl_interop_new_command_buffer();
		if (!cb) return TL_ERR_NOT_COMMITTED;
		cb.label = @"tilelight terrain";
		id<MTLSharedEvent> event = (__bridge id<MTLSharedEvent>)shared_event;
		[cb encodeWaitForEvent:event value:wait_value];
		int32_t rc = (color_tex && depth_tex && atlas_tex && draw_count >= 0) ? TL_OK : TL_ERR_BUFFER;
		// The shadow map (if due this frame) must exist before terrain samples it.
		if (rc == TL_OK) rc = tl_shadows_encode_map(cb);
		if (rc == TL_OK) {
			rc = encode_terrain(cb, params, draws, draw_count, (__bridge id<MTLTexture>)color_tex,
			                    (__bridge id<MTLTexture>)depth_tex, (__bridge id<MTLTexture>)atlas_tex);
		}
		// Terrain-only depth, so the end of the frame can tell where vanilla drew water over it.
		if (rc == TL_OK) rc = tl_water_capture_terrain_depth(cb, (__bridge id<MTLTexture>)depth_tex);
		// Always signal, even on error, so Vulkan's wait can never deadlock.
		[cb encodeSignalEvent:event value:signal_value];
		tl_interop_track(cb);
		[cb commit];
		return rc;
	}
}

void tl_terrain_shutdown(void) {
	t_solid_pso = t_cutout_pso = nil;
	t_depth_state = nil;
	t_atlas_sampler = nil;
	t_library = nil;
	t_device = nil;
}
