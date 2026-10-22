package dev.tilelight.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
	/** Ticks of lightning flash left, 0 when the flash option is off. */
	@Invoker("getSkyFlashTime")
	int tilelight$skyFlashTime();
}
