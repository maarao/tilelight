package dev.tilelight.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
	@Accessor("entityOutlineTarget")
	RenderTarget tilelight$entityOutlineTarget();

	@Accessor("viewArea")
	@Nullable ViewArea tilelight$viewArea();

	@Accessor("visibleSections")
	it.unimi.dsi.fastutil.objects.ObjectArrayList<SectionRenderDispatcher.RenderSection> tilelight$visibleSections();

	@Accessor("sectionRenderDispatcher")
	@Nullable SectionRenderDispatcher tilelight$sectionRenderDispatcher();
}
