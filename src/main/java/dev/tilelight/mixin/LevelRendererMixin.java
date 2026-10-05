package dev.tilelight.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.tilelight.render.ScaledFrame;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	// Always-on-top features (debug gizmos) clear the main depth; keep the world depth first.
	@Inject(method = "executeAlwaysOnTop", at = @At("HEAD"))
	private void tilelight$beforeAlwaysOnTop(FeatureRenderDispatcher.PreparedFrame featureFrame, RenderTarget mainTarget,
			boolean consistentDepthRequired, CallbackInfo ci) {
		ScaledFrame.get().beforeAlwaysOnTop(mainTarget);
	}
}
