package dev.tilelight.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Asks Minecraft's Vulkan device for VK_EXT_metal_objects, which lets us reach the Metal objects under MoltenVK. */
@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsMixin {
	private static final FeatureSet TILELIGHT_METAL_OBJECTS =
			new FeatureSet("Tilelight Metal objects", Set.of("VK_EXT_metal_objects"), Set.of());

	@Inject(method = "optionalFeatureSets", at = @At("RETURN"))
	private static void tilelight$addMetalObjects(CallbackInfoReturnable<Set<FeatureSet>> cir) {
		cir.getReturnValue().add(TILELIGHT_METAL_OBJECTS);
	}
}
