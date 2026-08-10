package dev.cxy.bedrockminer.mixin;

import dev.cxy.bedrockminer.BedrockBreaker;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lines up the mod's packets around the mining-completion packet so the server
 * processes "cut power -> break piston -> place fresh piston" within one tick.
 *
 * In 26.1.2 the client mining flow is: when the local break progress reaches
 * 1.0, {@code continueDestroyBlock} calls
 * {@code startPrediction(seq -> { destroyBlock(pos); return STOP_DESTROY_BLOCK; })}
 * and {@code startPrediction} sends that packet AFTER running the lambda. So:
 * - the INVOKE-shift BEFORE on continueDestroyBlock (progress full, right
 *   before the prediction packet) sends the "cut power" packet first, so the
 *   piston is un-powered when it gets mined;
 * - the HEAD of destroyBlock captures the scene while the piston is present;
 * - the RETURN of startPrediction runs after STOP_DESTROY_BLOCK was sent, so
 *   the fresh-piston packets travel in the same batch, after the break.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void bedrockMinerExtra$captureScene(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		BedrockBreaker.onBlockBreakStarted(pos);
	}

	@Inject(
		method = "continueDestroyBlock",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V",
			shift = At.Shift.BEFORE
		)
	)
	private void bedrockMinerExtra$cutPowerBeforeMiningPacket(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		BedrockBreaker.onBeforeMiningCompleted(pos);
	}

	@Inject(method = "startPrediction", at = @At("RETURN"))
	private void bedrockMinerExtra$sendAfterMiningPacket(CallbackInfo ci) {
		BedrockBreaker.onPredictionPacketSent();
	}
}
