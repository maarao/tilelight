// Gate 4: volumetric sun light, see shaders/volumetric.metal.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <string.h>
#include "shadows.h"
#include "volumetric.h"

static id<MTLDevice> v_device;
static id<MTLLibrary> v_library;
static id<MTLComputePipelineState> v_march_pso;
static id<MTLRenderPipelineState> v_apply_pso;
static MTLPixelFormat v_apply_format;
static id<MTLSamplerState> v_linear;
static id<MTLTexture> v_fog; // quarter-resolution march result

static BOOL v_pending;
static tl_volumetric_params v_params;
static id<MTLTexture> v_depth;

int32_t tl_volumetric_init(id<MTLDevice> device, id<MTLLibrary> library) {
	v_device = device;
	v_library = library;
	NSError *error = nil;
	id<MTLFunction> fn = [library newFunctionWithName:@"tl_volumetric_march"];
	if (!fn) return TL_ERR_LIBRARY;
	v_march_pso = [device newComputePipelineStateWithFunction:fn error:&error];
	if (!v_march_pso) {
		NSLog(@"tilelight: volumetric march pipeline: %@", error);
		return TL_ERR_PIPELINE;
	}
	MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
	sd.minFilter = MTLSamplerMinMagFilterLinear;
	sd.magFilter = MTLSamplerMinMagFilterLinear;
	sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
	sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
	v_linear = [device newSamplerStateWithDescriptor:sd];
	return TL_OK;
}

int32_t tl_interop_set_volumetric(const tl_volumetric_params *params, void *world_depth) {
	if (!v_device) return TL_ERR_NOT_INITIALIZED;
	if (!world_depth) return TL_ERR_BUFFER;
	v_params = *params;
	v_depth = (__bridge id<MTLTexture>)world_depth;
	v_pending = YES;
	return TL_OK;
}

static int32_t ensure_apply(MTLPixelFormat format) {
	if (v_apply_pso && v_apply_format == format) return TL_OK;
	NSError *error = nil;
	MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
	d.label = @"tilelight volumetric apply";
	d.vertexFunction = [v_library newFunctionWithName:@"tl_fullscreen_vs"];
	d.fragmentFunction = [v_library newFunctionWithName:@"tl_volumetric_apply_fs"];
	if (!d.vertexFunction || !d.fragmentFunction) return TL_ERR_LIBRARY;
	d.colorAttachments[0].pixelFormat = format;
	v_apply_pso = [v_device newRenderPipelineStateWithDescriptor:d error:&error];
	if (!v_apply_pso) {
		NSLog(@"tilelight: volumetric apply pipeline: %@", error);
		return TL_ERR_PIPELINE;
	}
	v_apply_format = format;
	return TL_OK;
}

static int32_t ensure_fog(NSUInteger w, NSUInteger h) {
	if (v_fog && v_fog.width == w && v_fog.height == h) return TL_OK;
	MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float
	                                                                              width:w
	                                                                             height:h
	                                                                          mipmapped:NO];
	td.usage = MTLTextureUsageShaderWrite | MTLTextureUsageShaderRead;
	td.storageMode = MTLStorageModePrivate;
	v_fog = [v_device newTextureWithDescriptor:td];
	v_fog.label = @"tilelight volumetric";
	return v_fog ? TL_OK : TL_ERR_BUFFER;
}

int32_t tl_volumetric_encode(id<MTLCommandBuffer> cb, id<MTLTexture> color) {
	if (!v_pending) return TL_OK;
	v_pending = NO;
	id<MTLTexture> depth = v_depth;
	v_depth = nil;
	// Needs a drawn shadow map; without one there is nothing to shaft.
	if (!tl_shadows_map_valid()) return TL_OK;
	int32_t rc = ensure_fog(MAX(color.width / 4, 1u), MAX(color.height / 4, 1u));
	if (rc == TL_OK) rc = ensure_apply(color.pixelFormat);
	if (rc != TL_OK) return rc;

	id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
	if (!ce) return TL_ERR_GPU;
	ce.label = @"tilelight volumetric march";
	[ce setComputePipelineState:v_march_pso];
	[ce setTexture:v_fog atIndex:0];
	[ce setTexture:depth atIndex:1];
	[ce setTexture:tl_shadows_map() atIndex:2];
	[ce setSamplerState:tl_shadows_compare_sampler() atIndex:0];
	[ce setBytes:&v_params length:sizeof(v_params) atIndex:0];
	[ce dispatchThreads:MTLSizeMake(v_fog.width, v_fog.height, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	[ce endEncoding];

	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = color;
	rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
	if (!enc) return TL_ERR_GPU;
	enc.label = @"tilelight volumetric apply";
	[enc setRenderPipelineState:v_apply_pso];
	[enc setFragmentTexture:v_fog atIndex:0];
	[enc setFragmentSamplerState:v_linear atIndex:0];
	[enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[enc endEncoding];
	return TL_OK;
}

void tl_volumetric_shutdown(void) {
	v_pending = NO;
	v_depth = nil;
	v_fog = nil;
	v_march_pso = nil;
	v_apply_pso = nil;
	v_linear = nil;
	v_library = nil;
	v_device = nil;
}
