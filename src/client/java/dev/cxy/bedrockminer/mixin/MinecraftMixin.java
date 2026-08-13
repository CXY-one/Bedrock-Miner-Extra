package dev.cxy.bedrockminer.mixin;

import dev.cxy.bedrockminer.AutoBreaker;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While auto mode is mining the piston, block the player's own attack input
 * (continueAttack / startAttack). Otherwise, if the player holds the attack
 * button after clicking bedrock, vanilla's continueAttack keeps calling
 * continueDestroyBlock on the block under the crosshair (the bedrock), which
 * overwrites the mining state (destroyBlockPos) AutoBreaker set up for the
 * piston - resetting the progress every tick so destroyBlock never fires.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void bedrockMinerExtra$blockPlayerMining(boolean down, CallbackInfo ci) {
		if (AutoBreaker.isMining()) {
			ci.cancel();
		}
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void bedrockMinerExtra$blockPlayerAttack(CallbackInfoReturnable<Boolean> cir) {
		if (AutoBreaker.isMining()) {
			cir.setReturnValue(false);
		}
	}
}
