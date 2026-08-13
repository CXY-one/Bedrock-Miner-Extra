package dev.cxy.bedrockminer.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the client's current mining target and progress (private fields in
 * 26.1.2) so the client tick can pre-send the rotation for horizontal facings.
 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {

	@Accessor("destroyBlockPos")
	BlockPos bedrockMinerExtra$getDestroyBlockPos();

	@Accessor("destroyProgress")
	float bedrockMinerExtra$getDestroyProgress();

	@Accessor("destroyDelay")
	int bedrockMinerExtra$getDestroyDelay();

	@Accessor("destroyDelay")
	void bedrockMinerExtra$setDestroyDelay(int destroyDelay);

	@Accessor("isDestroying")
	boolean bedrockMinerExtra$getIsDestroying();
}
