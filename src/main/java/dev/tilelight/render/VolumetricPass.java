package dev.tilelight.render;

import dev.tilelight.Tilelight;
import dev.tilelight.TilelightConfig;
import dev.tilelight.nativeapi.NativeLib;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Gate 4: sun shafts and haze, ray-marched through the shadow map at quarter resolution.
 *
 * <p>Configured by {@code volumetric} and {@code fogDensity} in {@link TilelightConfig}.
 */
final class VolumetricPass {
	private static final long PARAMS_SIZE = 192; // tl_volumetric_params in tilelight.h
	private static final float MAX_DISTANCE = 128f;
	private static final int STEPS = 16;

	private final boolean enabled = TilelightConfig.get().volumetric;
	private final float density = TilelightConfig.get().fogDensity;
	private final MemorySegment params = Arena.ofAuto().allocate(PARAMS_SIZE, 16);
	private long frame;

	boolean enabled() {
		return this.enabled;
	}

	/** Queues this frame's volumetric light. shadowSample is the current shadow map's clip space. */
	void submit(NativeLib lib, LevelRenderState level, Matrix4f projection, Matrix4f shadowSample, long worldDepth) {
		Matrix4f invViewProj = new Matrix4f(projection).mul(level.cameraRenderState.viewRotationMatrix).invert();
		float a = level.skyRenderState.sunAngle;
		float sunX = (float) -Math.sin(a);
		float sunY = (float) Math.cos(a);
		float day = Math.clamp((sunY + 0.05f) / 0.3f, 0f, 1f);
		Vec3 cam = level.cameraRenderState.pos;
		// Warmer, stronger forward glow as the sun gets low.
		float low = 1f - Math.clamp(sunY / 0.6f, 0f, 1f);

		MemorySegment p = this.params;
		ShadowPass.writeMatrix(p, 0, invViewProj);
		ShadowPass.writeMatrix(p, 64, shadowSample);
		p.set(ValueLayout.JAVA_FLOAT, 128, sunX);
		p.set(ValueLayout.JAVA_FLOAT, 132, sunY);
		p.set(ValueLayout.JAVA_FLOAT, 136, 0f);
		p.set(ValueLayout.JAVA_FLOAT, 140, day * level.skyRenderState.rainBrightness);
		p.set(ValueLayout.JAVA_FLOAT, 144, 1.0f);
		p.set(ValueLayout.JAVA_FLOAT, 148, 0.9f - 0.15f * low);
		p.set(ValueLayout.JAVA_FLOAT, 152, 0.75f - 0.35f * low);
		p.set(ValueLayout.JAVA_FLOAT, 156, 0.35f);
		p.set(ValueLayout.JAVA_FLOAT, 160, this.density);
		p.set(ValueLayout.JAVA_FLOAT, 164, 0.02f);
		p.set(ValueLayout.JAVA_FLOAT, 168, (float) cam.y);
		p.set(ValueLayout.JAVA_FLOAT, 172, MAX_DISTANCE);
		p.set(ValueLayout.JAVA_FLOAT, 176, 0.6f);
		p.set(ValueLayout.JAVA_FLOAT, 180, (float) (this.frame++ % 64));
		p.set(ValueLayout.JAVA_FLOAT, 184, 1f / ShadowPass.MAP_SIZE);
		p.set(ValueLayout.JAVA_FLOAT, 188, STEPS);
		int rc = lib.interopSetVolumetric(p, worldDepth);
		if (rc != NativeLib.OK) {
			Tilelight.LOG.error("tl_interop_set_volumetric failed with code {}", rc);
		}
	}
}
