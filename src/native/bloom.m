// Bloom, see shaders/bloom.metal.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalPerformanceShaders/MetalPerformanceShaders.h>
#include "bloom.h"

typedef struct {
	float threshold, knee, intensity, pad;
} BloomParams;

static id<MTLDevice> b_device;
static id<MTLLibrary> b_library;
static id<MTLComputePipelineState> b_extract_pso;
static id<MTLRenderPipelineState> b_apply_pso;
static MTLPixelFormat b_apply_format;
static id<MTLSamplerState> b_linear;
static id<MTLTexture> b_bright, b_blurred; // quarter resolution
static MPSImageGaussianBlur *b_blur;
static BOOL b_enabled;
static BloomParams b_params;

int32_t tl_bloom_init(id<MTLDevice> device, id<MTLLibrary> library) {
	b_device = device;
	b_library = library;
	NSError *error = nil;
	id<MTLFunction> fn = [library newFunctionWithName:@"tl_bloom_extract"];
	if (!fn) return TL_ERR_LIBRARY;
	b_extract_pso = [device newComputePipelineStateWithFunction:fn error:&error];
	if (!b_extract_pso) {
		NSLog(@"tilelight: bloom pipeline: %@", error);
		return TL_ERR_PIPELINE;
	}
	MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
	sd.minFilter = MTLSamplerMinMagFilterLinear;
	sd.magFilter = MTLSamplerMinMagFilterLinear;
	sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
	sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
	b_linear = [device newSamplerStateWithDescriptor:sd];
	b_blur = [[MPSImageGaussianBlur alloc] initWithDevice:device sigma:6.0f];
	b_blur.edgeMode = MPSImageEdgeModeClamp;
	return b_linear && b_blur ? TL_OK : TL_ERR_PIPELINE;
}

int32_t tl_interop_set_bloom(float threshold, float knee, float intensity) {
	b_enabled = intensity > 0.0f;
	b_params = (BloomParams){threshold, knee, intensity, 0.0f};
	return TL_OK;
}

static int32_t ensure_targets(NSUInteger w, NSUInteger h) {
	if (b_bright && b_bright.width == w && b_bright.height == h) return TL_OK;
	MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float
	                                                                              width:w
	                                                                             height:h
	                                                                          mipmapped:NO];
	td.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
	td.storageMode = MTLStorageModePrivate;
	b_bright = [b_device newTextureWithDescriptor:td];
	b_blurred = [b_device newTextureWithDescriptor:td];
	return b_bright && b_blurred ? TL_OK : TL_ERR_BUFFER;
}

static int32_t ensure_apply(MTLPixelFormat format) {
	if (b_apply_pso && b_apply_format == format) return TL_OK;
	NSError *error = nil;
	MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
	d.label = @"tilelight bloom apply";
	d.vertexFunction = [b_library newFunctionWithName:@"tl_fullscreen_vs"];
	d.fragmentFunction = [b_library newFunctionWithName:@"tl_bloom_apply_fs"];
	if (!d.vertexFunction || !d.fragmentFunction) return TL_ERR_LIBRARY;
	d.colorAttachments[0].pixelFormat = format;
	b_apply_pso = [b_device newRenderPipelineStateWithDescriptor:d error:&error];
	if (!b_apply_pso) {
		NSLog(@"tilelight: bloom apply pipeline: %@", error);
		return TL_ERR_PIPELINE;
	}
	b_apply_format = format;
	return TL_OK;
}

int32_t tl_bloom_encode(id<MTLCommandBuffer> cb, id<MTLTexture> color) {
	if (!b_enabled || !b_device) return TL_OK;
	int32_t rc = ensure_targets(MAX(color.width / 4, 1u), MAX(color.height / 4, 1u));
	if (rc == TL_OK) rc = ensure_apply(color.pixelFormat);
	if (rc != TL_OK) return rc;

	id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
	if (!ce) return TL_ERR_GPU;
	ce.label = @"tilelight bloom extract";
	[ce setComputePipelineState:b_extract_pso];
	[ce setTexture:color atIndex:0];
	[ce setTexture:b_bright atIndex:1];
	[ce setSamplerState:b_linear atIndex:0];
	[ce setBytes:&b_params length:sizeof(b_params) atIndex:0];
	[ce dispatchThreads:MTLSizeMake(b_bright.width, b_bright.height, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	[ce endEncoding];

	[b_blur encodeToCommandBuffer:cb sourceTexture:b_bright destinationTexture:b_blurred];

	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = color;
	rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
	if (!enc) return TL_ERR_GPU;
	enc.label = @"tilelight bloom apply";
	[enc setRenderPipelineState:b_apply_pso];
	[enc setFragmentTexture:b_blurred atIndex:0];
	[enc setFragmentSamplerState:b_linear atIndex:0];
	[enc setFragmentBytes:&b_params length:sizeof(b_params) atIndex:0];
	[enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[enc endEncoding];
	return TL_OK;
}

void tl_bloom_shutdown(void) {
	b_enabled = NO;
	b_bright = b_blurred = nil;
	b_blur = nil;
	b_extract_pso = nil;
	b_apply_pso = nil;
	b_linear = nil;
	b_library = nil;
	b_device = nil;
}
