package dev.tilelight.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets ScaledFrame point the main target at its low-resolution textures while the world renders. */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
	@Accessor("colorTexture")
	void tilelight$setColorTexture(GpuTexture texture);

	@Accessor("colorTextureView")
	void tilelight$setColorTextureView(GpuTextureView view);

	@Accessor("depthTexture")
	void tilelight$setDepthTexture(GpuTexture texture);

	@Accessor("depthTextureView")
	void tilelight$setDepthTextureView(GpuTextureView view);
}
