package dev.tilelight.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
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
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
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
 * <p>Before the world renders, every window-sized target the world pass touches (main, 3D HUD
 * depth, entity outline) has its textures swapped for low-resolution ones; Minecraft's frame-graph
 * targets follow the main target's size on their own. After vanilla post effects the native
 * textures are swapped back and the frame is handed to Metal inside Minecraft's single per-frame
 * Vulkan submission:
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
	// Same usage as RenderTarget.createBuffers: copy src/dst, sampled, render attachment.
	private static final int TARGET_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
			| GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

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

	/** One per window-sized target; the main target is first. Empty until the first frame. */
	private List<Substitute> substitutes = List.of();
	private boolean swapped;

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
		this.scale = s;
	}

	public static ScaledFrame get() {
		return INSTANCE;
	}

	/**
	 * Called at the start of every GameRenderer.render() and before resize/close. If the previous
	 * frame threw between beforeLevel and afterLevel, this puts the native textures back.
	 */
	public void restoreIfSwapped() {
		if (!this.swapped) return;
		Tilelight.LOG.warn("World rendering ended without afterLevel; restoring native targets");
		restoreAll();
	}

	/** Swaps low-resolution textures into main, 3D HUD and entity outline targets. */
	public void beforeLevel(RenderTarget main, RenderTarget hud3d, RenderTarget entityOutline, Vector4fc clearColor) {
		restoreIfSwapped();
		if (!ensureActive()) return;
		// noop measures only the hand-off: render at native size and upscale nothing.
		if (this.mode == NativeLib.MODE_NOOP) return;

		int w = Math.max(1, Math.round(main.width * this.scale));
		int h = Math.max(1, Math.round(main.height * this.scale));
		if (this.substitutes.isEmpty()) {
			this.substitutes = List.of(new Substitute(main, "Main"), new Substitute(hud3d, "HUD 3D"),
					new Substitute(entityOutline, "Entity Outline"));
			Tilelight.LOG.info("Rendering the world at {}x{} for a {}x{} window", w, h, main.width, main.height);
		}
		for (Substitute s : this.substitutes) {
			s.swapIn(w, h);
		}
		this.swapped = true;

		// GameRenderer cleared the native main target; do the same for ours (reverse-Z: depth clears to 0).
		Substitute m = this.substitutes.getFirst();
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(m.lowColor, clearColor, m.lowDepth, 0.0);
	}

	public void afterLevel() {
		if (this.state != State.ACTIVE) return;
		GpuTexture in;
		GpuTexture out;
		if (this.mode == NativeLib.MODE_NOOP) {
			in = out = null;
		} else {
			if (!this.swapped) return;
			Substitute m = this.substitutes.getFirst();
			in = m.lowColor;
			out = m.nativeColor;
			restoreAll();
		}

		long n = this.frameIndex++;
		long worldDone = 2 * n + 1;
		long metalDone = 2 * n + 2;
		VulkanCommandEncoder encoder = vulkanEncoder();
		encoder.signalSemaphore(this.semaphore, worldDone, ALL_COMMANDS);
		int rc = this.lib.interopFrame(this.mode, in == null ? 0L : mtlTexture(in), out == null ? 0L : mtlTexture(out),
				this.sharedEvent, worldDone, metalDone);
		if (rc != NativeLib.ERR_NOT_COMMITTED) {
			// A committed Metal command buffer signals metalDone even if encoding failed.
			encoder.waitSemaphore(this.semaphore, metalDone, ALL_COMMANDS);
		}
		if (rc != NativeLib.OK) {
			Tilelight.LOG.error("Metal upscale failed with code {}; turning Tilelight off", rc);
			this.state = State.OFF;
		}

		if (++this.statFrames >= STATS_EVERY_FRAMES) {
			this.statFrames = 0;
			NativeLib.InteropStats stats = this.lib.interopTakeStats();
			if (stats.frames() > 0) {
				Tilelight.LOG.info("interop: mode {} scale {}, Metal GPU {} us/frame over {} frames, {} errors",
						this.mode, this.scale, stats.gpuNanos() / stats.frames() / 1000, stats.frames(), stats.errors());
			}
		}
	}

	/** Releases everything we created on Minecraft's device. Called before GameRenderer closes. */
	public void shutdown() {
		restoreIfSwapped();
		if (this.vulkanDevice != null) {
			// Our textures and semaphore may still be in use by in-flight frames.
			this.vulkanDevice.graphicsQueue().waitIdle();
		}
		if (this.lib != null) {
			this.lib.interopShutdown(); // waits for our own Metal queue to drain
		}
		for (Substitute s : this.substitutes) {
			s.closeLow();
		}
		this.substitutes = List.of();
		this.mtlTextures.clear();
		if (this.semaphore != 0L) {
			VK10.vkDestroySemaphore(this.vulkanDevice.vkDevice(), this.semaphore, null);
			this.semaphore = 0L;
		}
		this.state = State.OFF;
	}

	private void restoreAll() {
		for (Substitute s : this.substitutes) {
			s.restore();
		}
		this.swapped = false;
	}

	/** A window-sized render target and the low-resolution textures swapped into it during the world pass. */
	private final class Substitute {
		private final RenderTarget target;
		private final String label;
		private @Nullable GpuTexture lowColor, lowDepth;
		private @Nullable GpuTextureView lowColorView, lowDepthView;
		private int nativeWidth, nativeHeight;
		private @Nullable GpuTexture nativeColor, nativeDepth;
		private @Nullable GpuTextureView nativeColorView, nativeDepthView;

		Substitute(RenderTarget target, String label) {
			this.target = target;
			this.label = label;
		}

		void swapIn(int w, int h) {
			ensureLow(w, h);
			this.nativeWidth = this.target.width;
			this.nativeHeight = this.target.height;
			this.nativeColor = this.target.getColorTexture();
			this.nativeColorView = this.target.getColorTextureView();
			this.nativeDepth = this.target.getDepthTexture();
			this.nativeDepthView = this.target.getDepthTextureView();
			set(w, h, this.lowColor, this.lowColorView, this.lowDepth, this.lowDepthView);
		}

		void restore() {
			set(this.nativeWidth, this.nativeHeight, this.nativeColor, this.nativeColorView, this.nativeDepth, this.nativeDepthView);
		}

		private void set(int w, int h, @Nullable GpuTexture color, @Nullable GpuTextureView colorView,
				@Nullable GpuTexture depth, @Nullable GpuTextureView depthView) {
			RenderTargetAccessor acc = (RenderTargetAccessor) this.target;
			this.target.width = w;
			this.target.height = h;
			acc.tilelight$setColorTexture(color);
			acc.tilelight$setColorTextureView(colorView);
			acc.tilelight$setDepthTexture(depth);
			acc.tilelight$setDepthTextureView(depthView);
		}

		private void ensureLow(int w, int h) {
			GpuTexture color = this.target.getColorTexture();
			GpuTexture depth = this.target.getDepthTexture();
			if (matches(this.lowColor, color, w, h) && matches(this.lowDepth, depth, w, h)) return;
			closeLow();
			GpuDevice device = RenderSystem.getDevice();
			if (color != null) {
				GpuFormat format = color.getFormat();
				this.lowColor = device.createTexture(() -> "Tilelight / " + this.label + " / Color", TARGET_USAGE, format, w, h, 1, 1);
				this.lowColorView = device.createTextureView(this.lowColor);
			}
			if (depth != null) {
				GpuFormat format = depth.getFormat();
				this.lowDepth = device.createTexture(() -> "Tilelight / " + this.label + " / Depth", TARGET_USAGE, format, w, h, 1, 1);
				this.lowDepthView = device.createTextureView(this.lowDepth);
			}
		}

		/** True if low is a w x h stand-in for the target's texture (or both are absent). */
		private static boolean matches(@Nullable GpuTexture low, @Nullable GpuTexture target, int w, int h) {
			if (target == null) return low == null;
			return low != null && low.getWidth(0) == w && low.getHeight(0) == h && low.getFormat() == target.getFormat();
		}

		void closeLow() {
			if (this.lowColor != null) {
				ScaledFrame.this.mtlTextures.remove(this.lowColor);
				this.lowColorView.close();
				this.lowColor.close();
			}
			if (this.lowDepth != null) {
				this.lowDepthView.close();
				this.lowDepth.close();
			}
			this.lowColor = this.lowDepth = null;
			this.lowColorView = this.lowDepthView = null;
		}
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
		VkDevice device = vk.vkDevice();
		if (!device.getCapabilities().VK_EXT_metal_objects) {
			Tilelight.LOG.warn("VK_EXT_metal_objects is not enabled on Minecraft's device; Tilelight stays off");
			return false;
		}
		this.vulkanDevice = vk;
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
				this.lib.interopShutdown();
				return false;
			}
			this.semaphore = handle[0];

			VkExportMetalSharedEventInfoEXT eventInfo = VkExportMetalSharedEventInfoEXT.calloc(stack).sType$Default()
					.semaphore(this.semaphore);
			export(device, stack, eventInfo.address());
			this.sharedEvent = eventInfo.mtlSharedEvent();
			if (this.sharedEvent == 0L) {
				Tilelight.LOG.error("MoltenVK did not export an MTLSharedEvent for the timeline semaphore");
				VK10.vkDestroySemaphore(device, this.semaphore, null);
				this.semaphore = 0L;
				this.lib.interopShutdown();
				return false;
			}
		}
		Tilelight.LOG.info("Metal interop active: mode {}, render scale {}", this.mode, this.scale);
		this.state = State.ACTIVE;
		return true;
	}

	private long mtlTexture(GpuTexture texture) {
		Long cached = this.mtlTextures.get(texture);
		if (cached != null) return cached;
		// Targets are recreated on resize; drop pointers to closed textures so the map stays small.
		this.mtlTextures.keySet().removeIf(GpuTexture::isClosed);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkExportMetalTextureInfoEXT info = VkExportMetalTextureInfoEXT.calloc(stack).sType$Default()
					.image(((VulkanGpuTexture) texture).vkImage()).plane(PLANE_0);
			export(this.vulkanDevice.vkDevice(), stack, info.address());
			if (info.mtlTexture() == 0L) {
				throw new IllegalStateException("MoltenVK did not export an MTLTexture for " + texture.getLabel());
			}
			this.mtlTextures.put(texture, info.mtlTexture());
			return info.mtlTexture();
		}
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
