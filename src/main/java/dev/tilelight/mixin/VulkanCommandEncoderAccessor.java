package dev.tilelight.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
	@Accessor("submissionBuilder")
	VulkanQueue.Submission tilelight$submissionBuilder();

	@Accessor("submissionBuilder")
	void tilelight$setSubmissionBuilder(VulkanQueue.Submission submission);

	@Invoker("endCommandBuffer")
	void tilelight$endCommandBuffer();

	@Accessor("device")
	VulkanDevice tilelight$device();
}
