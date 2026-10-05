package dev.tilelight.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.tilelight.Tilelight;
import dev.tilelight.mixin.FrontendCommandEncoderAccessor;
import dev.tilelight.mixin.FrontendGpuDeviceAccessor;
import dev.tilelight.mixin.RenderTargetAccessor;
import dev.tilelight.nativeapi.NativeLib;
import java.util.IdentityHashMap;
import java.util.Map;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMetalObjects;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMetalDeviceInfoEXT;
import org.lwjgl.vulkan.VkExportMetalObjectCreateInfoEXT;
import org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT;
import org.lwjgl.vulkan.VkExportMetalSharedEventInfoEXT;
import org.lwjgl.vulkan.VkExportMetalTextureInfoEXT;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;

/**
 * Gate 1: renders the world at a fraction of the window size and upscales it on Metal.
 *
 * <p>Before the world renders, the main render target's textures are swapped for low-resolution
 * ones. After vanilla post effects, the native textures are swapped back and the frame is handed to
 * Metal inside Minecraft's single per-frame Vulkan submission:
 *
 * <pre>
 *   Vulkan batch 1: world -> low-res colour, signal timeline semaphore = 2n+1
 *   Metal (own queue): wait 2n+1, upscale low-res -> native colour, signal 2n+2
 *   Vulkan batch 2: wait 2n+2, GUI over native colour, present
 * </pre>
 *
 * The timeline semaphore is exported as an MTLSharedEvent, so both waits happen on the GPU.
 *
 * <p>Configured with system properties for now: {@code tilelight.mode} = off | noop | bilinear |
 * metalfx (default metalfx), {@code tilelight.scale} = render scale in (0, 1] (default 0.67).
 */
public final class ScaledFrame {
	private static final long ALL_COMMANDS = 0x10000L; // VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
	private static final int PLANE_0 = 0x10; // VK_IMAGE_ASPECT_PLANE_0_BIT
	private static final int STATS_EVERY_FRAMES = 600;

	private static final ScaledFrame INSTANCE = new ScaledFrame();

	private enum State { UNINITIALIZED, ACTIVE, OFF }

	private final int mode;
	private final float scale;
	private State state = State.UNINITIALIZED;

	private NativeLib lib;
	private VulkanDevice vulkanDevice;
	private long semaphore;
	private long sharedEvent;
	private long frameIndex;
	private final Map<GpuTexture, Long> mtlTextures = new IdentityHashMap<>();

	private GpuTexture lowColor, lowDepth;
	private GpuTextureView lowColorView, lowDepthView;

	// The main target's own textures while the low-res ones are swapped in.
	private boolean swapped;
	private int nativeWidth, nativeHeight;
	private GpuTexture nativeColor, nativeDepth;
	private GpuTextureView nativeColorView, nativeDepthView;

	private long statFrames;

	private ScaledFrame() {
		String m = System.getProperty("tilelight.mode", "metalfx");
		this.mode = switch (m) {
			case "off" -> -1;
			case "noop" -> NativeLib.MODE_NOOP;
			case "bilinear" -> NativeLib.MODE_BILINEAR;
			case "metalfx" -> NativeLib.MODE_METALFX;
			default -> throw new IllegalArgumentException("tilelight.mode must be off, noop, bilinear or metalfx, not " + m);
		};
		float s = Float.parseFloat(System.getProperty("tilelight.scale", "0.67"));
		if (!(s > 0f && s <= 1f)) {
			throw new IllegalArgumentException("tilelight.scale must be in (0, 1], not " + s);
		}
		// noop measures only the hand-off, so it renders at full size: nothing would upscale.
		this.scale = this.mode == NativeLib.MODE_NOOP ? 1f : s;
	}

	public static ScaledFrame get() {
		return INSTANCE;
	}

	public void beforeLevel(RenderTarget main, Vector4fc clearColor) {
		if (!ensureActive()) return;
		int w = Math.max(1, Math.round(main.width * this.scale));
		int h = Math.max(1, Math.round(main.height * this.scale));
		ensureLowTextures(main, w, h);

		this.nativeWidth = main.width;
		this.nativeHeight = main.height;
		this.nativeColor = main.getColorTexture();
		this.nativeColorView = main.getColorTextureView();
		this.nativeDepth = main.getDepthTexture();
		this.nativeDepthView = main.getDepthTextureView();
		swapInto(main, w, h, this.lowColor, this.lowColorView, this.lowDepth, this.lowDepthView);
		this.swapped = true;

		// GameRenderer cleared the native target; do the same for ours (reverse-Z, so depth clears to 0).
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(this.lowColor, clearColor, this.lowDepth, 0.0);
	}

	public void afterLevel(RenderTarget main) {
		if (!this.swapped) return;
		this.swapped = false;
		swapInto(main, this.nativeWidth, this.nativeHeight, this.nativeColor, this.nativeColorView, this.nativeDepth,
				this.nativeDepthView);

		long n = this.frameIndex++;
		long worldDone = 2 * n + 1;
		long metalDone = 2 * n + 2;
		VulkanCommandEncoder encoder = vulkanEncoder();
		encoder.signalSemaphore(this.semaphore, worldDone, ALL_COMMANDS);
		int rc = this.lib.interopFrame(this.mode, mtlTexture(this.lowColor), mtlTexture(this.nativeColor), this.sharedEvent,
				worldDone, metalDone);
		// The Metal command buffer signals metalDone even when it fails, so this wait cannot hang.
		encoder.waitSemaphore(this.semaphore, metalDone, ALL_COMMANDS);
		if (rc != NativeLib.OK) {
			Tilelight.LOG.error("Metal upscale failed with code {}; turning Tilelight off", rc);
			this.state = State.OFF;
		}

		if (++this.statFrames >= STATS_EVERY_FRAMES) {
			this.statFrames = 0;
			NativeLib.InteropStats stats = this.lib.interopTakeStats();
			if (stats.frames() > 0) {
				Tilelight.LOG.info("interop: mode {} {}x{} -> {}x{}, Metal GPU {} us/frame over {} frames, {} errors",
						this.mode, this.lowColor.getWidth(0), this.lowColor.getHeight(0), this.nativeWidth, this.nativeHeight,
						stats.gpuNanos() / stats.frames() / 1000, stats.frames(), stats.errors());
			}
		}
	}

	private static void swapInto(RenderTarget target, int width, int height, GpuTexture color, GpuTextureView colorView,
			GpuTexture depth, GpuTextureView depthView) {
		RenderTargetAccessor acc = (RenderTargetAccessor) target;
		target.width = width;
		target.height = height;
		acc.tilelight$setColorTexture(color);
		acc.tilelight$setColorTextureView(colorView);
		acc.tilelight$setDepthTexture(depth);
		acc.tilelight$setDepthTextureView(depthView);
	}

	private void ensureLowTextures(RenderTarget main, int w, int h) {
		if (this.lowColor != null && this.lowColor.getWidth(0) == w && this.lowColor.getHeight(0) == h
				&& this.lowColor.getFormat() == main.getColorTexture().getFormat()
				&& this.lowDepth.getFormat() == main.getDepthTexture().getFormat()) {
			return;
		}
		closeLowTextures();
		GpuDevice device = RenderSystem.getDevice();
		// Same usage as RenderTarget.createBuffers: copy src/dst, sampled, render attachment.
		int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING
				| GpuTexture.USAGE_RENDER_ATTACHMENT;
		this.lowColor = device.createTexture(() -> "Tilelight / Scaled Color", usage, main.getColorTexture().getFormat(), w, h, 1, 1);
		this.lowColorView = device.createTextureView(this.lowColor);
		this.lowDepth = device.createTexture(() -> "Tilelight / Scaled Depth", usage, main.getDepthTexture().getFormat(), w, h, 1, 1);
		this.lowDepthView = device.createTextureView(this.lowDepth);
		Tilelight.LOG.info("Rendering the world at {}x{} for a {}x{} window", w, h, main.width, main.height);
	}

	private void closeLowTextures() {
		if (this.lowColor == null) return;
		this.mtlTextures.remove(this.lowColor);
		this.lowColorView.close();
		this.lowColor.close();
		this.lowDepthView.close();
		this.lowDepth.close();
		this.lowColor = this.lowDepth = null;
		this.lowColorView = this.lowDepthView = null;
	}

	private boolean ensureActive() {
		if (this.state == State.ACTIVE) return true;
		if (this.state == State.OFF) return false;
		this.state = State.OFF;
		if (this.mode < 0) {
			Tilelight.LOG.info("tilelight.mode=off; leaving rendering to vanilla");
			return false;
		}
		this.lib = Tilelight.nativeLib();
		if (this.lib == null) return false;
		if (!(RenderSystem.getDevice() instanceof FrontendGpuDeviceAccessor frontend)
				|| !(frontend.tilelight$backend() instanceof VulkanDevice vk)) {
			Tilelight.LOG.warn("Not on the Vulkan backend; Tilelight stays off. Set Graphics API to Vulkan.");
			return false;
		}
		this.vulkanDevice = vk;
		VkDevice device = vk.vkDevice();
		if (!device.getCapabilities().VK_EXT_metal_objects) {
			Tilelight.LOG.warn("VK_EXT_metal_objects is not enabled on Minecraft's device; Tilelight stays off");
			return false;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkExportMetalDeviceInfoEXT deviceInfo = VkExportMetalDeviceInfoEXT.calloc(stack).sType$Default();
			export(device, stack, deviceInfo.address());
			int rc = this.lib.interopInit(deviceInfo.mtlDevice());
			if (rc != NativeLib.OK) {
				Tilelight.LOG.error("Metal interop init failed with code {}", rc);
				return false;
			}

			VkExportMetalObjectCreateInfoEXT exportable = VkExportMetalObjectCreateInfoEXT.calloc(stack).sType$Default()
					.exportObjectType(EXTMetalObjects.VK_EXPORT_METAL_OBJECT_TYPE_METAL_SHARED_EVENT_BIT_EXT);
			VkSemaphoreTypeCreateInfo timeline = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
					.semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
			timeline.pNext(exportable.address());
			VkSemaphoreCreateInfo create = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
			create.pNext(timeline.address());
			long[] handle = new long[1];
			int vr = VK10.vkCreateSemaphore(device, create, null, handle);
			if (vr != VK10.VK_SUCCESS) {
				Tilelight.LOG.error("vkCreateSemaphore failed: {}", vr);
				return false;
			}
			this.semaphore = handle[0];

			VkExportMetalSharedEventInfoEXT eventInfo = VkExportMetalSharedEventInfoEXT.calloc(stack).sType$Default()
					.semaphore(this.semaphore);
			export(device, stack, eventInfo.address());
			this.sharedEvent = eventInfo.mtlSharedEvent();
			if (this.sharedEvent == 0L) {
				Tilelight.LOG.error("MoltenVK did not export an MTLSharedEvent for the timeline semaphore");
				return false;
			}
		}
		Tilelight.LOG.info("Metal interop active: mode {}, render scale {}", this.mode, this.scale);
		this.state = State.ACTIVE;
		return true;
	}

	private long mtlTexture(GpuTexture texture) {
		return this.mtlTextures.computeIfAbsent(texture, t -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkExportMetalTextureInfoEXT info = VkExportMetalTextureInfoEXT.calloc(stack).sType$Default()
						.image(((VulkanGpuTexture) t).vkImage()).plane(PLANE_0);
				export(this.vulkanDevice.vkDevice(), stack, info.address());
				if (info.mtlTexture() == 0L) {
					throw new IllegalStateException("MoltenVK did not export an MTLTexture for " + t.getLabel());
				}
				// Textures are recreated on resize; drop pointers to closed ones so the map stays small.
				this.mtlTextures.keySet().removeIf(GpuTexture::isClosed);
				return info.mtlTexture();
			}
		});
	}

	private static void export(VkDevice device, MemoryStack stack, long chain) {
		VkExportMetalObjectsInfoEXT objects = VkExportMetalObjectsInfoEXT.calloc(stack).sType$Default();
		objects.pNext(chain);
		EXTMetalObjects.vkExportMetalObjectsEXT(device, objects);
	}

	private static VulkanCommandEncoder vulkanEncoder() {
		CommandEncoder frontend = RenderSystem.getDevice().createCommandEncoder();
		return (VulkanCommandEncoder) ((FrontendCommandEncoderAccessor) frontend).tilelight$backend();
	}
}
