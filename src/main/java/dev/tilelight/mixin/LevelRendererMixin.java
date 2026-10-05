package dev.tilelight.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.tilelight.render.ScaledFrame;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	// prepareTranslucents runs inside the world pass right before the main render pass opens: the last
	// point where the frame can be handed to Metal before opaque terrain would be drawn.
	@Inject(method = "prepareTranslucents", at = @At("RETURN"))
	private void tilelight$terrainHandoff(CallbackInfo ci) {
		ScaledFrame.get().terrainHandoff(this.levelRenderState);
	}

	@Redirect(method = "executeSolid", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
	private void tilelight$skipOpaqueTerrain(ChunkSectionsToRender sections, ChunkSectionLayerGroup group, RenderPass renderPass,
			GpuSampler sampler, GpuTextureView atlas, boolean wireframe) {
		if (!ScaledFrame.get().terrainDrawn()) {
			sections.renderGroup(group, renderPass, sampler, atlas, wireframe);
		}
	}

	// Always-on-top features (debug gizmos) clear the main depth; keep the world depth first.
	@Inject(method = "executeAlwaysOnTop", at = @At("HEAD"))
	private void tilelight$beforeAlwaysOnTop(FeatureRenderDispatcher.PreparedFrame featureFrame, RenderTarget mainTarget,
			boolean consistentDepthRequired, CallbackInfo ci) {
		ScaledFrame.get().beforeAlwaysOnTop(mainTarget);
	}
}
