package dev.tilelight.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanGpuBuffer.Direct.class)
public interface VulkanGpuBufferDirectAccessor {
	@Accessor("vmaAllocation")
	long tilelight$vmaAllocation();
}
