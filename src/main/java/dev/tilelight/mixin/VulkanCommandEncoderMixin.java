package dev.tilelight.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.tilelight.render.FrameTimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times Minecraft's per-frame submit: queue submission (MoltenVK encodes here) and the wait for the frame before. */
@Mixin(VulkanCommandEncoder.class)
public class VulkanCommandEncoderMixin {
	@Inject(method = "submit", at = @At("HEAD"))
	private void tilelight$submitHead(CallbackInfo ci) {
		FrameTimer.submitStart();
	}

	@Inject(method = "submit", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;awaitSubmitCompletion(JJ)Z"))
	private void tilelight$beforeWait(CallbackInfo ci) {
		FrameTimer.waitStart();
	}

	@Inject(method = "submit", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;awaitSubmitCompletion(JJ)Z"))
	private void tilelight$afterWait(CallbackInfo ci) {
		FrameTimer.waitEnd();
	}
}
