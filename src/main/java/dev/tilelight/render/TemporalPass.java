package dev.tilelight.render;

import dev.tilelight.Tilelight;
import dev.tilelight.nativeapi.NativeLib;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Gate 5: inputs for MetalFX temporal upscaling. Jitters the world projection by a sub-pixel Halton
 * offset each frame and tracks last frame's camera for motion vectors.
 */
final class TemporalPass {
	private static final long PARAMS_SIZE = 224; // tl_temporal_params in tilelight.h
	private static final int PHASES = 16;
	// History is dropped when the camera jumps further than this between frames (teleports, respawn).
	private static final double RESET_DISTANCE = 16.0;
	// MetalFX's expected jitter sign relative to the projection offset; switchable while verifying.
	private static final float JITTER_SIGN = Float.parseFloat(System.getProperty("tilelight.jitterSign", "1"));

	private final MemorySegment params = Arena.ofAuto().allocate(PARAMS_SIZE, 16);
	private final Matrix4f unjittered = new Matrix4f();
	private final Matrix4f prevViewProj = new Matrix4f();
	private Vec3 prevCamera;
	private int prevWidth, prevHeight;
	private long frame;
	private float jitterX, jitterY;

	/** Returns the projection with this frame's sub-pixel jitter for a width x height render. */
	Matrix4f jitter(Matrix4fc projection, int width, int height) {
		this.unjittered.set(projection);
		int i = (int) (this.frame % PHASES) + 1;
		this.jitterX = halton(i, 2) - 0.5f;
		this.jitterY = halton(i, 3) - 0.5f;
		// Minecraft's texel rows run along +NDC y, so a pixel offset maps to clip space without a flip.
		return new Matrix4f().translation(2f * this.jitterX / width, 2f * this.jitterY / height, 0f).mul(projection);
	}

	void submit(NativeLib lib, LevelRenderState level, Matrix4f jitteredProjection, int width, int height, long worldDepth,
			long handDepth) {
		Matrix4fc view = level.cameraRenderState.viewRotationMatrix;
		Matrix4f viewProj = new Matrix4f(this.unjittered).mul(view);
		Matrix4f invJittered = new Matrix4f(jitteredProjection).mul(view).invert();
		Vec3 cam = level.cameraRenderState.pos;
		boolean reset = this.prevCamera == null || width != this.prevWidth || height != this.prevHeight
				|| cam.distanceTo(this.prevCamera) > RESET_DISTANCE;
		Vec3 delta = reset ? Vec3.ZERO : cam.subtract(this.prevCamera);

		MemorySegment p = this.params;
		ShadowPass.writeMatrix(p, 0, invJittered);
		ShadowPass.writeMatrix(p, 64, viewProj);
		ShadowPass.writeMatrix(p, 128, reset ? viewProj : this.prevViewProj);
		p.set(ValueLayout.JAVA_FLOAT, 192, (float) delta.x);
		p.set(ValueLayout.JAVA_FLOAT, 196, (float) delta.y);
		p.set(ValueLayout.JAVA_FLOAT, 200, (float) delta.z);
		p.set(ValueLayout.JAVA_FLOAT, 204, 0f);
		p.set(ValueLayout.JAVA_FLOAT, 208, JITTER_SIGN * this.jitterX);
		p.set(ValueLayout.JAVA_FLOAT, 212, JITTER_SIGN * this.jitterY);
		p.set(ValueLayout.JAVA_FLOAT, 216, reset ? 1f : 0f);
		p.set(ValueLayout.JAVA_FLOAT, 220, 0f);
		int rc = lib.interopSetTemporal(p, worldDepth, handDepth);
		if (rc != NativeLib.OK) {
			Tilelight.LOG.error("tl_interop_set_temporal failed with code {}", rc);
		}

		this.prevViewProj.set(viewProj);
		this.prevCamera = cam;
		this.prevWidth = width;
		this.prevHeight = height;
		this.frame++;
	}

	private static float halton(int index, int base) {
		float f = 1f;
		float r = 0f;
		while (index > 0) {
			f /= base;
			r += f * (index % base);
			index /= base;
		}
		return r;
	}
}
