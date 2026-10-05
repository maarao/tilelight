// Gate 1: Metal work inside Minecraft's Vulkan (MoltenVK) frame.
//
// Java exports Minecraft's MTLDevice, MTLTextures and an MTLSharedEvent through
// VK_EXT_metal_objects and hands us the raw pointers. Each frame:
//   Vulkan: world -> low-res colour, submit, signal event = 2n+1
//   Metal (here): wait 2n+1, upscale low-res -> native colour, signal 2n+2
//   Vulkan: wait 2n+2, GUI over native colour, present
// The waits are GPU-side (MTLSharedEvent), so the CPU never blocks.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#include <stdatomic.h>
#include "shadows.h"
#include "terrain.h"
#include "volumetric.h"
#include "water.h"
#include "tilelight.h"

static id<MTLDevice> g_device;
static id<MTLCommandQueue> g_queue;
static id<MTLLibrary> g_library;
static id<MTLRenderPipelineState> g_bilinear_pso;
static MTLPixelFormat g_bilinear_format;
static id<MTLSamplerState> g_linear_sampler;

// MetalFX scaler, recreated when sizes or formats change.
static id<MTLFXSpatialScaler> g_scaler;
static NSUInteger g_scaler_in_w, g_scaler_in_h, g_scaler_out_w, g_scaler_out_h;
static MTLPixelFormat g_scaler_in_fmt, g_scaler_out_fmt;
static id<MTLTexture> g_scaler_scratch; // used when the output texture lacks the usage MetalFX needs

// Rolling GPU time of our command buffers, written from completion handlers.
static _Atomic int64_t g_gpu_ns_total;
static _Atomic int64_t g_frames;
static _Atomic int32_t g_errors;

int32_t tl_interop_init(void *mtl_device, const char *metallib_path) {
	if (!mtl_device) return TL_ERR_NO_DEVICE;
	@autoreleasepool {
		id<MTLDevice> device = (__bridge id<MTLDevice>)mtl_device;
		NSError *error = nil;
		NSURL *url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:metallib_path]];
		id<MTLLibrary> library = [device newLibraryWithURL:url error:&error];
		if (!library) {
			NSLog(@"tilelight: interop failed to load %s: %@", metallib_path, error);
			return TL_ERR_LIBRARY;
		}
		g_device = device;
		// Our own queue, not MoltenVK's: our command buffer is committed before Vulkan submits the
		// frame and waits on the GPU for it, which would deadlock on a shared in-order queue.
		id<MTLCommandQueue> queue = [device newCommandQueue];
		if (!queue) return TL_ERR_GPU;
		queue.label = @"tilelight interop";
		g_queue = queue;
		g_library = library;
		int32_t rc = tl_shadows_init(device, library);
		if (rc == TL_OK) rc = tl_terrain_init(device, library);
		if (rc == TL_OK) rc = tl_volumetric_init(device, library);
		if (rc == TL_OK) rc = tl_water_init(device, library);
		if (rc != TL_OK) return rc;
		MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
		sd.minFilter = MTLSamplerMinMagFilterLinear;
		sd.magFilter = MTLSamplerMinMagFilterLinear;
		sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
		g_linear_sampler = [device newSamplerStateWithDescriptor:sd];
	}
	return TL_OK;
}

static int32_t ensure_bilinear(MTLPixelFormat format) {
	if (g_bilinear_pso && g_bilinear_format == format) return TL_OK;
	NSError *error = nil;
	MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
	d.label = @"tilelight bilinear upscale";
	d.vertexFunction = [g_library newFunctionWithName:@"tl_fullscreen_vs"];
	d.fragmentFunction = [g_library newFunctionWithName:@"tl_bilinear_fs"];
	if (!d.vertexFunction || !d.fragmentFunction) return TL_ERR_LIBRARY;
	d.colorAttachments[0].pixelFormat = format;
	g_bilinear_pso = [g_device newRenderPipelineStateWithDescriptor:d error:&error];
	if (!g_bilinear_pso) {
		NSLog(@"tilelight: bilinear pipeline: %@", error);
		return TL_ERR_PIPELINE;
	}
	g_bilinear_format = format;
	return TL_OK;
}

static int32_t ensure_scaler(id<MTLTexture> in, id<MTLTexture> out) {
	if (g_scaler && g_scaler_in_w == in.width && g_scaler_in_h == in.height && g_scaler_out_w == out.width &&
	    g_scaler_out_h == out.height && g_scaler_in_fmt == in.pixelFormat && g_scaler_out_fmt == out.pixelFormat) {
		return TL_OK;
	}
	if (![MTLFXSpatialScalerDescriptor supportsDevice:g_device]) return TL_ERR_NO_DEVICE;
	MTLFXSpatialScalerDescriptor *d = [MTLFXSpatialScalerDescriptor new];
	d.inputWidth = in.width;
	d.inputHeight = in.height;
	d.outputWidth = out.width;
	d.outputHeight = out.height;
	d.colorTextureFormat = in.pixelFormat;
	d.outputTextureFormat = out.pixelFormat;
	// Minecraft's main target holds sRGB-encoded 8-bit colour.
	d.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
	id<MTLFXSpatialScaler> scaler = [d newSpatialScalerWithDevice:g_device];
	if (!scaler) return TL_ERR_PIPELINE;

	g_scaler_scratch = nil;
	if ((out.usage & scaler.outputTextureUsage) != scaler.outputTextureUsage) {
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:out.pixelFormat
		                                                                              width:out.width
		                                                                             height:out.height
		                                                                          mipmapped:NO];
		td.usage = scaler.outputTextureUsage;
		td.storageMode = MTLStorageModePrivate;
		g_scaler_scratch = [g_device newTextureWithDescriptor:td];
		if (!g_scaler_scratch) return TL_ERR_BUFFER;
		NSLog(@"tilelight: output texture usage 0x%lx lacks MetalFX's 0x%lx; upscaling via a scratch texture",
		      (unsigned long)out.usage, (unsigned long)scaler.outputTextureUsage);
	}
	g_scaler = scaler;
	g_scaler_in_w = in.width;
	g_scaler_in_h = in.height;
	g_scaler_out_w = out.width;
	g_scaler_out_h = out.height;
	g_scaler_in_fmt = in.pixelFormat;
	g_scaler_out_fmt = out.pixelFormat;
	return TL_OK;
}

int32_t tl_interop_frame(int32_t mode, void *in_tex, void *out_tex, void *shared_event, uint64_t wait_value,
                         uint64_t signal_value) {
	if (!g_device) return TL_ERR_NOT_INITIALIZED;
	@autoreleasepool {
		id<MTLSharedEvent> event = (__bridge id<MTLSharedEvent>)shared_event;
		id<MTLTexture> in = (__bridge id<MTLTexture>)in_tex;
		id<MTLTexture> out = (__bridge id<MTLTexture>)out_tex;

		id<MTLCommandBuffer> cb = tl_interop_new_command_buffer();
		if (!cb) return TL_ERR_NOT_COMMITTED;
		cb.label = @"tilelight frame";
		[cb encodeWaitForEvent:event value:wait_value];

		// Shadows darken the low-res colour before it is upscaled.
		int32_t rc = in ? tl_shadows_encode_apply(cb, in) : TL_OK;
		if (rc == TL_OK && in) rc = tl_water_encode(cb, in);
		if (rc == TL_OK && in) rc = tl_volumetric_encode(cb, in);
		if (rc == TL_OK) switch (mode) {
		case TL_MODE_NOOP:
			break;
		case TL_MODE_BILINEAR: {
			rc = ensure_bilinear(out.pixelFormat);
			if (rc != TL_OK) break;
			MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
			rp.colorAttachments[0].texture = out;
			rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
			rp.colorAttachments[0].storeAction = MTLStoreActionStore;
			id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
			enc.label = @"tilelight bilinear";
			[enc setRenderPipelineState:g_bilinear_pso];
			[enc setFragmentTexture:in atIndex:0];
			[enc setFragmentSamplerState:g_linear_sampler atIndex:0];
			[enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
			[enc endEncoding];
			break;
		}
		case TL_MODE_METALFX: {
			rc = ensure_scaler(in, out);
			if (rc != TL_OK) break;
			g_scaler.colorTexture = in;
			g_scaler.outputTexture = g_scaler_scratch ? g_scaler_scratch : out;
			[g_scaler encodeToCommandBuffer:cb];
			if (g_scaler_scratch) {
				id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
				[blit copyFromTexture:g_scaler_scratch toTexture:out];
				[blit endEncoding];
			}
			break;
		}
		default:
			rc = TL_ERR_BUFFER;
		}

		// Always signal, even on error, so Vulkan's wait can never deadlock.
		[cb encodeSignalEvent:event value:signal_value];
		tl_interop_track(cb);
		[cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
			if (done.status == MTLCommandBufferStatusCompleted) atomic_fetch_add(&g_frames, 1);
		}];
		[cb commit];
		return rc;
	}
}

id<MTLCommandBuffer> tl_interop_new_command_buffer(void) {
	return g_queue ? [g_queue commandBuffer] : nil;
}

// Counts the GPU time of every command buffer we commit (terrain and end-of-frame) per frame.
void tl_interop_track(id<MTLCommandBuffer> cb) {
	[cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
		if (done.status == MTLCommandBufferStatusCompleted) {
			atomic_fetch_add(&g_gpu_ns_total, (int64_t)((done.GPUEndTime - done.GPUStartTime) * 1e9));
		} else {
			atomic_fetch_add(&g_errors, 1);
		}
	}];
}

int32_t tl_interop_take_stats(int64_t *gpu_ns_total, int64_t *frames, int32_t *errors) {
	*gpu_ns_total = atomic_exchange(&g_gpu_ns_total, 0);
	*frames = atomic_exchange(&g_frames, 0);
	*errors = atomic_exchange(&g_errors, 0);
	return TL_OK;
}

void tl_interop_shutdown(void *shared_event) {
	tl_shadows_shutdown();
	tl_terrain_shutdown();
	tl_volumetric_shutdown();
	tl_water_shutdown();
	if (shared_event) {
		((__bridge id<MTLSharedEvent>)shared_event).signaledValue = UINT64_MAX;
	}
	if (g_queue) {
		// Command buffers retain what they use, but let in-flight work finish before the
		// caller destroys the Vulkan objects behind those Metal textures and the event.
		id<MTLCommandBuffer> fence = [g_queue commandBuffer];
		[fence commit];
		[fence waitUntilCompleted];
	}
	g_scaler = nil;
	g_scaler_scratch = nil;
	g_bilinear_pso = nil;
	g_linear_sampler = nil;
	g_library = nil;
	g_queue = nil;
	g_device = nil;
}
