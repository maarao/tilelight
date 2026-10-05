// Gate 4: water reflections, see shaders/water.metal.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include "water.h"

static id<MTLDevice> w_device;
static id<MTLLibrary> w_library;
static id<MTLRenderPipelineState> w_pso;
static MTLPixelFormat w_format;
static id<MTLSamplerState> w_linear;
static id<MTLTexture> w_terrain_depth; // depth right after the Metal terrain pass
static BOOL w_terrain_depth_valid;     // copied this frame
static id<MTLTexture> w_scene;         // copy of the colour, sampled by reflections

static BOOL w_pending;
static tl_water_params w_params;
static id<MTLTexture> w_world_depth;

int32_t tl_water_init(id<MTLDevice> device, id<MTLLibrary> library) {
	w_device = device;
	w_library = library;
	MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
	sd.minFilter = MTLSamplerMinMagFilterLinear;
	sd.magFilter = MTLSamplerMinMagFilterLinear;
	sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
	sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
	w_linear = [device newSamplerStateWithDescriptor:sd];
	return w_linear ? TL_OK : TL_ERR_PIPELINE;
}

static id<MTLTexture> ensure_copy_target(id<MTLTexture> current, id<MTLTexture> like, MTLTextureUsage usage, NSString *label) {
	if (current && current.width == like.width && current.height == like.height && current.pixelFormat == like.pixelFormat) {
		return current;
	}
	MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:like.pixelFormat
	                                                                              width:like.width
	                                                                             height:like.height
	                                                                          mipmapped:NO];
	td.usage = usage;
	td.storageMode = MTLStorageModePrivate;
	id<MTLTexture> t = [w_device newTextureWithDescriptor:td];
	t.label = label;
	return t;
}

int32_t tl_water_capture_terrain_depth(id<MTLCommandBuffer> cb, id<MTLTexture> depth) {
	w_terrain_depth_valid = NO;
	if (!w_device) return TL_OK;
	w_terrain_depth = ensure_copy_target(w_terrain_depth, depth, MTLTextureUsageShaderRead, @"tilelight terrain depth");
	if (!w_terrain_depth) return TL_ERR_BUFFER;
	id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
	if (!blit) return TL_ERR_GPU;
	[blit copyFromTexture:depth toTexture:w_terrain_depth];
	[blit endEncoding];
	w_terrain_depth_valid = YES;
	return TL_OK;
}

int32_t tl_interop_set_water(const tl_water_params *params, void *world_depth) {
	if (!w_device) return TL_ERR_NOT_INITIALIZED;
	if (!world_depth) return TL_ERR_BUFFER;
	w_params = *params;
	w_world_depth = (__bridge id<MTLTexture>)world_depth;
	w_pending = YES;
	return TL_OK;
}

static int32_t ensure_pipeline(MTLPixelFormat format) {
	if (w_pso && w_format == format) return TL_OK;
	NSError *error = nil;
	MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
	d.label = @"tilelight water";
	d.vertexFunction = [w_library newFunctionWithName:@"tl_fullscreen_vs"];
	d.fragmentFunction = [w_library newFunctionWithName:@"tl_water_fs"];
	if (!d.vertexFunction || !d.fragmentFunction) return TL_ERR_LIBRARY;
	d.colorAttachments[0].pixelFormat = format;
	w_pso = [w_device newRenderPipelineStateWithDescriptor:d error:&error];
	if (!w_pso) {
		NSLog(@"tilelight: water pipeline: %@", error);
		return TL_ERR_PIPELINE;
	}
	w_format = format;
	return TL_OK;
}

int32_t tl_water_encode(id<MTLCommandBuffer> cb, id<MTLTexture> color) {
	if (!w_pending) return TL_OK;
	w_pending = NO;
	id<MTLTexture> world_depth = w_world_depth;
	w_world_depth = nil;
	BOOL have_terrain_depth = w_terrain_depth_valid;
	w_terrain_depth_valid = NO;
	// Without this frame's terrain depth there is no way to tell water from terrain.
	if (!have_terrain_depth || world_depth.width != w_terrain_depth.width || world_depth.height != w_terrain_depth.height) {
		return TL_OK;
	}
	int32_t rc = ensure_pipeline(color.pixelFormat);
	if (rc != TL_OK) return rc;
	w_scene = ensure_copy_target(w_scene, color, MTLTextureUsageShaderRead, @"tilelight scene copy");
	if (!w_scene) return TL_ERR_BUFFER;

	id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
	if (!blit) return TL_ERR_GPU;
	[blit copyFromTexture:color toTexture:w_scene];
	[blit endEncoding];

	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = color;
	rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
	if (!enc) return TL_ERR_GPU;
	enc.label = @"tilelight water";
	[enc setRenderPipelineState:w_pso];
	[enc setFragmentTexture:world_depth atIndex:0];
	[enc setFragmentTexture:w_terrain_depth atIndex:1];
	[enc setFragmentTexture:w_scene atIndex:2];
	[enc setFragmentSamplerState:w_linear atIndex:0];
	[enc setFragmentBytes:&w_params length:sizeof(w_params) atIndex:0];
	[enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[enc endEncoding];
	return TL_OK;
}

void tl_water_shutdown(void) {
	w_pending = NO;
	w_world_depth = nil;
	w_terrain_depth = nil;
	w_terrain_depth_valid = NO;
	w_scene = nil;
	w_pso = nil;
	w_linear = nil;
	w_library = nil;
	w_device = nil;
}
