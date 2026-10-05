package dev.tilelight.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.tilelight.render.ScaledFrame;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets world rendering in GameRenderer.render(): the world (and vanilla post effects) draw into
 * low-resolution textures, then Metal upscales into the native-resolution target before the GUI.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
	private void tilelight$beforeLevel(CallbackInfo ci) {
		ScaledFrame.get().beforeLevel(this.mainRenderTarget, this.gameRenderState.guiRenderState.clearColorOverride);
	}

	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
	private void tilelight$afterLevel(CallbackInfo ci) {
		ScaledFrame.get().afterLevel(this.mainRenderTarget);
	}
}
