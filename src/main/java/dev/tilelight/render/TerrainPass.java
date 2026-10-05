package dev.tilelight.render;

import dev.tilelight.TilelightConfig;
import dev.tilelight.mixin.LevelRendererAccessor;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

/**
 * Gate 3: replaces vanilla's opaque (SOLID + CUTOUT) terrain with a Metal pass that lights it with
 * sun, sky and block light, using the shadow map for sun visibility.
 *
 * <p>Uses vanilla's own visible-section list, so culling matches vanilla exactly. Configured by
 * {@code terrain} in {@link TilelightConfig}.
 */
final class TerrainPass {
	// tl_terrain_params and tl_draw in tilelight.h
	private static final long PARAMS_SIZE = 224;
	private static final long DRAW_SIZE = 56;
	private static final int DRAW_CUTOUT = 1;

	private final boolean enabled = TilelightConfig.get().terrain;
	private final Arena arena = Arena.ofShared();
	private final MemorySegment params = this.arena.allocate(PARAMS_SIZE, 16);
	private MemorySegment draws = this.arena.allocate(DRAW_SIZE * 8192, 16);
	private int drawCount;
	private boolean complete;

	boolean enabled() {
		return this.enabled;
	}

	MemorySegment params() {
		return this.params;
	}

	MemorySegment draws() {
		return this.draws;
	}

	int drawCount() {
		return this.drawCount;
	}

	/** False if some vanilla-renderable section could not be exported; vanilla must draw terrain then. */
	boolean complete() {
		return this.complete;
	}

	/** Fills params and the draw list for this frame. shadowSample is null when there are no shadows. */
	void prepare(MetalExports exports, LevelRenderState level, Matrix4f projection, Matrix4f shadowSample, float sunAngle) {
		Minecraft mc = Minecraft.getInstance();
		LevelRendererAccessor lr = (LevelRendererAccessor) mc.levelRenderer;
		SectionRenderDispatcher dispatcher = lr.tilelight$sectionRenderDispatcher();
		Vec3 cam = level.cameraRenderState.pos;
		// Same as GlobalSettingsUniform's CameraBlockPos.
		BlockPos camBlock = BlockPos.containing(Math.floor(cam.x), Math.floor(cam.y), Math.floor(cam.z));
		this.complete = dispatcher != null;
		this.drawCount = dispatcher == null ? 0 : gather(exports, lr.tilelight$visibleSections(), dispatcher, camBlock);

		Matrix4f viewProj = new Matrix4f(projection).mul(level.cameraRenderState.viewRotationMatrix);
		float sunX = (float) -Math.sin(sunAngle);
		float sunY = (float) Math.cos(sunAngle);
		float day = smoothstep(-0.05f, 0.25f, sunY);
		FogData fog = level.cameraRenderState.fogData;

		MemorySegment p = this.params;
		ShadowPass.writeMatrix(p, 0, viewProj);
		ShadowPass.writeMatrix(p, 64, shadowSample != null ? shadowSample : new Matrix4f());
		p.set(ValueLayout.JAVA_FLOAT, 128, sunX);
		p.set(ValueLayout.JAVA_FLOAT, 132, sunY);
		p.set(ValueLayout.JAVA_FLOAT, 136, 0f);
		p.set(ValueLayout.JAVA_FLOAT, 140, day);
		p.set(ValueLayout.JAVA_FLOAT, 144, fog.color.x);
		p.set(ValueLayout.JAVA_FLOAT, 148, fog.color.y);
		p.set(ValueLayout.JAVA_FLOAT, 152, fog.color.z);
		p.set(ValueLayout.JAVA_FLOAT, 156, fog.color.w);
		p.set(ValueLayout.JAVA_FLOAT, 160, fog.environmentalStart);
		p.set(ValueLayout.JAVA_FLOAT, 164, fog.environmentalEnd);
		p.set(ValueLayout.JAVA_FLOAT, 168, fog.renderDistanceStart);
		p.set(ValueLayout.JAVA_FLOAT, 172, fog.renderDistanceEnd);
		p.set(ValueLayout.JAVA_FLOAT, 176, level.skyRenderState.rainBrightness);
		p.set(ValueLayout.JAVA_FLOAT, 180, 0f); // unused since lighting moved to linear HDR
		p.set(ValueLayout.JAVA_FLOAT, 184, 1f / ShadowPass.MAP_SIZE);
		p.set(ValueLayout.JAVA_FLOAT, 188, shadowSample != null ? 1f : 0f);
		// vanilla's CameraOffset: draw origins are relative to the camera's block, the fraction is added on the GPU
		p.set(ValueLayout.JAVA_FLOAT, 192, (float) (camBlock.getX() - cam.x));
		p.set(ValueLayout.JAVA_FLOAT, 196, (float) (camBlock.getY() - cam.y));
		p.set(ValueLayout.JAVA_FLOAT, 200, (float) (camBlock.getZ() - cam.z));
		p.set(ValueLayout.JAVA_FLOAT, 204, 0f);
		Vector3fc skyColor = level.skyRenderState.skyColor;
		p.set(ValueLayout.JAVA_FLOAT, 208, skyColor != null ? skyColor.x() : fog.color.x);
		p.set(ValueLayout.JAVA_FLOAT, 212, skyColor != null ? skyColor.y() : fog.color.y);
		p.set(ValueLayout.JAVA_FLOAT, 216, skyColor != null ? skyColor.z() : fog.color.z);
		p.set(ValueLayout.JAVA_FLOAT, 220, TilelightConfig.get().exposure);
	}

	private int gather(MetalExports exports, List<SectionRenderDispatcher.RenderSection> sections, SectionRenderDispatcher dispatcher,
			BlockPos camBlock) {
		int count = 0;
		dispatcher.lock();
		try {
			for (SectionRenderDispatcher.RenderSection section : sections) {
				SectionMesh mesh = section.getSectionMesh();
				BlockPos origin = section.getRenderOrigin();
				for (ChunkSectionLayer layer : new ChunkSectionLayer[] {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT}) {
					SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
					if (draw == null || draw.indexCount() == 0) continue;
					SectionRenderDispatcher.RenderSectionBufferSlice slice = dispatcher.getRenderSectionSlice(mesh, layer);
					if (slice == null) continue;
					MetalExports.BufferRef vertices = exports.buffer(slice.vertexBuffer());
					if (vertices == null) {
						this.complete = false;
						continue;
					}
					long indexBuffer = 0L;
					long indexOffset = 0L;
					int indexType = 1;
					if (draw.hasCustomIndexBuffer()) {
						if (slice.indexBuffer() == null) continue; // vanilla skips these too
						MetalExports.BufferRef indices = exports.buffer(slice.indexBuffer());
						if (indices == null) {
							this.complete = false;
							continue;
						}
						indexBuffer = indices.mtlBuffer();
						indexOffset = indices.offset() + slice.indexBufferOffset();
						indexType = draw.indexType().bytes == 4 ? 1 : 0;
					}
					if ((count + 1) * DRAW_SIZE > this.draws.byteSize()) {
						MemorySegment grown = this.arena.allocate(this.draws.byteSize() * 2, 16);
						grown.copyFrom(this.draws);
						this.draws = grown;
					}
					ShadowPass.writeDraw(this.draws, count * DRAW_SIZE, vertices.mtlBuffer(), vertices.offset() + slice.vertexBufferOffset(),
							indexBuffer, indexOffset, draw.indexCount(), indexType,
							origin.getX() - camBlock.getX(), origin.getY() - camBlock.getY(), origin.getZ() - camBlock.getZ(),
							layer == ChunkSectionLayer.CUTOUT ? DRAW_CUTOUT : 0);
					count++;
				}
			}
		} finally {
			dispatcher.unlock();
		}
		return count;
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
		return t * t * (3f - 2f * t);
	}
}
