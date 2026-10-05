package dev.tilelight.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanTransientMemory;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The per-frame upload command buffer, which ScaledFrame splits at mid-frame flushes. */
@Mixin(VulkanTransientMemory.class)
public interface VulkanTransientMemoryAccessor {
	@Accessor("commandBuffer")
	@Nullable VkCommandBuffer tilelight$commandBuffer();

	@Accessor("commandBuffer")
	void tilelight$setCommandBuffer(VkCommandBuffer commandBuffer);

	@Accessor("anyCommandRecorded")
	boolean tilelight$anyCommandRecorded();

	/** True on unified memory: mapped uploads are written in place, with no staging copy to split. */
	@Accessor("useDeviceMemoryForMappedGpuStaging")
	boolean tilelight$uploadsInPlace();

	@Accessor("anyCommandRecorded")
	void tilelight$setAnyCommandRecorded(boolean recorded);
}
