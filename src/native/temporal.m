// Gate 5: MetalFX temporal upscaling, see shaders/temporal.metal.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#include "temporal.h"

static id<MTLDevice> m_device;
static id<MTLComputePipelineState> m_motion_pso;
static id<MTLTexture> m_motion;
static id<MTLFXTemporalScaler> m_scaler;
static NSUInteger m_in_w, m_in_h, m_out_w, m_out_h;
static MTLPixelFormat m_color_fmt, m_depth_fmt, m_out_fmt;
static id<MTLTexture> m_scratch; // when the output texture lacks the usage MetalFX needs

static BOOL m_pending;
static tl_temporal_params m_params;
static id<MTLTexture> m_depth, m_hand_depth;

int32_t tl_temporal_init(id<MTLDevice> device, id<MTLLibrary> library) {
	m_device = device;
	NSError *error = nil;
	id<MTLFunction> fn = [library newFunctionWithName:@"tl_motion_vectors"];
	if (!fn) return TL_ERR_LIBRARY;
	m_motion_pso = [device newComputePipelineStateWithFunction:fn error:&error];
	if (!m_motion_pso) {
		NSLog(@"tilelight: motion vector pipeline: %@", error);
		return TL_ERR_PIPELINE;
	}
	return TL_OK;
}

int32_t tl_interop_set_temporal(const tl_temporal_params *params, void *world_depth, void *hand_depth) {
	if (!m_device) return TL_ERR_NOT_INITIALIZED;
	if (!world_depth || !hand_depth) return TL_ERR_BUFFER;
	m_params = *params;
	m_depth = (__bridge id<MTLTexture>)world_depth;
	m_hand_depth = (__bridge id<MTLTexture>)hand_depth;
	m_pending = YES;
	return TL_OK;
}

static int32_t ensure_scaler(id<MTLTexture> in, id<MTLTexture> depth, id<MTLTexture> out) {
	if (m_scaler && m_in_w == in.width && m_in_h == in.height && m_out_w == out.width && m_out_h == out.height &&
	    m_color_fmt == in.pixelFormat && m_depth_fmt == depth.pixelFormat && m_out_fmt == out.pixelFormat) {
		return TL_OK;
	}
	if (![MTLFXTemporalScalerDescriptor supportsDevice:m_device]) return TL_ERR_NO_DEVICE;
	MTLFXTemporalScalerDescriptor *d = [MTLFXTemporalScalerDescriptor new];
	d.inputWidth = in.width;
	d.inputHeight = in.height;
	d.outputWidth = out.width;
	d.outputHeight = out.height;
	d.colorTextureFormat = in.pixelFormat;
	d.depthTextureFormat = depth.pixelFormat;
	d.motionTextureFormat = MTLPixelFormatRG16Float;
	d.outputTextureFormat = out.pixelFormat;
	id<MTLFXTemporalScaler> scaler = [d newTemporalScalerWithDevice:m_device];
	if (!scaler) return TL_ERR_PIPELINE;

	MTLTextureDescriptor *md = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRG16Float
	                                                                              width:in.width
	                                                                             height:in.height
	                                                                          mipmapped:NO];
	md.usage = MTLTextureUsageShaderWrite | scaler.motionTextureUsage;
	md.storageMode = MTLStorageModePrivate;
	m_motion = [m_device newTextureWithDescriptor:md];
	if (!m_motion) return TL_ERR_BUFFER;
	m_motion.label = @"tilelight motion vectors";

	m_scratch = nil;
	if ((out.usage & scaler.outputTextureUsage) != scaler.outputTextureUsage) {
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:out.pixelFormat
		                                                                              width:out.width
		                                                                             height:out.height
		                                                                          mipmapped:NO];
		td.usage = scaler.outputTextureUsage;
		td.storageMode = MTLStorageModePrivate;
		m_scratch = [m_device newTextureWithDescriptor:td];
		if (!m_scratch) return TL_ERR_BUFFER;
	}
	m_scaler = scaler;
	m_in_w = in.width;
	m_in_h = in.height;
	m_out_w = out.width;
	m_out_h = out.height;
	m_color_fmt = in.pixelFormat;
	m_depth_fmt = depth.pixelFormat;
	m_out_fmt = out.pixelFormat;
	m_params.jitter[2] = 1.0f; // new scaler: no history yet
	return TL_OK;
}

int32_t tl_temporal_encode(id<MTLCommandBuffer> cb, id<MTLTexture> in, id<MTLTexture> out) {
	if (!m_pending) return TL_ERR_BUFFER; // temporal mode needs inputs every frame
	m_pending = NO;
	id<MTLTexture> depth = m_depth, hand = m_hand_depth;
	m_depth = m_hand_depth = nil;
	if (depth.width != in.width || depth.height != in.height) return TL_ERR_BUFFER;
	int32_t rc = ensure_scaler(in, depth, out);
	if (rc != TL_OK) return rc;

	id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
	if (!ce) return TL_ERR_GPU;
	ce.label = @"tilelight motion vectors";
	[ce setComputePipelineState:m_motion_pso];
	[ce setTexture:m_motion atIndex:0];
	[ce setTexture:depth atIndex:1];
	[ce setTexture:hand atIndex:2];
	[ce setBytes:&m_params length:sizeof(m_params) atIndex:0];
	[ce dispatchThreads:MTLSizeMake(in.width, in.height, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	[ce endEncoding];

	m_scaler.colorTexture = in;
	m_scaler.depthTexture = depth;
	m_scaler.motionTexture = m_motion;
	m_scaler.outputTexture = m_scratch ? m_scratch : out;
	m_scaler.jitterOffsetX = m_params.jitter[0];
	m_scaler.jitterOffsetY = m_params.jitter[1];
	m_scaler.motionVectorScaleX = 1.0f;
	m_scaler.motionVectorScaleY = 1.0f;
	m_scaler.depthReversed = YES;
	m_scaler.reset = m_params.jitter[2] != 0.0f;
	[m_scaler encodeToCommandBuffer:cb];
	if (m_scratch) {
		id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
		if (!blit) return TL_ERR_GPU;
		[blit copyFromTexture:m_scratch toTexture:out];
		[blit endEncoding];
	}
	return TL_OK;
}

void tl_temporal_shutdown(void) {
	m_pending = NO;
	m_depth = m_hand_depth = nil;
	m_scaler = nil;
	m_scratch = nil;
	m_motion = nil;
	m_motion_pso = nil;
	m_device = nil;
}
