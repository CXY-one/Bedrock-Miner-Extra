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
 * Lines up the mod's packets around the break packet so the server processes
 * "cut power -> break piston -> place fresh piston" within one tick.
 *
 * Both mining paths end up calling {@code MultiPlayerGameMode.destroyBlock}:
 * - the slow path: {@code continueDestroyBlock} accumulates progress and, when
 *   it reaches 1.0, calls
 *   {@code startPrediction(seq -> { destroyBlock(pos); return STOP_DESTROY_BLOCK; })};
 * - the instant-mining path: {@code startDestroyBlock}'s insta-mine special
 *   case calls {@code destroyBlock(pos)} directly inside its own startPrediction
 *   lambda (skipping continueDestroyBlock entirely).
 *
 * So destroyBlock is the single hook point: at its HEAD we capture the scene
 * and cut the lever power (so the piston is un-powered but still extended when
 * it gets mined); the RETURN of startPrediction runs after the break packet
 * (START or STOP) was sent, so the fresh-piston packets travel right after it.
 * The lever useItemOn itself triggers a nested startPrediction - its RETURN is
 * skipped via {@link BedrockBreaker}'s inCutPower guard.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void bedrockMinerExtra$captureScene(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		BedrockBreaker.onBlockBreakStarted(pos);
	}

	// The prediction system (startPrediction) exists from 1.19.3 on; its RETURN
	// runs after the break packet (START/STOP) was sent, which is the
	// fresh-piston placement anchor. (1.18.2 and older are not supported: their
	// headless trick needs instant mining inside the retraction window, which
	// conflicts with this mod's any-speed design.) The inCutPower guard in
	// BedrockBreaker keeps the nested startPrediction of the cutPower useItemOn
	// from placing the fresh piston too early.
	@Inject(method = "startPrediction", at = @At("RETURN"))
	private void bedrockMinerExtra$sendAfterMiningPacket(CallbackInfo ci) {
		BedrockBreaker.onPredictionPacketSent();
	}

	/**
	 * Right after the mod placed the replacement piston, vanilla may re-mine
	 * the same spot (instant-mining re-runs startDestroyBlock because
	 * destroyBlockPos was never set; the slow path re-accumulates progress a
	 * few ticks later). The freshly placed piston would then be mined away -
	 * with an instant-mining pickaxe in the main hand (offhand piston case) the
	 * server insta-mines it on the second START_DESTROY_BLOCK. Block both
	 * entry points for the short protection window.
	 */
	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void bedrockMinerExtra$guardFreshPistonStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (BedrockBreaker.isProtected(pos)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void bedrockMinerExtra$guardFreshPistonContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (BedrockBreaker.isProtected(pos)) {
			cir.setReturnValue(false);
		}
	}
}
