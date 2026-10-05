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
import dev.tilelight.Tilelight;
import dev.tilelight.TilelightConfig;
import dev.tilelight.mixin.FrontendCommandEncoderAccessor;
import dev.tilelight.mixin.FrontendGpuDeviceAccessor;
import dev.tilelight.mixin.RenderTargetAccessor;
import dev.tilelight.mixin.VulkanCommandEncoderAccessor;
import dev.tilelight.mixin.VulkanTransientMemoryAccessor;
import dev.tilelight.nativeapi.NativeLib;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.joml.Vector4fc;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.EXTMetalObjects;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMetalObjectCreateInfoEXT;
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
 * <p>Configured by {@code upscaler} and {@code renderScale} in {@link TilelightConfig}.
 */
public final class ScaledFrame {
	private static final long ALL_COMMANDS = 0x10000L; // VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
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
	private MetalExports exports;
	private final ShadowPass shadows = new ShadowPass();
	private final TerrainPass terrain = new TerrainPass();
	private final VolumetricPass volumetric = new VolumetricPass();
	private final WaterPass water = new WaterPass();
	private final TemporalPass temporal = new TemporalPass();
	private long semaphore;
	private long sharedEvent;
	// Last value used on the timeline semaphore; every hand-off takes the next two.
	private long timeline;
	// Metal drew this frame's opaque terrain, so vanilla must skip its own.
	private boolean terrainDrawn;

	/** One per window-sized target; the main target is first. Empty until the first frame. */
	private List<Substitute> substitutes = List.of();
	private boolean swapped;

	private long statFrames;
	// Our Metal work goes on MoltenVK's queue: Vulkan work is flushed at each hand-off.
	private boolean sameQueue;
	private boolean trackingLogged;
	// Render-thread CPU time spent in our code, and the time between frames, summed since the last stats line.
	private long cpuShadowsNs, cpuGatherNs, cpuTerrainNativeNs, cpuEndOfFrameNs, framePeriodNs;
	private long lastBeforeLevelNs;

	// Copy of the world depth taken before vanilla clears the main depth to draw the hand.
	private GpuTexture worldDepth;
	// Whether the hand was drawn into the 3D HUD target's depth instead of the main depth.
	private boolean handInHudDepth;
	// Set once the world depth was copied this frame (always-on-top features may clear it before the hand).
	private boolean worldDepthCopied;
	private boolean shadowsWereOn;

	private ScaledFrame() {
		TilelightConfig config = TilelightConfig.get();
		this.mode = switch (config.upscaler) {
			case "off" -> -1;
			case "noop" -> NativeLib.MODE_NOOP;
			case "bilinear" -> NativeLib.MODE_BILINEAR;
			case "metalfx" -> NativeLib.MODE_METALFX;
			case "temporal" -> NativeLib.MODE_TEMPORAL;
			default -> NativeLib.MODE_TAA;
		};
		float s = config.renderScale;
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
		long now = System.nanoTime();
		if (this.lastBeforeLevelNs != 0) this.framePeriodNs += now - this.lastBeforeLevelNs;
		this.lastBeforeLevelNs = now;
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
		this.worldDepthCopied = false;
		this.terrainDrawn = false;

		// GameRenderer cleared the native main target; do the same for ours (reverse-Z: depth clears to 0).
		Substitute m = this.substitutes.getFirst();
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(m.lowColor, clearColor, m.lowDepth, 0.0);
	}

	/**
	 * Called in renderLevel just before the 3D HUD (hand) pass, which clears the main depth when no
	 * post effect is active. Keeps the world depth for the Metal passes.
	 */
	public void beforeHud(RenderTarget main, boolean handInHudDepth) {
		this.handInHudDepth = handInHudDepth;
		copyWorldDepth(main);
	}

	/** Called before always-on-top features (debug gizmos), which clear the main depth when drawn. */
	public void beforeAlwaysOnTop(RenderTarget main) {
		copyWorldDepth(main);
	}

	private void copyWorldDepth(RenderTarget main) {
		if (!this.swapped || this.worldDepthCopied) return;
		// Needed by the Gate 2 shadow overlay (vanilla terrain), or by volumetrics and water reflections.
		boolean needed = jittered()
				|| (this.terrainDrawn ? this.volumetric.enabled() || this.water.enabled() : this.shadows.enabled());
		if (!needed) return;
		this.worldDepthCopied = true;
		GpuTexture depth = main.getDepthTexture();
		int w = depth.getWidth(0);
		int h = depth.getHeight(0);
		if (this.worldDepth == null || this.worldDepth.getWidth(0) != w || this.worldDepth.getHeight(0) != h
				|| this.worldDepth.getFormat() != depth.getFormat()) {
			if (this.worldDepth != null) this.worldDepth.close();
			this.worldDepth = RenderSystem.getDevice().createTexture(() -> "Tilelight / World Depth",
					GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, depth.getFormat(), w, h, 1, 1);
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(depth, this.worldDepth, 0, 0, 0, 0, 0, w, h);
	}

	public void afterLevel(LevelRenderState level) {
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

		long t0 = System.nanoTime();
		boolean shadowsOn = in != null && this.shadows.appliesTo(level) && (this.terrainDrawn || this.worldDepthCopied);
		if (shadowsOn != this.shadowsWereOn) {
			this.shadowsWereOn = shadowsOn;
			Tilelight.LOG.info("Sun shadows {} (enabled {}, sky {}, world depth copied {})", shadowsOn ? "on" : "off",
					this.shadows.enabled(), level.skyRenderState.skybox, this.worldDepthCopied);
		}
		if (shadowsOn) {
			// The hand's depth target holds only the hand (it is cleared first); it masks the hand out.
			if (!this.terrainDrawn) { // Gate 2 overlay; with Gate 3 terrain the shadows were queued mid-frame
				Substitute handTarget = this.substitutes.get(this.handInHudDepth ? 1 : 0);
				this.shadows.submit(this.lib, this.exports, level, true, this.exports.texture(this.worldDepth),
						this.exports.texture(handTarget.lowDepth));
			}
			if (this.volumetric.enabled() && this.worldDepthCopied) {
				this.volumetric.submit(this.lib, level, ShadowPass.levelProjection, this.shadows.sample(),
						this.exports.texture(this.worldDepth));
			}
		}

		if (in != null && this.terrainDrawn && this.water.enabled() && this.worldDepthCopied) {
			this.water.submit(this.lib, level, ShadowPass.levelProjection, this.exports.texture(this.worldDepth));
		}

		if (jittered()) {
			if (!this.worldDepthCopied) {
				Tilelight.LOG.error("Temporal anti-aliasing has no world depth this frame; turning Tilelight off");
				this.state = State.OFF;
				return;
			}
			Substitute handTarget = this.substitutes.get(this.handInHudDepth ? 1 : 0);
			this.temporal.submit(this.lib, level, ShadowPass.levelProjection, in.getWidth(0), in.getHeight(0),
					this.exports.texture(this.worldDepth), this.exports.texture(handTarget.lowDepth));
		}

		VulkanCommandEncoder encoder = vulkanEncoder();
		// The GUI can sample the native colour (menu background blur) before drawing into it.
		long[] values = beginHandoff(encoder, false);
		int rc = this.lib.interopFrame(this.mode, in == null ? 0L : this.exports.texture(in), out == null ? 0L : this.exports.texture(out),
				this.sharedEvent, values[0], values[1]);
		endHandoff(encoder, rc, values[1]);
		if (rc != NativeLib.OK) {
			Tilelight.LOG.error("Metal upscale failed with code {}; turning Tilelight off", rc);
			this.state = State.OFF;
		}

		this.cpuEndOfFrameNs += System.nanoTime() - t0;

		if (++this.statFrames >= STATS_EVERY_FRAMES) {
			long n = this.statFrames;
			this.statFrames = 0;
			NativeLib.InteropStats stats = this.lib.interopTakeStats();
			if (stats.frames() > 0) {
				Tilelight.LOG.info("interop: mode {} scale {}, Metal GPU {} us/frame over {} frames, {} errors",
						this.mode, this.scale, stats.gpuNanos() / stats.frames() / 1000, stats.frames(), stats.errors());
			}
			if (this.sameQueue && !this.trackingLogged) {
				this.trackingLogged = true;
				String report = this.lib.interopTrackingReport();
				Tilelight.LOG.info("Metal hazard tracking of shared resources: {}", report);
				if (report.contains("UNTRACKED")) {
					Tilelight.LOG.warn("Some shared resources are untracked; Vulkan -> Metal ordering may race");
				}
			}
			Tilelight.LOG.info("cpu us/frame: frame period {}, shadows {}, terrain gather {}, terrain native {}, end of frame {}",
					this.framePeriodNs / n / 1000, this.cpuShadowsNs / n / 1000, this.cpuGatherNs / n / 1000,
					this.cpuTerrainNativeNs / n / 1000, this.cpuEndOfFrameNs / n / 1000);
			this.framePeriodNs = this.cpuShadowsNs = this.cpuGatherNs = this.cpuTerrainNativeNs = this.cpuEndOfFrameNs = 0;
		}
	}

	/**
	 * Sees the world pass's projection: jitters it for temporal upscaling and keeps it for our passes.
	 * Returns the projection vanilla should render with.
	 */
	public org.joml.Matrix4f captureProjection(org.joml.Matrix4f projection, RenderTarget main) {
		org.joml.Matrix4f used = projection;
		if (this.swapped && this.state == State.ACTIVE && jittered()) {
			used = this.temporal.jitter(projection, main.width, main.height);
		}
		ShadowPass.levelProjection.set(used);
		return used;
	}

	/** Temporal anti-aliasing modes: the world is jittered and needs depth and motion every frame. */
	private boolean jittered() {
		return this.mode == NativeLib.MODE_TEMPORAL || this.mode == NativeLib.MODE_TAA;
	}

	/** True once Metal has drawn this frame's opaque terrain; vanilla's terrain group is then skipped. */
	public boolean terrainDrawn() {
		return this.terrainDrawn && this.swapped && this.state == State.ACTIVE;
	}

	/** Called at the start of every GameRenderer.renderLevel, including panorama captures. */
	public void beginLevelRender() {
		this.terrainDrawn = false;
	}

	/**
	 * Called in the world pass just before vanilla opens its main render pass (after the sky). Hands
	 * the frame to Metal, which draws and lights the opaque terrain into the low-res targets.
	 */
	public void terrainHandoff(LevelRenderState level) {
		if (!this.swapped || this.state != State.ACTIVE || !this.terrain.enabled()) return;
		// The lighting model is the Overworld's (sun, sky); elsewhere vanilla draws terrain.
		if (level.skyRenderState.skybox != net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD) return;
		Substitute main = this.substitutes.getFirst();
		boolean shadowsOn = this.shadows.appliesTo(level);
		long t0 = System.nanoTime();
		if (shadowsOn) {
			this.shadows.submit(this.lib, this.exports, level, false, 0L, 0L);
		}
		long t1 = System.nanoTime();
		this.terrain.prepare(this.exports, level, ShadowPass.levelProjection, shadowsOn ? this.shadows.sample() : null,
				level.skyRenderState.sunAngle);
		long t2 = System.nanoTime();
		this.cpuShadowsNs += t1 - t0;
		this.cpuGatherNs += t2 - t1;
		if (!this.terrain.complete()) {
			// Some section could not be handed to Metal; vanilla draws all terrain rather than leave holes.
			return;
		}
		long atlas = this.exports.texture(net.minecraft.client.Minecraft.getInstance().getTextureManager()
				.getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getTexture());

		VulkanCommandEncoder encoder = vulkanEncoder();
		// Called at prepareTranslucents RETURN: LevelRenderer opens its "Main"/"Solid" render pass on
		// the main colour and depth right after, before anything samples them.
		long[] values = beginHandoff(encoder, true);
		int rc = this.lib.interopTerrain(this.terrain.params(), this.terrain.draws(), this.terrain.drawCount(),
				this.exports.texture(main.lowColor), this.exports.texture(main.lowDepth), atlas, this.sharedEvent, values[0],
				values[1]);
		endHandoff(encoder, rc, values[1]);
		this.cpuTerrainNativeNs += System.nanoTime() - t2;
		if (rc == NativeLib.OK) {
			this.terrainDrawn = true;
		} else {
			Tilelight.LOG.error("Metal terrain failed with code {}; vanilla draws terrain this frame", rc);
			this.shadows.invalidate();
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
			// Releases any Metal work still waiting on a Vulkan signal that was never submitted,
			// then waits for our own Metal queue to drain.
			this.lib.interopShutdown(this.sharedEvent);
		}
		for (Substitute s : this.substitutes) {
			s.closeLow();
		}
		this.substitutes = List.of();
		if (this.worldDepth != null) {
			this.worldDepth.close();
			this.worldDepth = null;
		}
		if (this.exports != null) this.exports.clear();
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
			Tilelight.LOG.info("Upscaler is off; leaving rendering to vanilla");
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
		this.exports = new MetalExports(vk);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int rc = this.lib.interopInit(this.exports.device());
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
				this.lib.interopShutdown(0L);
				return false;
			}
			this.semaphore = handle[0];

			this.sharedEvent = this.exports.sharedEvent(this.semaphore);
			if (this.sharedEvent == 0L) {
				Tilelight.LOG.error("MoltenVK did not export an MTLSharedEvent for the timeline semaphore");
				VK10.vkDestroySemaphore(device, this.semaphore, null);
				this.semaphore = 0L;
				this.lib.interopShutdown(0L);
				return false;
			}
		}
		// The shared queue relies on MoltenVK committing inside vkQueueSubmit (its default). The only other
		// way to turn that off, VK_EXT_layer_settings at instance creation, is not used by Minecraft 26.3.
		String sync = System.getenv("MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS");
		boolean syncSubmits = sync == null || !(sync.equals("0") || sync.equalsIgnoreCase("false"));
		// A mid-frame flush can only split uploads that are written in place (unified memory); staged
		// uploads copy whole blocks once, so later writes to a block would miss their copy.
		boolean uploadsInPlace = ((VulkanTransientMemoryAccessor) vulkanEncoder().transientMemory()).tilelight$uploadsInPlace();
		if (TilelightConfig.get().sameQueue && !(syncSubmits && uploadsInPlace)) {
			Tilelight.LOG.warn("Using a separate Metal queue (synchronous submits {}, in-place uploads {})", syncSubmits,
					uploadsInPlace);
		}
		if (TilelightConfig.get().sameQueue && syncSubmits && uploadsInPlace) {
			long queue = this.exports.commandQueue();
			if (queue != 0L && this.lib.interopUseQueue(queue) == NativeLib.OK) {
				this.sameQueue = true;
			} else {
				Tilelight.LOG.warn("Could not get MoltenVK's command queue; using a separate Metal queue");
			}
		}
		this.lib.interopSetBloom(0.78f, 0.2f, TilelightConfig.get().bloom);
		Tilelight.LOG.info("Metal interop active: mode {}, render scale {}, {} queue", this.mode, this.scale,
				this.sameQueue ? "shared" : "separate");
		this.state = State.ACTIVE;
		return true;
	}

	/**
	 * Gets Vulkan's work so far ordered before the Metal command buffer about to be committed, and
	 * returns the {wait, signal} event values for it (0 = none).
	 *
	 * <p>Separate queue: Vulkan signals a timeline value that Metal waits for, and Metal signals one
	 * that Vulkan waits for, both on the GPU.
	 *
	 * <p>Shared queue: the Vulkan work recorded so far is submitted now, so MoltenVK commits it to the
	 * queue before our command buffer. Vulkan -> Metal then needs no event: our encoders bind every
	 * resource directly, so Metal's hazard tracking orders our reads after Vulkan's attachment and copy
	 * writes. Metal -> Vulkan keeps an event: MoltenVK reads through argument buffers made resident with
	 * residency sets, which Metal does not track, unless the next Vulkan work is a render pass with our
	 * outputs as attachments. Minecraft's end-of-frame submit and pacing are untouched.
	 */
	private long[] beginHandoff(VulkanCommandEncoder encoder, boolean attachmentPassNext) {
		long signal = ++this.timeline;
		if (!this.sameQueue) {
			long wait = signal;
			signal = ++this.timeline;
			encoder.signalSemaphore(this.semaphore, wait, ALL_COMMANDS);
			return new long[] {wait, signal};
		}
		flush(encoder);
		// If Vulkan's next use of our outputs is as render pass attachments (tracked), Metal orders that
		// pass after our writes and everything else follows it in MoltenVK's own order: no event.
		return new long[] {0L, attachmentPassNext ? 0L : signal};
	}

	/**
	 * Submits everything recorded so far without disturbing Minecraft's per-frame bookkeeping. The
	 * frame's upload command buffer was put in the submission when it began and is still recording,
	 * so it is ended here and a fresh one continues the frame in the next submission; its allocators
	 * are not rotated (that happens at the real end of frame).
	 */
	private static void flush(VulkanCommandEncoder encoder) {
		VulkanCommandEncoderAccessor acc = (VulkanCommandEncoderAccessor) encoder;
		VulkanTransientMemoryAccessor uploads = (VulkanTransientMemoryAccessor) encoder.transientMemory();
		acc.tilelight$endCommandBuffer();
		VkCommandBuffer uploadBuffer = uploads.tilelight$commandBuffer();
		if (uploadBuffer != null) {
			if (uploads.tilelight$anyCommandRecorded()) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					VulkanCommandEncoder.memoryBarrier(uploadBuffer, stack);
				}
			}
			VK10.vkEndCommandBuffer(uploadBuffer);
		}
		acc.tilelight$submissionBuilder().close();
		acc.tilelight$setSubmissionBuilder(acc.tilelight$device().graphicsQueue().beginSubmit());
		if (uploadBuffer != null) {
			VkCommandBuffer next = encoder.allocateAndBeginTransientCommandBuffer();
			encoder.execute(next);
			uploads.tilelight$setCommandBuffer(next);
			uploads.tilelight$setAnyCommandRecorded(false);
		}
	}

	private void endHandoff(VulkanCommandEncoder encoder, int rc, long signal) {
		// A committed Metal command buffer signals even if encoding failed, so this wait cannot hang.
		if (signal != 0L && rc != NativeLib.ERR_NOT_COMMITTED) {
			encoder.waitSemaphore(this.semaphore, signal, ALL_COMMANDS);
		}
	}

	private static VulkanCommandEncoder vulkanEncoder() {
		CommandEncoder frontend = RenderSystem.getDevice().createCommandEncoder();
		return (VulkanCommandEncoder) ((FrontendCommandEncoderAccessor) frontend).tilelight$backend();
	}
}
