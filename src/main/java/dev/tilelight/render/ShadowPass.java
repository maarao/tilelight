package dev.tilelight.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.tilelight.Tilelight;
import dev.tilelight.TilelightConfig;
import dev.tilelight.mixin.LevelRendererAccessor;
import dev.tilelight.nativeapi.NativeLib;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * Gate 2: a sun shadow map drawn on Metal straight from vanilla's chunk mesh buffers, applied to
 * vanilla's low-resolution image before it is upscaled.
 *
 * <p>Configured by {@code shadows} in {@link TilelightConfig}; the debug system property
 * {@code tilelight.shadowDebug} = 1 shows reconstructed positions, 2 the shadow factor.
 */
final class ShadowPass {
	static final int MAP_SIZE = 2048;
	private static final int RADIUS_SECTIONS = 5; // casters within 80 blocks horizontally
	private static final float STRENGTH = 0.45f;
	// The shadow map is redrawn at most this often, or sooner when the sun or camera moves enough.
	private static final int REDRAW_FRAMES = 8;
	private static final double REDRAW_SUN_RADIANS = 0.002; // ~0.1 degrees
	private static final double REDRAW_CAMERA_BLOCKS = 8.0;

	// tl_shadow_params and tl_draw in tilelight.h
	private static final long PARAMS_SIZE = 288;
	private static final long DRAW_SIZE = 56;
	private static final int DRAW_CUTOUT = 1;

	/** Projection matrix of the current frame's world pass, captured in GameRenderer.renderLevel. */
	static final Matrix4f levelProjection = new Matrix4f();

	private final boolean enabled = TilelightConfig.get().shadows;
	private final int debug = Integer.getInteger("tilelight.shadowDebug", 0);
	private final Arena arena = Arena.ofShared();
	private final MemorySegment params = this.arena.allocate(PARAMS_SIZE, 16);
	private MemorySegment draws = this.arena.allocate(DRAW_SIZE * 4096, 16);
	private boolean warnedNoBuffer;

	// State of the map as last drawn: it is reused, re-aimed at the moved camera, until redrawn.
	private final Matrix4f mapViewProj = new Matrix4f();
	private Vec3 mapCamera;
	private float mapSunAngle;
	private int framesSinceDraw;
	// Shadow clip space of the current map, re-aimed at this frame's camera.
	private final Matrix4f sample = new Matrix4f();

	boolean enabled() {
		return this.enabled;
	}

	/** Sun shadows only make sense under the Overworld sky; elsewhere sunAngle is stale or default. */
	boolean appliesTo(LevelRenderState level) {
		return this.enabled && level.skyRenderState.skybox == DimensionType.Skybox.OVERWORLD;
	}

	/** Forces a redraw next frame, e.g. after the map failed to draw. */
	void invalidate() {
		this.mapCamera = null;
	}

	/** The current map's shadow clip space for this frame's camera; valid after submit. */
	Matrix4f sample() {
		return this.sample;
	}

	/**
	 * Queues this frame's shadow work. With overlay, the map darkens vanilla's colour at the end of the
	 * frame: worldDepth is the low-res depth of the world and handDepth is non-zero only where the hand
	 * was drawn. Without overlay (Gate 3), the terrain pass draws and samples the map; depths may be 0.
	 */
	void submit(NativeLib lib, MetalExports exports, LevelRenderState level, boolean overlay, long worldDepth, long handDepth) {
		Minecraft mc = Minecraft.getInstance();
		LevelRendererAccessor lr = (LevelRendererAccessor) mc.levelRenderer;
		ViewArea viewArea = lr.tilelight$viewArea();
		SectionRenderDispatcher dispatcher = lr.tilelight$sectionRenderDispatcher();
		if (viewArea == null || dispatcher == null) return;

		Vec3 cam = level.cameraRenderState.pos;
		float a = level.skyRenderState.sunAngle;
		boolean redraw = this.mapCamera == null || ++this.framesSinceDraw >= REDRAW_FRAMES
				|| Math.abs(a - this.mapSunAngle) > REDRAW_SUN_RADIANS || cam.distanceTo(this.mapCamera) > REDRAW_CAMERA_BLOCKS;

		// The sky renderer rotates the sun -90 degrees about Y, then sunAngle about X, from +Y.
		Vector3d sun = new Vector3d(-Math.sin(a), Math.cos(a), 0.0).normalize();
		int count = 0;
		if (redraw) {
			count = gatherCasters(exports, viewArea, dispatcher, cam);
			this.mapViewProj.set(shadowMatrix(sun, cam));
			this.mapCamera = cam;
			this.mapSunAngle = a;
			this.framesSinceDraw = 0;
		}
		// Positions are camera-relative, so a map drawn around an earlier camera position is sampled
		// after shifting by how far the camera has moved since.
		Matrix4f sample = this.sample.set(this.mapViewProj).translate(
				(float) (cam.x - this.mapCamera.x), (float) (cam.y - this.mapCamera.y), (float) (cam.z - this.mapCamera.z));

		// Camera: projection (with view bobbing) times view rotation, on camera-relative positions.
		Matrix4f viewProj = new Matrix4f(levelProjection).mul(level.cameraRenderState.viewRotationMatrix);
		Matrix4f invViewProj = new Matrix4f(viewProj).invert();

		MemorySegment p = this.params;
		writeMatrix(p, 0, viewProj);
		writeMatrix(p, 64, invViewProj);
		writeMatrix(p, 128, this.mapViewProj);
		writeMatrix(p, 192, sample);
		p.set(ValueLayout.JAVA_FLOAT, 256, (float) sun.x);
		p.set(ValueLayout.JAVA_FLOAT, 260, (float) sun.y);
		p.set(ValueLayout.JAVA_FLOAT, 264, (float) sun.z);
		p.set(ValueLayout.JAVA_FLOAT, 268, STRENGTH);
		p.set(ValueLayout.JAVA_INT, 272, this.debug);
		p.set(ValueLayout.JAVA_INT, 276, MAP_SIZE);
		p.set(ValueLayout.JAVA_INT, 280, redraw ? 1 : 0);
		p.set(ValueLayout.JAVA_INT, 284, overlay ? 1 : 0);

		long atlas = exports.texture(mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTexture());
		int rc = lib.interopSetShadows(p, this.draws, count, worldDepth, handDepth, atlas);
		if (rc != NativeLib.OK) {
			Tilelight.LOG.error("tl_interop_set_shadows failed with code {}", rc);
			this.mapCamera = null; // the map was not redrawn; force a redraw next frame
		}
	}

	private int gatherCasters(MetalExports exports, ViewArea viewArea, SectionRenderDispatcher dispatcher, Vec3 cam) {
		SectionPos camSection = SectionPos.of(cam);
		int count = 0;
		dispatcher.lock();
		try {
			for (int dx = -RADIUS_SECTIONS; dx <= RADIUS_SECTIONS; dx++) {
				for (int dz = -RADIUS_SECTIONS; dz <= RADIUS_SECTIONS; dz++) {
					for (int sy = viewArea.minSectionY(); sy <= viewArea.maxSectionY(); sy++) { // max is inclusive
						BlockPos origin = SectionPos.of(camSection.x() + dx, sy, camSection.z() + dz).origin();
						SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(origin);
						// The view area wraps around; a stale slot holds a different section.
						if (section == null || !section.getRenderOrigin().equals(origin)) continue;
						SectionMesh mesh = section.getSectionMesh();
						for (ChunkSectionLayer layer : new ChunkSectionLayer[] {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT}) {
							SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
							if (draw == null || draw.indexCount() == 0) continue;
							SectionRenderDispatcher.RenderSectionBufferSlice slice = dispatcher.getRenderSectionSlice(mesh, layer);
							if (slice == null) continue;
							MetalExports.BufferRef vertices = exports.buffer(slice.vertexBuffer());
							if (vertices == null) {
								warnNoBuffer(slice.vertexBuffer());
								continue;
							}
							long indexBuffer = 0L;
							long indexOffset = 0L;
							int indexType = 1;
							if (draw.hasCustomIndexBuffer()) {
								if (slice.indexBuffer() == null) continue;
								MetalExports.BufferRef indices = exports.buffer(slice.indexBuffer());
								if (indices == null) continue;
								indexBuffer = indices.mtlBuffer();
								indexOffset = indices.offset() + slice.indexBufferOffset();
								indexType = draw.indexType().bytes == 4 ? 1 : 0;
							}
							ensureDrawCapacity(count + 1);
							writeDraw(this.draws, count * DRAW_SIZE, vertices.mtlBuffer(), vertices.offset() + slice.vertexBufferOffset(),
									indexBuffer, indexOffset, draw.indexCount(), indexType,
									(float) (origin.getX() - cam.x), (float) (origin.getY() - cam.y), (float) (origin.getZ() - cam.z),
									layer == ChunkSectionLayer.CUTOUT ? DRAW_CUTOUT : 0);
							count++;
						}
					}
				}
			}
		} finally {
			dispatcher.unlock();
		}
		return count;
	}

	/**
	 * Orthographic sun view around the camera. The box is shifted by the camera's sub-texel position
	 * in light space, so shadow edges stay put while the camera moves instead of shimmering.
	 */
	private static Matrix4f shadowMatrix(Vector3d sun, Vec3 cam) {
		float radius = RADIUS_SECTIONS * 16f;
		float distance = 256f;
		Vector3d up = Math.abs(sun.z) > 0.99 ? new Vector3d(0, 1, 0) : new Vector3d(0, 0, 1);
		Vector3d right = new Vector3d(up).cross(sun).normalize();
		Vector3d trueUp = new Vector3d(sun).cross(right).normalize();
		// The camera's offset from the shadow texel grid, along each light-space axis (in doubles:
		// absolute world coordinates are large).
		double texel = 2.0 * radius / MAP_SIZE;
		double lx = cam.x * right.x + cam.y * right.y + cam.z * right.z;
		double ly = cam.x * trueUp.x + cam.y * trueUp.y + cam.z * trueUp.z;
		float sx = (float) (lx - Math.floor(lx / texel) * texel);
		float sy = (float) (ly - Math.floor(ly / texel) * texel);
		Matrix4f view = new Matrix4f().lookAt(
				(float) (sun.x * distance), (float) (sun.y * distance), (float) (sun.z * distance),
				0f, 0f, 0f,
				(float) trueUp.x, (float) trueUp.y, (float) trueUp.z);
		// Metal clip space: z in [0, 1].
		Matrix4f ortho = new Matrix4f().setOrtho(-radius - sx, radius - sx, -radius - sy, radius - sy, 0f, 2f * distance, true);
		return ortho.mul(view);
	}

	private void ensureDrawCapacity(int draws) {
		if (draws * DRAW_SIZE <= this.draws.byteSize()) return;
		MemorySegment grown = this.arena.allocate(this.draws.byteSize() * 2, 16);
		grown.copyFrom(this.draws);
		this.draws = grown;
	}

	private void warnNoBuffer(GpuBuffer buffer) {
		if (this.warnedNoBuffer) return;
		this.warnedNoBuffer = true;
		Tilelight.LOG.warn("MoltenVK exported no MTLBuffer for {}; those sections cast no shadows", buffer);
	}

	/** Writes one tl_draw (tilelight.h) at offset. */
	static void writeDraw(MemorySegment d, long base, long vertexBuffer, long vertexOffset, long indexBuffer, long indexOffset,
			int indexCount, int indexType, float ox, float oy, float oz, int flags) {
		d.set(ValueLayout.JAVA_LONG, base, vertexBuffer);
		d.set(ValueLayout.JAVA_LONG, base + 8, vertexOffset);
		d.set(ValueLayout.JAVA_LONG, base + 16, indexBuffer);
		d.set(ValueLayout.JAVA_LONG, base + 24, indexOffset);
		d.set(ValueLayout.JAVA_INT, base + 32, indexCount);
		d.set(ValueLayout.JAVA_INT, base + 36, indexType);
		d.set(ValueLayout.JAVA_FLOAT, base + 40, ox);
		d.set(ValueLayout.JAVA_FLOAT, base + 44, oy);
		d.set(ValueLayout.JAVA_FLOAT, base + 48, oz);
		d.set(ValueLayout.JAVA_INT, base + 52, flags);
	}

	static void writeMatrix(MemorySegment seg, long offset, Matrix4f m) {
		float[] f = m.get(new float[16]);
		for (int i = 0; i < 16; i++) {
			seg.set(ValueLayout.JAVA_FLOAT, offset + i * 4L, f[i]);
		}
	}
}
