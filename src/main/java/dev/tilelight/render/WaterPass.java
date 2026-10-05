package dev.tilelight.render;

import dev.tilelight.Tilelight;
import dev.tilelight.nativeapi.NativeLib;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

/**
 * Gate 4: screen-space reflections on water, found by comparing depth before and after vanilla's
 * translucent pass. Needs Gate 3 Metal terrain. Configured with {@code tilelight.water} = true|false.
 */
final class WaterPass {
	private static final long PARAMS_SIZE = 176; // tl_water_params in tilelight.h
	private static final float STRENGTH = 0.85f;
	private static final float MAX_DISTANCE = 64f;
	private static final int STEPS = 24;

	private final boolean enabled = Boolean.parseBoolean(System.getProperty("tilelight.water", "true"));
	private final MemorySegment params = Arena.ofAuto().allocate(PARAMS_SIZE, 16);
	private long frame;

	boolean enabled() {
		return this.enabled;
	}

	void submit(NativeLib lib, LevelRenderState level, Matrix4f projection, long worldDepth) {
		Matrix4f viewProj = new Matrix4f(projection).mul(level.cameraRenderState.viewRotationMatrix);
		Matrix4f invViewProj = new Matrix4f(viewProj).invert();
		MemorySegment p = this.params;
		ShadowPass.writeMatrix(p, 0, viewProj);
		ShadowPass.writeMatrix(p, 64, invViewProj);
		// Horizon from vanilla's fog colour, zenith from its sky colour.
		var fog = level.cameraRenderState.fogData.color;
		p.set(ValueLayout.JAVA_FLOAT, 128, fog.x);
		p.set(ValueLayout.JAVA_FLOAT, 132, fog.y);
		p.set(ValueLayout.JAVA_FLOAT, 136, fog.z);
		p.set(ValueLayout.JAVA_FLOAT, 140, 1f);
		Vector3fc sky = level.skyRenderState.skyColor;
		p.set(ValueLayout.JAVA_FLOAT, 144, sky != null ? sky.x() : fog.x);
		p.set(ValueLayout.JAVA_FLOAT, 148, sky != null ? sky.y() : fog.y);
		p.set(ValueLayout.JAVA_FLOAT, 152, sky != null ? sky.z() : fog.z);
		p.set(ValueLayout.JAVA_FLOAT, 156, STRENGTH);
		p.set(ValueLayout.JAVA_FLOAT, 160, MAX_DISTANCE);
		p.set(ValueLayout.JAVA_FLOAT, 164, STEPS);
		p.set(ValueLayout.JAVA_FLOAT, 168, (float) (this.frame++ % 64));
		p.set(ValueLayout.JAVA_FLOAT, 172, 0f);
		int rc = lib.interopSetWater(p, worldDepth);
		if (rc != NativeLib.OK) {
			Tilelight.LOG.error("tl_interop_set_water failed with code {}", rc);
		}
	}
}
