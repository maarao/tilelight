package dev.tilelight.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.tilelight.render.ScaledFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets world rendering in GameRenderer.render(): the world (and vanilla post effects) draw into
 * low-resolution textures, then Metal upscales into the native-resolution target before the GUI.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Shadow
	@Final
	private RenderTarget hud3DTarget;

	@Shadow
	@Final
	private GameRenderState gameRenderState;

	// If the previous frame threw mid-world, put native targets back before render() checks sizes.
	@Inject(method = "render", at = @At("HEAD"))
	private void tilelight$renderHead(CallbackInfo ci) {
		ScaledFrame.get().restoreIfSwapped();
	}

	@Inject(method = "resize", at = @At("HEAD"))
	private void tilelight$resizeHead(int width, int height, CallbackInfo ci) {
		ScaledFrame.get().restoreIfSwapped();
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void tilelight$closeHead(CallbackInfo ci) {
		ScaledFrame.get().shutdown();
	}

	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
	private void tilelight$beforeLevel(CallbackInfo ci) {
		RenderTarget entityOutline = ((LevelRendererAccessor) this.minecraft.levelRenderer).tilelight$entityOutlineTarget();
		ScaledFrame.get().beforeLevel(this.mainRenderTarget, this.hud3DTarget, entityOutline,
				this.gameRenderState.guiRenderState.clearColorOverride);
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void tilelight$renderLevelHead(CallbackInfo ci) {
		ScaledFrame.get().beginLevelRender();
	}

	// The projection actually used for the world pass (includes view bobbing and nausea warp).
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f tilelight$captureProjection(Matrix4f projection) {
		ScaledFrame.get().captureProjection(projection);
		return projection;
	}

	// Runs just before the hand pass; the flag says whether the hand gets its own depth target.
	@ModifyArg(method = "renderLevel", index = 3, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private boolean tilelight$beforeHud(boolean consistentDepthRequired) {
		ScaledFrame.get().beforeHud(this.mainRenderTarget, consistentDepthRequired);
		return consistentDepthRequired;
	}

	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
	private void tilelight$afterLevel(CallbackInfo ci) {
		ScaledFrame.get().afterLevel(this.gameRenderState.levelRenderState);
	}
}
