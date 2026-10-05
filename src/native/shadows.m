// Gate 2: a sun shadow map drawn from Minecraft's own chunk meshes, applied to vanilla's image.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <string.h>
#include "shadows.h"

static id<MTLDevice> s_device;
static id<MTLLibrary> s_library;
static id<MTLRenderPipelineState> s_solid_pso, s_cutout_pso;
static id<MTLRenderPipelineState> s_apply_pso;
static MTLPixelFormat s_apply_color_format;
static id<MTLDepthStencilState> s_depth_less;
static id<MTLSamplerState> s_nearest, s_compare;
static id<MTLTexture> s_shadow_map;
static id<MTLBuffer> s_quad_indices; // sequential quads as uint32
static uint32_t s_quad_index_capacity;

// Inputs queued by tl_interop_set_shadows for the next frame.
static BOOL s_pending;
static BOOL s_map_drawn; // the queued map was already drawn this frame (by the terrain pass)
static tl_shadow_params s_params;
static tl_draw *s_draws;
static int32_t s_draw_count, s_draw_capacity;
static id<MTLTexture> s_depth, s_hand_depth, s_atlas;

static MTLVertexDescriptor *block_vertex_descriptor(void) {
	MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
	vd.attributes[0].format = MTLVertexFormatFloat3;
	vd.attributes[0].offset = 0;
	vd.attributes[1].format = MTLVertexFormatUChar4Normalized;
	vd.attributes[1].offset = 12;
	vd.attributes[2].format = MTLVertexFormatFloat2;
	vd.attributes[2].offset = 16;
	for (int i = 0; i < 3; i++) vd.attributes[i].bufferIndex = 0;
	vd.layouts[0].stride = 28;
	return vd;
}

int32_t tl_shadows_init(id<MTLDevice> device, id<MTLLibrary> library) {
	s_device = device;
	s_library = library;
	NSError *error = nil;

	MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
	d.vertexFunction = [library newFunctionWithName:@"tl_shadow_vs"];
	if (!d.vertexFunction) return TL_ERR_LIBRARY;
	d.vertexDescriptor = block_vertex_descriptor();
	d.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
	d.label = @"tilelight shadow solid";
	s_solid_pso = [device newRenderPipelineStateWithDescriptor:d error:&error];
	d.fragmentFunction = [library newFunctionWithName:@"tl_shadow_cutout_fs"];
	d.label = @"tilelight shadow cutout";
	s_cutout_pso = d.fragmentFunction ? [device newRenderPipelineStateWithDescriptor:d error:&error] : nil;
	if (!s_solid_pso || !s_cutout_pso) {
		NSLog(@"tilelight: shadow pipelines: %@", error);
		return TL_ERR_PIPELINE;
	}

	MTLDepthStencilDescriptor *ds = [MTLDepthStencilDescriptor new];
	ds.depthCompareFunction = MTLCompareFunctionLess;
	ds.depthWriteEnabled = YES;
	s_depth_less = [device newDepthStencilStateWithDescriptor:ds];

	MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
	sd.minFilter = MTLSamplerMinMagFilterNearest;
	sd.magFilter = MTLSamplerMinMagFilterNearest;
	s_nearest = [device newSamplerStateWithDescriptor:sd];
	sd.minFilter = MTLSamplerMinMagFilterLinear;
	sd.magFilter = MTLSamplerMinMagFilterLinear;
	sd.compareFunction = MTLCompareFunctionLessEqual;
	sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
	sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
	s_compare = [device newSamplerStateWithDescriptor:sd];
	return TL_OK;
}

static int32_t ensure_apply(MTLPixelFormat color_format) {
	if (s_apply_pso && s_apply_color_format == color_format) return TL_OK;
	NSError *error = nil;
	MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
	d.label = @"tilelight shadow apply";
	d.vertexFunction = [s_library newFunctionWithName:@"tl_fullscreen_vs"];
	d.fragmentFunction = [s_library newFunctionWithName:@"tl_shadow_apply_fs"];
	if (!d.vertexFunction || !d.fragmentFunction) return TL_ERR_LIBRARY;
	d.colorAttachments[0].pixelFormat = color_format;
	s_apply_pso = [s_device newRenderPipelineStateWithDescriptor:d error:&error];
	if (!s_apply_pso) {
		NSLog(@"tilelight: shadow apply pipeline: %@", error);
		return TL_ERR_PIPELINE;
	}
	s_apply_color_format = color_format;
	return TL_OK;
}

static int32_t ensure_quad_indices(uint32_t index_count) {
	if (s_quad_indices && index_count <= s_quad_index_capacity) return TL_OK;
	uint32_t capacity = MAX(index_count, 6u * 16384u);
	capacity = (capacity + 5) / 6 * 6;
	id<MTLBuffer> buf = [s_device newBufferWithLength:capacity * sizeof(uint32_t) options:MTLResourceStorageModeShared];
	if (!buf) return TL_ERR_BUFFER;
	uint32_t *idx = buf.contents;
	for (uint32_t q = 0, i = 0; i < capacity; q++, i += 6) {
		uint32_t v = q * 4;
		idx[i] = v; idx[i + 1] = v + 1; idx[i + 2] = v + 2;
		idx[i + 3] = v + 2; idx[i + 4] = v + 3; idx[i + 5] = v;
	}
	buf.label = @"tilelight quad indices";
	s_quad_indices = buf;
	s_quad_index_capacity = capacity;
	return TL_OK;
}

static int32_t ensure_shadow_map(int32_t size) {
	if (s_shadow_map && (int32_t)s_shadow_map.width == size) return TL_OK;
	MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
	                                                                              width:size
	                                                                             height:size
	                                                                          mipmapped:NO];
	td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
	td.storageMode = MTLStorageModePrivate;
	s_shadow_map = [s_device newTextureWithDescriptor:td];
	s_shadow_map.label = @"tilelight shadow map";
	return s_shadow_map ? TL_OK : TL_ERR_BUFFER;
}

int32_t tl_interop_set_shadows(const tl_shadow_params *params, const tl_draw *draws, int32_t draw_count,
                               void *world_depth, void *hand_depth, void *atlas_tex) {
	if (!s_device) return TL_ERR_NOT_INITIALIZED;
	if (draw_count < 0 || !atlas_tex || (params->overlay && (!world_depth || !hand_depth))) return TL_ERR_BUFFER;
	if (draw_count > s_draw_capacity) {
		int32_t capacity = MAX(draw_count, s_draw_capacity * 2);
		tl_draw *grown = realloc(s_draws, (size_t)capacity * sizeof(tl_draw));
		if (!grown) return TL_ERR_BUFFER;
		s_draws = grown;
		s_draw_capacity = capacity;
	}
	memcpy(s_draws, draws, (size_t)draw_count * sizeof(tl_draw));
	s_draw_count = draw_count;
	s_params = *params;
	s_depth = (__bridge id<MTLTexture>)world_depth;
	s_hand_depth = (__bridge id<MTLTexture>)hand_depth;
	s_atlas = (__bridge id<MTLTexture>)atlas_tex;
	s_pending = YES;
	s_map_drawn = NO;
	return TL_OK;
}

static int32_t encode_shadow_map(id<MTLCommandBuffer> cb) {
	int32_t rc = ensure_shadow_map(s_params.shadow_map_size);
	if (rc != TL_OK) return rc;
	uint32_t max_quad_indices = 0;
	for (int32_t i = 0; i < s_draw_count; i++) {
		if (!s_draws[i].index_buffer) max_quad_indices = MAX(max_quad_indices, s_draws[i].index_count);
	}
	if ((rc = ensure_quad_indices(max_quad_indices)) != TL_OK) return rc;

	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.depthAttachment.texture = s_shadow_map;
	rp.depthAttachment.loadAction = MTLLoadActionClear;
	rp.depthAttachment.clearDepth = 1.0;
	rp.depthAttachment.storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
	enc.label = @"tilelight shadow map";
	[enc setDepthStencilState:s_depth_less];
	// Casters are drawn double-sided: thin cutout geometry (leaves, plants) has no back faces.
	[enc setCullMode:MTLCullModeNone];
	[enc setVertexBytes:s_params.shadow_view_proj length:sizeof(s_params.shadow_view_proj) atIndex:1];
	[enc setFragmentTexture:s_atlas atIndex:0];
	[enc setFragmentSamplerState:s_nearest atIndex:0];

	id<MTLRenderPipelineState> current = nil;
	for (int32_t i = 0; i < s_draw_count; i++) {
		const tl_draw *dr = &s_draws[i];
		if (dr->index_count == 0 || !dr->vertex_buffer) continue;
		id<MTLRenderPipelineState> pso = (dr->flags & TL_DRAW_CUTOUT) ? s_cutout_pso : s_solid_pso;
		if (pso != current) {
			[enc setRenderPipelineState:pso];
			current = pso;
		}
		[enc setVertexBuffer:(__bridge id<MTLBuffer>)dr->vertex_buffer offset:dr->vertex_offset atIndex:0];
		[enc setVertexBytes:dr->origin length:sizeof(dr->origin) atIndex:2];
		if (dr->index_buffer) {
			[enc drawIndexedPrimitives:MTLPrimitiveTypeTriangle
			                indexCount:dr->index_count
			                 indexType:dr->index_type ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16
			               indexBuffer:(__bridge id<MTLBuffer>)dr->index_buffer
			         indexBufferOffset:dr->index_offset];
		} else {
			[enc drawIndexedPrimitives:MTLPrimitiveTypeTriangle
			                indexCount:dr->index_count
			                 indexType:MTLIndexTypeUInt32
			               indexBuffer:s_quad_indices
			         indexBufferOffset:0];
		}
	}
	[enc endEncoding];
	return TL_OK;
}

typedef struct {
	float inv_view_proj[16];
	float shadow_view_proj[16];
	float sun_dir_strength[4];
	int32_t debug;
	float texel;
	float pad[2];
} ApplyParams;

static int32_t encode_apply(id<MTLCommandBuffer> cb, id<MTLTexture> color) {
	int32_t rc = ensure_apply(color.pixelFormat);
	if (rc != TL_OK) return rc;
	ApplyParams p;
	memcpy(p.inv_view_proj, s_params.inv_view_proj, sizeof(p.inv_view_proj));
	memcpy(p.shadow_view_proj, s_params.shadow_sample, sizeof(p.shadow_view_proj));
	memcpy(p.sun_dir_strength, s_params.sun_dir, sizeof(float) * 3);
	p.sun_dir_strength[3] = s_params.strength;
	p.debug = s_params.debug;
	p.texel = 1.0f / (float)s_params.shadow_map_size;

	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = color;
	rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
	enc.label = @"tilelight shadow apply";
	[enc setRenderPipelineState:s_apply_pso];
	[enc setFragmentTexture:s_depth atIndex:0];
	[enc setFragmentTexture:s_shadow_map atIndex:1];
	[enc setFragmentTexture:s_hand_depth atIndex:2];
	[enc setFragmentSamplerState:s_compare atIndex:0];
	[enc setFragmentBytes:&p length:sizeof(p) atIndex:0];
	[enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[enc endEncoding];
	return TL_OK;
}

int32_t tl_shadows_encode_map(id<MTLCommandBuffer> cb) {
	if (!s_pending || s_map_drawn) return TL_OK;
	s_map_drawn = YES;
	if (!s_params.render_map && s_shadow_map) return TL_OK;
	return encode_shadow_map(cb);
}

int32_t tl_shadows_encode_apply(id<MTLCommandBuffer> cb, id<MTLTexture> color) {
	if (!s_pending) return TL_OK;
	int32_t rc = tl_shadows_encode_map(cb);
	if (rc == TL_OK && s_params.overlay) rc = encode_apply(cb, color);
	s_pending = NO;
	s_depth = s_hand_depth = s_atlas = nil;
	return rc;
}

id<MTLTexture> tl_shadows_map(void) {
	return s_shadow_map;
}

id<MTLSamplerState> tl_shadows_compare_sampler(void) {
	return s_compare;
}

id<MTLBuffer> tl_shadows_quad_indices(uint32_t index_count) {
	return ensure_quad_indices(index_count) == TL_OK ? s_quad_indices : nil;
}

void tl_shadows_shutdown(void) {
	s_pending = NO;
	free(s_draws);
	s_draws = NULL;
	s_draw_count = s_draw_capacity = 0;
	s_depth = s_hand_depth = s_atlas = nil;
	s_shadow_map = nil;
	s_quad_indices = nil;
	s_quad_index_capacity = 0;
	s_solid_pso = s_cutout_pso = s_apply_pso = nil;
	s_depth_less = nil;
	s_nearest = s_compare = nil;
	s_library = nil;
	s_device = nil;
}
