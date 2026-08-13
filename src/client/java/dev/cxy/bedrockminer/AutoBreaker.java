package dev.cxy.bedrockminer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import dev.cxy.bedrockminer.mixin.EntityAccessor;
import dev.cxy.bedrockminer.mixin.InventoryAccessor;
import dev.cxy.bedrockminer.mixin.MultiPlayerGameModeAccessor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Auto mode: while enabled, left-clicking a whitelisted block (bedrock by
 * default) queues it and automatically places a piston + lever next to it,
 * waits for the piston to extend, then mines the piston with the best pickaxe
 * in the hotbar. The vanilla mining flow then triggers the existing
 * BedrockBreaker hooks (cut the lever power, place a fresh piston facing the
 * target), so the bedrock breaks exactly like in manual mode.
 *
 * Queue: up to {@value #QUEUE_CAPACITY} positions, FIFO (the oldest entry is
 * dropped when the queue is full). Each tick the first in-range entry is
 * processed; out-of-range entries wait until the player gets close. Clicking
 * an in-range block puts it at the head so it is processed first.
 *
 * Placement search: first reuses an existing extendable piston next to the
 * target (and an existing lever powering it, honouring its on/off state);
 * otherwise, among the 6 neighbour faces of the target (sorted by distance to
 * the player), find a face where the piston slot is air, the piston head
 * direction is air (so it can extend), and a lever slot exists (attached to
 * the target bedrock first, then the piston's side). If none exists the click
 * is rejected with a "no space" message. Failed setups only mine away the
 * piston/lever they placed themselves - player-owned ones are reused, not
 * broken.
 *
 * Consumables: 1 piston for the setup + 1 piston for the replacement, and 1
 * lever, all from the hotbar (switched to automatically).
 */
public final class AutoBreaker {

	private static final int QUEUE_CAPACITY = 5;
	private static final int PLACED_CACHE_CAPACITY = 16;
	private static final double RANGE = 5.0;
	private static final int WAIT_EXTEND_MAX_TICKS = 40;
	private static final int MINE_MAX_TICKS = 120;
	private static final int VERIFY_MAX_TICKS = 30;
	private static final int CLEANUP_MAX_TICKS = 80;
	private static final int MAX_RETRIES = 2;

	private enum State {
		IDLE, PLACE_PISTON, PLACE_PISTON2, PLACE_PISTON_VERIFY, PLACE_LEVER, PLACE_LEVER2, POWER_LEVER, WAIT_EXTEND, MINE_ROTATE, MINE, VERIFY, CLEANUP, SUCCESS_CLEANUP
	}

	/**
	 * @param attachDir direction from the lever slot towards the block the
	 *                  lever attaches to (and powers): the target bedrock or
	 *                  the piston itself. Unused when the lever is reused.
	 * @param needPlacePiston true if a new piston must be placed (false = an
	 *                        existing extendable piston next to the target is reused)
	 * @param needPlaceLever true if a new lever must be placed (false = an
	 *                       existing lever powering the piston is reused)
	 * @param needPowerLever true if the lever must be flipped on (a freshly
	 *                       placed lever, or an existing one that is off)
	 */
	private record Placement(BlockPos pistonPos, Direction extendDir, BlockPos leverPos, Direction attachDir,
		boolean needPlacePiston, boolean needPlaceLever, boolean needPowerLever) {
	}

	private record LeverSpot(BlockPos leverPos, Direction attachDir) {
	}

	private static State state = State.IDLE;
	private static BlockPos targetPos;
	private static BlockPos pistonPos;
	private static BlockPos leverPos;
	private static Direction extendDir;
	private static Direction attachDir;
	// Direction from the piston towards the target bedrock; the replacement
	// piston placed by BedrockBreaker faces this way.
	private static Direction bedrockDir;
	private static boolean needPlacePiston;
	private static boolean needPlaceLever;
	private static boolean needPowerLever;
	private static int waitTicks;
	private static int mineTicks;
	private static int verifyTicks;
	private static int cleanupTicks;
	private static int retries;
	private static boolean miningStarted;
	private static boolean instantFinishing;

	private static final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
	private static final Set<BlockPos> queued = new HashSet<>();

	// Piston slots where the placement failed for the current target (e.g.
	// blocked by the player standing there); findPlacement skips them so the
	// auto mode searches another face instead of failing.
	private static final Set<BlockPos> failedPistonSlots = new HashSet<>();

	// Positions of pistons / levers WE placed in auto mode (most recent first,
	// bounded). After the target bedrock breaks, SUCCESS_CLEANUP mines away the
	// cached pistons that no longer have a bedrock neighbour (and levers that
	// are still around), so the area does not fill up with leftover setups.
	private static final LinkedHashSet<BlockPos> placedPistons = new LinkedHashSet<>();
	private static final LinkedHashSet<BlockPos> placedLevers = new LinkedHashSet<>();

	private AutoBreaker() {
	}

	public static boolean isEnabled() {
		return BedrockBreaker.isAutoEnabled();
	}

	/** True while the auto-mode state machine is mid-break. */
	public static boolean isBusy() {
		return state != State.IDLE;
	}

	/** True while auto mode is actually mining the piston (player input must
	 *  not interfere with the client-side mining state). */
	public static boolean isMining() {
		return state == State.MINE || state == State.VERIFY;
	}

	// ------------------------------------------------------------------
	// Queue
	// ------------------------------------------------------------------

	/** Left-click on a whitelisted block (client side): queue it for breaking. */
	public static void enqueue(BlockPos pos) {
		if (!queued.add(pos)) {
			return;
		}
		if (queue.size() >= QUEUE_CAPACITY) {
			BlockPos removed = queue.removeFirst();
			queued.remove(removed);
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (isInRange(player, pos)) {
			queue.addFirst(pos);
		} else {
			queue.addLast(pos);
		}
	}

	private static BlockPos nextInRange() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		for (BlockPos pos : queue) {
			if (isInRange(player, pos)) {
				return pos;
			}
		}
		return null;
	}

	private static boolean isInRange(LocalPlayer player, BlockPos pos) {
		if (player == null) {
			return false;
		}
		Vec3 center = Vec3.atCenterOf(pos);
		double dx = player.getX() - center.x;
		double dy = player.getY() - center.y;
		double dz = player.getZ() - center.z;
		return dx * dx + dy * dy + dz * dz <= RANGE * RANGE;
	}

	// ------------------------------------------------------------------
	// Tick-driven state machine
	// ------------------------------------------------------------------

	public static void tick(Minecraft mc) {
		if (!isEnabled()) {
			// Auto mode off: drop the queue.
			queue.clear();
			queued.clear();
			state = State.IDLE;
			return;
		}
		if (mc.player == null || mc.gameMode == null || mc.level == null) {
			state = State.IDLE;
			return;
		}
		LocalPlayer player = mc.player;
		MultiPlayerGameMode gameMode = mc.gameMode;
		Level level = mc.level;

		switch (state) {
			case IDLE -> {
				BlockPos next = nextInRange();
				if (next != null) {
					startBreak(level, next);
				}
			}
			case PLACE_PISTON -> {
				if (!needPlacePiston) {
					state = State.PLACE_LEVER;
					return;
				}
				// Send the rotation packet first; the server's yHeadRot only
				// updates on the player entity tick (one-tick delay), so the
				// piston is placed on the next tick to get the right facing.
				if (!switchToItem(player, Blocks.PISTON.asItem())) {
					fail(level, player);
					return;
				}
				float[] yp = BedrockBreaker.getYawPitch(extendDir);
				player.connection.send(BedrockBreaker.rotPacket(yp[0], yp[1], player.onGround()));
				state = State.PLACE_PISTON2;
			}
			case PLACE_PISTON2 -> {
				placeBlockWithView(player, gameMode, pistonPos, extendDir);
				state = State.PLACE_PISTON_VERIFY;
			}
			case PLACE_PISTON_VERIFY -> {
				// Verify the piston actually got placed (it may fail if the
				// player stands on the slot). If not, remember the slot and
				// search another face for this target.
				if (level.getBlockState(pistonPos).is(Blocks.PISTON)) {
					if (needPlacePiston) {
						rememberPlaced(placedPistons, pistonPos);
					}
					state = State.PLACE_LEVER;
				} else {
					// BedrockBreaker.LOGGER.info("auto: piston placement failed at {} (blocked?), searching another spot", pistonPos);
					failedPistonSlots.add(pistonPos);
					startBreak(level, targetPos);
				}
			}
			case PLACE_LEVER -> {
				if (!needPlaceLever) {
					state = needPowerLever ? State.POWER_LEVER : State.WAIT_EXTEND;
					waitTicks = 0;
					return;
				}
				// The lever is placed by clicking the attach target's face
				// towards the lever slot (see placeLeverWithView), so the
				// attachment face is exact and does not depend on the server
				// view. Still pre-rotate the server view (harmless, keeps the
				// client prediction consistent) and place one tick later.
				if (!switchToItem(player, Blocks.LEVER.asItem())) {
					fail(level, player);
					return;
				}
				float[] yp = BedrockBreaker.getYawPitch(attachDir.getOpposite());
				player.connection.send(BedrockBreaker.rotPacket(yp[0], yp[1], player.onGround()));
				state = State.PLACE_LEVER2;
			}
			case PLACE_LEVER2 -> {
				placeLeverWithView(player, gameMode);
				if (needPlaceLever) {
					rememberPlaced(placedLevers, leverPos);
				}
				// BedrockBreaker.LOGGER.info("auto: lever placed at {} attach={}", leverPos, attachDir);
				state = State.POWER_LEVER;
			}
			case POWER_LEVER -> {
				// The lever is placed un-powered; flip it so the piston extends.
				gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(leverPos), Direction.UP, leverPos, false));
				// BedrockBreaker.LOGGER.info("auto: powered lever at {}", leverPos);
				state = State.WAIT_EXTEND;
				waitTicks = 0;
			}
			case WAIT_EXTEND -> {
				if (level.getBlockState(pistonPos).is(Blocks.PISTON) && level.getBlockState(pistonPos).getValue(PistonBaseBlock.EXTENDED)) {
					// BedrockBreaker.LOGGER.info("auto: piston extended at {}", pistonPos);
					state = State.MINE_ROTATE;
				} else if (++waitTicks > WAIT_EXTEND_MAX_TICKS) {
					// BedrockBreaker.LOGGER.info("auto: wait extend timeout at {}", pistonPos);
					fail(level, player);
				}
			}
			case MINE_ROTATE -> {
				// The replacement piston placed by BedrockBreaker faces the
				// bedrock, and its facing is derived from the server-side view.
				// The server's yHeadRot lags one tick, so pre-send the rotation
				// now and start mining next tick.
				//
				// Start with a NON-instant item (empty hand, or any item that
				// cannot insta-mine pistons): instant mining makes the client
				// and server destroy speeds drift apart, so the client's
				// destroyBlock fires before the server reached 0.7 progress ->
				// the server defers the break and the replacement placement
				// (same tick) fails. Building up progress slowly keeps both in
				// sync; we then finish with an instant pickaxe (see MINE).
				if (!switchToSlowItem(player)) {
					showActionBar("Bedrock Miner Extra: 快捷栏没有可用于慢速挖掘的物品，无法破基岩");
					fail(level, player);
					return;
				}
				float[] yp = BedrockBreaker.getYawPitch(bedrockDir);
				player.connection.send(BedrockBreaker.rotPacket(yp[0], yp[1], player.onGround()));
				state = State.MINE;
				mineTicks = 0;
				miningStarted = false;
				instantFinishing = false;
			}
			case MINE -> {
				// Keep the server view facing the bedrock every tick (the
				// server's yHeadRot lags one tick, so a single early rotation
				// may still be too late if packets straddle a tick boundary).
				float[] yp = BedrockBreaker.getYawPitch(bedrockDir);
				player.connection.send(BedrockBreaker.rotPacket(yp[0], yp[1], player.onGround()));
				if (isBroken(level)) {
					onSuccess(player);
					return;
				}
				// The piston was mined (or replaced): the break happened, so if
				// the bedrock is still there this attempt failed - don't keep
				// mining the replacement piston.
				BlockState pistonState = level.getBlockState(pistonPos);
				if (!pistonState.is(Blocks.PISTON) || !pistonState.getValue(PistonBaseBlock.EXTENDED)) {
					// Mining finished (piston mined away / replacement placed):
					// give the bedrock a few ticks to break, then check.
					state = State.VERIFY;
					verifyTicks = 0;
					return;
				}
				if (++mineTicks > MINE_MAX_TICKS) {
					fail(level, player);
					return;
				}
				if (!miningStarted) {
					// Clear any leftover mining state from manual mode / cleanup.
					gameMode.stopDestroyBlock();
					((MultiPlayerGameModeAccessor) gameMode).bedrockMinerExtra$setDestroyDelay(0);
					// Re-assert the slow item right before START_DESTROY_BLOCK
					// so the server mines at the same (slow) speed as the client.
					if (!switchToSlowItem(player)) {
						fail(level, player);
						return;
					}
					// BedrockBreaker.LOGGER.info("auto: mine start piston={} state={} mainHand={} destroyProgress={}",
					// 	pistonPos, pistonState, player.getMainHandItem(),
					// 	pistonState.getDestroyProgress(player, player.level(), pistonPos));
					boolean sdb = gameMode.startDestroyBlock(pistonPos, Direction.UP);
					miningStarted = true;
					// BedrockBreaker.LOGGER.info("auto: startDestroyBlock returned {}", sdb);
				}
				boolean cdb = gameMode.continueDestroyBlock(pistonPos, Direction.UP);
				// Finish fast: once progress is safely above the server's 0.7
				// threshold (or after 0.2s of slow mining), switch to an
				// instant pickaxe and let the client insta-mine. If the player
				// has no instant pickaxe, switch back to the slow item and just
				// mine it out (slow but reliable).
				if (!instantFinishing
					&& (((MultiPlayerGameModeAccessor) gameMode).bedrockMinerExtra$getDestroyProgress() >= 0.7F || mineTicks >= 4)) {
					if (switchToBestPickaxe(player)) {
						if (speedWithEfficiency(player, player.getMainHandItem()) >= 15.0F) {
							instantFinishing = true;
							gameMode.startDestroyBlock(pistonPos, Direction.UP);
							// BedrockBreaker.LOGGER.info("auto: instant finish (progress 0.7+ / 0.2s)");
						} else {
							// No instant pickaxe available: keep slow mining.
							switchToSlowItem(player);
						}
					}
				}
				// Debug: per-tick mining state (was used to diagnose the slow-vs-
				// instant mining sync issues). Re-enable when debugging.
				// if (mineTicks <= 5 || mineTicks % 20 == 0) {
				// 	MultiPlayerGameModeAccessor acc = (MultiPlayerGameModeAccessor) gameMode;
				// 	BedrockBreaker.LOGGER.info("auto: mine tick={} cdb={} destroyDelay={} isDestroying={} destroyPos={} progress={} pistonState={}",
				// 		mineTicks, cdb, acc.bedrockMinerExtra$getDestroyDelay(),
				// 		acc.bedrockMinerExtra$getIsDestroying(), acc.bedrockMinerExtra$getDestroyBlockPos(),
				// 		acc.bedrockMinerExtra$getDestroyProgress(), level.getBlockState(pistonPos));
				// }
			}
			case VERIFY -> {
				// The break already happened; wait for the bedrock removal to be
				// reflected client-side before deciding success/failure.
				float[] yp = BedrockBreaker.getYawPitch(bedrockDir);
				player.connection.send(BedrockBreaker.rotPacket(yp[0], yp[1], player.onGround()));
				if (isBroken(level)) {
					onSuccess(player);
					return;
				}
				if (++verifyTicks > VERIFY_MAX_TICKS) {
					fail(level, player);
				}
			}
			case CLEANUP -> {
				cleanup(level, player, gameMode);
			}
			case SUCCESS_CLEANUP -> {
				successCleanup(level, player, gameMode);
			}
		}
	}

	private static void startBreak(Level level, BlockPos pos) {
		if (!pos.equals(targetPos)) {
			retries = 0;
			failedPistonSlots.clear();
		}
		targetPos = pos;
		LocalPlayer player = Minecraft.getInstance().player;
		Placement placement = findPlacement(level, pos);
		if (placement == null) {
			queue.remove(pos);
			queued.remove(pos);
			failedPistonSlots.clear();
			if (player != null) {
				showActionBar("Bedrock Miner Extra: 空间不足，无法破基岩（已移除队列）");
			}
			state = State.IDLE;
			return;
		}
		pistonPos = placement.pistonPos();
		extendDir = placement.extendDir();
		leverPos = placement.leverPos();
		attachDir = placement.attachDir();
		bedrockDir = directionFromDelta(pos.getX() - pistonPos.getX(), pos.getY() - pistonPos.getY(), pos.getZ() - pistonPos.getZ());
		if (bedrockDir == null) {
			bedrockDir = Direction.UP;
		}
		// Tell BedrockBreaker which bedrock we target, so the replacement
		// piston faces it (capture() would otherwise prefer a vertical
		// neighbour which may be a different bedrock block).
		BedrockBreaker.setExpectedBedrockDir(bedrockDir);
		needPlacePiston = placement.needPlacePiston();
		needPlaceLever = placement.needPlaceLever();
		needPowerLever = placement.needPowerLever();
		// BedrockBreaker.LOGGER.info("auto: setup target={} piston={} facing={} lever={} attach={} needPlacePiston={} needPlaceLever={} needPowerLever={}",
		// 	pos, pistonPos, extendDir, leverPos, attachDir, needPlacePiston, needPlaceLever, needPowerLever);
		state = State.PLACE_PISTON;
	}

	private static boolean isBroken(Level level) {
		return targetPos != null && !BedrockBreaker.isWhitelisted(level.getBlockState(targetPos).getBlock());
	}

	private static void onSuccess(LocalPlayer player) {
		queue.remove(targetPos);
		queued.remove(targetPos);
		retries = 0;
		failedPistonSlots.clear();
		miningStarted = false;
		instantFinishing = false;
		BedrockBreaker.setExpectedBedrockDir(null);
		// Restore the server-side view (we kept it facing the bedrock while mining).
		player.connection.send(BedrockBreaker.rotPacket(player.getYRot(), player.getXRot(), player.onGround()));
		showActionBar("Bedrock Miner Extra: 破基岩成功");
		// Clean up the pistons/levers we placed that are now useless.
		cleanupTicks = 0;
		state = State.SUCCESS_CLEANUP;
	}

	private static void fail(Level level, LocalPlayer player) {
		// BedrockBreaker.LOGGER.info("auto: fail retry={}/{} target={}", retries + 1, MAX_RETRIES, targetPos);
		if (++retries > MAX_RETRIES) {
			queue.remove(targetPos);
			queued.remove(targetPos);
			failedPistonSlots.clear();
			retries = 0;
			showActionBar("Bedrock Miner Extra: 破基岩失败，已移除队列");
		}
		BedrockBreaker.setExpectedBedrockDir(null);
		// Restore the server-side view that MINE_ROTATE pre-rotated.
		player.connection.send(BedrockBreaker.rotPacket(player.getYRot(), player.getXRot(), player.onGround()));
		miningStarted = false;
		instantFinishing = false;
		cleanupTicks = 0;
		state = State.CLEANUP;
	}

	// ------------------------------------------------------------------
	// Placement search
	// ------------------------------------------------------------------

	/**
	 * Finds a setup for breaking {@code target}: first reuses an existing
	 * extendable piston next to the target (and an existing lever powering it,
	 * honouring its on/off state), otherwise finds a fresh placement: piston
	 * slot, piston head direction (must be air so the piston can extend), lever
	 * slot. Neighbour faces are tried closest-to-the-player first.
	 */
	private static Placement findPlacement(Level level, BlockPos target) {
		LocalPlayer player = Minecraft.getInstance().player;
		Vec3 eye = player != null ? new Vec3(player.getX(), player.getY(), player.getZ()) : Vec3.ZERO;

		List<Direction> dirs = new ArrayList<>(List.of(Direction.values()));
		dirs.sort(Comparator.comparingDouble(d -> eye.distanceToSqr(Vec3.atCenterOf(target.relative(d)))));

		// 1) Reuse an existing extendable piston next to the target.
		for (Direction face : dirs) {
			BlockPos p = target.relative(face);
			BlockState s = level.getBlockState(p);
			if (!s.is(Blocks.PISTON)) {
				continue;
			}
			Direction facing = s.getValue(PistonBaseBlock.FACING);
			if (facing == face) {
				// Head points at the bedrock; it cannot extend.
				continue;
			}
			if (!level.getBlockState(p.relative(facing)).isAir()) {
				continue;
			}
			Placement reuse = reusePiston(level, target, p, facing, dirs);
			if (reuse != null) {
				return reuse;
			}
		}

		// 2) Fresh placement.
		for (Direction face : dirs) {
			BlockPos pistonSlot = target.relative(face);
			if (failedPistonSlots.contains(pistonSlot)) {
				continue;
			}
			if (!level.getBlockState(pistonSlot).isAir()) {
				continue;
			}
			// The player standing on the piston slot (or on the extension spot)
			// blocks the placement / extension - skip and try another face.
			if (playerIntersects(pistonSlot)) {
				continue;
			}
			for (Direction extend : dirs) {
				if (extend == face) {
					// Head direction must not point at the target (bedrock would
					// block the extension).
					continue;
				}
				if (playerIntersects(pistonSlot.relative(extend))) {
					continue;
				}
				if (!level.getBlockState(pistonSlot.relative(extend)).isAir()) {
					continue;
				}
				// Reuse an existing lever (on the target bedrock or the piston)
				// if one is already there - otherwise a second lever would keep
				// the piston powered and break the headless trick.
				BlockPos existingLever = findExistingLever(level, target, pistonSlot);
				if (existingLever != null) {
					boolean powered = level.getBlockState(existingLever).getValue(LeverBlock.POWERED);
					return new Placement(pistonSlot, extend, existingLever, Direction.UP, true, false, !powered);
				}
				LeverSpot spot = findLeverSpot(level, target, pistonSlot, extend, dirs);
				if (spot == null) {
					continue;
				}
				return new Placement(pistonSlot, extend, spot.leverPos(), spot.attachDir(), true, true, true);
			}
		}
		return null;
	}

	/**
	 * Reuses an existing piston: prefers an existing lever that can power it
	 * (checks the lever's on/off state), otherwise finds a spot for a new
	 * lever. Returns null if no lever is available.
	 */
	private static Placement reusePiston(Level level, BlockPos target, BlockPos pistonPos, Direction facing, List<Direction> dirs) {
		BlockPos lever = findExistingLever(level, target, pistonPos);
		if (lever != null) {
			boolean powered = level.getBlockState(lever).getValue(LeverBlock.POWERED);
			return new Placement(pistonPos, facing, lever, Direction.UP, false, false, !powered);
		}
		LeverSpot spot = findLeverSpot(level, target, pistonPos, facing, dirs);
		if (spot != null) {
			return new Placement(pistonPos, facing, spot.leverPos(), spot.attachDir(), false, true, true);
		}
		return null;
	}

	/**
	 * An existing lever that can power the piston: adjacent to the piston
	 * (attached to it) or adjacent to the target bedrock (attached to the
	 * bedrock, which then powers the piston).
	 */
	private static BlockPos findExistingLever(Level level, BlockPos target, BlockPos pistonPos) {
		for (Direction d : Direction.values()) {
			BlockPos lp = pistonPos.relative(d);
			if (level.getBlockState(lp).is(Blocks.LEVER)) {
				return lp;
			}
		}
		for (Direction d : Direction.values()) {
			BlockPos lp = target.relative(d);
			if (level.getBlockState(lp).is(Blocks.LEVER)) {
				return lp;
			}
		}
		return null;
	}

	/**
	 * Finds a spot for a new lever: attached to the target bedrock first (the
	 * bedrock then powers the piston, 100% reliable), then attached to a
	 * horizontal side of the piston. Never on the piston's extension face.
	 */
	private static LeverSpot findLeverSpot(Level level, BlockPos target, BlockPos pistonPos, Direction extendDir, List<Direction> dirs) {
		for (Direction lv : dirs) {
			BlockPos leverSlot = target.relative(lv);
			if (leverSlot.equals(pistonPos) || leverSlot.equals(pistonPos.relative(extendDir))) {
				continue;
			}
			if (playerIntersects(leverSlot)) {
				continue;
			}
			if (!level.getBlockState(leverSlot).isAir()) {
				continue;
			}
			return new LeverSpot(leverSlot, lv.getOpposite());
		}
		for (Direction lv : Direction.Plane.HORIZONTAL) {
			BlockPos leverSlot = pistonPos.relative(lv);
			if (leverSlot.equals(target) || leverSlot.equals(pistonPos.relative(extendDir))) {
				continue;
			}
			if (playerIntersects(leverSlot)) {
				continue;
			}
			if (!level.getBlockState(leverSlot).isAir()) {
				continue;
			}
			return new LeverSpot(leverSlot, lv.getOpposite());
		}
		return null;
	}

	/** True if the player's bounding box intersects the given block slot (a
	 *  block cannot be placed / a piston cannot extend there). Mirrors the
	 *  reference project's {@code player.boundingBox.intersects(AABB(extendPos))}
	 *  quality check. */
	private static boolean playerIntersects(BlockPos pos) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		return player.getBoundingBox().intersects(new AABB(pos));
	}

	/** Records a placed block in a bounded most-recent-first cache. */
	private static void rememberPlaced(LinkedHashSet<BlockPos> cache, BlockPos pos) {
		cache.remove(pos);
		cache.add(pos);
		while (cache.size() > PLACED_CACHE_CAPACITY) {
			Iterator<BlockPos> it = cache.iterator();
			it.next();
			it.remove();
		}
	}

	// ------------------------------------------------------------------
	// Block placement
	// ------------------------------------------------------------------

	/**
	 * Places a block at {@code pos} while temporarily redirecting the player
	 * view so the client prediction (and the already-sent rotation packet)
	 * derive the wanted facing. The rotation packet for the server was sent one
	 * tick earlier (PLACE_PISTON) so the server's yHeadRot is up to date.
	 */
	private static void placeBlockWithView(LocalPlayer player, MultiPlayerGameMode gameMode, BlockPos pos, Direction facing) {
		BlockPos hitPos = pos.relative(facing.getOpposite());
		Vec3 hitVec = Vec3.atCenterOf(hitPos).add(facing.getStepX() * 0.5, facing.getStepY() * 0.5, facing.getStepZ() * 0.5);
		BlockHitResult hitResult = new BlockHitResult(hitVec, facing, pos, false);

		float[] yp = BedrockBreaker.getYawPitch(facing);
		float yaw = yp[0];
		float pitch = yp[1];

		float oldXRot = player.getXRot();
		float oldYRot = player.getYRot();
		float oldYHeadRot = player.yHeadRot;
		EntityAccessor accessor = (EntityAccessor) player;
		try {
			accessor.bedrockMinerExtra$setXRot(pitch);
			accessor.bedrockMinerExtra$setYRot(yaw);
			player.yHeadRot = yaw;
			gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
		} finally {
			// Local view only; server view stays at the placement rotation.
			accessor.bedrockMinerExtra$setXRot(oldXRot);
			accessor.bedrockMinerExtra$setYRot(oldYRot);
			player.yHeadRot = oldYHeadRot;
		}
	}

	/**
	 * Places the lever at {@code leverPos} with a guaranteed attachment face.
	 * Instead of relying on the server's yHeadRot (which lags one tick), the
	 * click targets the attach block (target bedrock or piston side) on the
	 * face towards the lever slot: the server's LeverBlock.getStateForPlacement
	 * takes the first entry of getNearestLookingDirections() - which is the
	 * clicked face - so the lever attaches exactly where we want.
	 */
	private static void placeLeverWithView(LocalPlayer player, MultiPlayerGameMode gameMode) {
		BlockPos attachTarget = leverPos.relative(attachDir);
		Direction clickFace = attachDir.getOpposite();
		Vec3 hitVec = Vec3.atCenterOf(attachTarget)
			.add(clickFace.getStepX() * 0.5, clickFace.getStepY() * 0.5, clickFace.getStepZ() * 0.5);
		BlockHitResult hitResult = new BlockHitResult(hitVec, clickFace, attachTarget, false);

		// Keep the local view facing the attach target while placing so the
		// client-side prediction matches the server; the server side no longer
		// needs the pre-rotated yHeadRot for the attachment face, but the
		// rotation packet stays harmless.
		float[] yp = BedrockBreaker.getYawPitch(attachDir.getOpposite());
		float yaw = yp[0];
		float pitch = yp[1];

		float oldXRot = player.getXRot();
		float oldYRot = player.getYRot();
		float oldYHeadRot = player.yHeadRot;
		EntityAccessor accessor = (EntityAccessor) player;
		try {
			accessor.bedrockMinerExtra$setXRot(pitch);
			accessor.bedrockMinerExtra$setYRot(yaw);
			player.yHeadRot = yaw;
			gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
		} finally {
			// Local view only; server view stays at the placement rotation.
			accessor.bedrockMinerExtra$setXRot(oldXRot);
			accessor.bedrockMinerExtra$setYRot(oldYRot);
			player.yHeadRot = oldYHeadRot;
		}
	}

	// ------------------------------------------------------------------
	// Inventory helpers
	// ------------------------------------------------------------------

	private static boolean switchToItem(LocalPlayer player, Item item) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (inventory.getItem(i).is(item)) {
				((InventoryAccessor) inventory).bedrockMinerExtra$setSelected(i);
				player.connection.send(new ServerboundSetCarriedItemPacket(i));
				return true;
			}
		}
		return false;
	}

	/**
	 * Switches to the hotbar item with the highest mining speed on pistons.
	 * getDestroySpeed only reads the Tool component's base speed (it ignores
	 * the Efficiency enchantment), so the enchantment multiplier is added
	 * manually here - otherwise an Efficiency V pickaxe ties with a plain one
	 * and the first hotbar slot wins (which may not be the fast pickaxe).
	 */
	private static boolean switchToBestPickaxe(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		int best = -1;
		float bestSpeed = -1.0F;
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			float speed = speedWithEfficiency(player, stack);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = i;
			}
		}
		if (best < 0) {
			return false;
		}
		((InventoryAccessor) inventory).bedrockMinerExtra$setSelected(best);
		player.connection.send(new ServerboundSetCarriedItemPacket(best));
		return true;
	}

	/**
	 * Switches to an item that CANNOT insta-mine pistons (progress < 1.0):
	 * empty hand first, then any hotbar item whose effective speed on pistons
	 * is below the instant threshold (base speed * efficiency < 15).
	 * Slow mining keeps the client and server destroy speeds in sync, so the
	 * headless break always lands in the right window.
	 */
	private static boolean switchToSlowItem(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (inventory.getItem(i).isEmpty()) {
				((InventoryAccessor) inventory).bedrockMinerExtra$setSelected(i);
				player.connection.send(new ServerboundSetCarriedItemPacket(i));
				return true;
			}
		}
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			// Slow enough that the client's destroyBlock lands while the
			// server progress is still in [0.7, 1.0): speed < 7.5 (< 0.5/tick,
			// i.e. stone/iron tier and below; netherite 0.6/tick is too fast).
			if (speedWithEfficiency(player, stack) < 7.5F) {
				((InventoryAccessor) inventory).bedrockMinerExtra$setSelected(i);
				player.connection.send(new ServerboundSetCarriedItemPacket(i));
				return true;
			}
		}
		return false;
	}

	/** Effective piston-mining speed including the Efficiency enchantment. */
	private static float speedWithEfficiency(LocalPlayer player, ItemStack stack) {
		float base = stack.getDestroySpeed(Blocks.PISTON.defaultBlockState());
		int efficiency = efficiencyLevel(player, stack);
		return efficiency > 0 ? base * (1.0F + efficiency * efficiency) : base;
	}

	private static int efficiencyLevel(LocalPlayer player, ItemStack stack) {
		//? if >=1.21 {
		// 1.21+：EFFICIENCY is a ResourceKey; find the holder's level via the
		// ItemEnchantments entry set.
		var ench = stack.getEnchantments();
		if (ench != null) {
			for (var e : ench.entrySet()) {
				if (e.getKey().is(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY)) {
					return e.getValue();
				}
			}
		}
		return 0;
		//?} else if >=1.20.5 {
		/*return net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
			net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY, stack);*/
		//?} else {
		/*return net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
			net.minecraft.world.item.enchantment.Enchantments.BLOCK_EFFICIENCY, stack);*/
		//?}
	}

	// ------------------------------------------------------------------
	// Cleanup (mine away the placed piston/lever after a failure)
	// ------------------------------------------------------------------

	private static void cleanup(Level level, LocalPlayer player, MultiPlayerGameMode gameMode) {
		if (cleanupTicks++ > CLEANUP_MAX_TICKS) {
			miningStarted = false;
			state = State.IDLE;
			return;
		}
		// Mine away the piston WE placed; for the lever, just flip it OFF (it is
		// slow to mine and will be reused by the next attempt).
		if (needPlacePiston && level.getBlockState(pistonPos).is(Blocks.PISTON)) {
			if (!miningStarted) {
				if (!switchToBestPickaxe(player)) {
					state = State.IDLE;
					return;
				}
				miningStarted = true;
			}
			gameMode.startDestroyBlock(pistonPos, Direction.UP);
			gameMode.continueDestroyBlock(pistonPos, Direction.UP);
		} else if (needPlaceLever && level.getBlockState(leverPos).is(Blocks.LEVER)
			&& level.getBlockState(leverPos).getValue(LeverBlock.POWERED)) {
			gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(leverPos), Direction.UP, leverPos, false));
			miningStarted = false;
			state = State.IDLE;
		} else {
			miningStarted = false;
			state = State.IDLE;
		}
	}

	// ------------------------------------------------------------------
	// Post-success cleanup (mine away cached pistons/levers that are useless)
	// ------------------------------------------------------------------

	/**
	 * After the target bedrock broke, mines away the pistons/levers WE placed
	 * that are now useless: pistons with no bedrock neighbour left (or that
	 * cannot extend), and levers that are still around. Player-owned setups are
	 * never touched (they are not in the cache).
	 */
	private static void successCleanup(Level level, LocalPlayer player, MultiPlayerGameMode gameMode) {
		if (cleanupTicks++ > CLEANUP_MAX_TICKS) {
			placedPistons.clear();
			placedLevers.clear();
			miningStarted = false;
			state = State.IDLE;
			return;
		}
		// 1) Mine away useless placed pistons, one at a time.
		BlockPos piston = firstUselessPlacedPiston(level);
		if (piston != null) {
			if (!miningStarted) {
				if (!switchToBestPickaxe(player)) {
					placedPistons.clear();
					placedLevers.clear();
					miningStarted = false;
					state = State.IDLE;
					return;
				}
				gameMode.stopDestroyBlock();
				((MultiPlayerGameModeAccessor) gameMode).bedrockMinerExtra$setDestroyDelay(0);
				miningStarted = true;
			}
			gameMode.startDestroyBlock(piston, Direction.UP);
			gameMode.continueDestroyBlock(piston, Direction.UP);
			if (!level.getBlockState(piston).is(Blocks.PISTON)) {
				placedPistons.remove(piston);
				miningStarted = false;
			}
			return;
		}
		miningStarted = false;
		// 2) Mine away our placed levers that are still around (a lever attached
		//    to a mined piston / broken bedrock already dropped on its own).
		BlockPos lever = firstPlacedLever(level);
		if (lever != null) {
			if (!miningStarted) {
				if (!switchToBestPickaxe(player)) {
					placedPistons.clear();
					placedLevers.clear();
					miningStarted = false;
					state = State.IDLE;
					return;
				}
				gameMode.stopDestroyBlock();
				((MultiPlayerGameModeAccessor) gameMode).bedrockMinerExtra$setDestroyDelay(0);
				miningStarted = true;
			}
			gameMode.startDestroyBlock(lever, Direction.UP);
			gameMode.continueDestroyBlock(lever, Direction.UP);
			if (!level.getBlockState(lever).is(Blocks.LEVER)) {
				placedLevers.remove(lever);
				miningStarted = false;
			}
			return;
		}
		placedPistons.clear();
		placedLevers.clear();
		state = State.IDLE;
	}

	/** First cached piston that is still a piston and is useless: its head
	 *  direction is blocked by a non-air, non-whitelisted block (cannot
	 *  extend) or no whitelisted block is adjacent anymore. */
	private static BlockPos firstUselessPlacedPiston(Level level) {
		for (BlockPos pos : placedPistons) {
			BlockState s = level.getBlockState(pos);
			if (!s.is(Blocks.PISTON)) {
				continue;
			}
			Direction head = s.getValue(PistonBaseBlock.FACING);
			BlockState headState = level.getBlockState(pos.relative(head));
			boolean headBlocked = !headState.isAir() && !BedrockBreaker.isWhitelisted(headState.getBlock());
			boolean hasBedrockNeighbour = false;
			for (Direction d : Direction.values()) {
				if (BedrockBreaker.isWhitelisted(level.getBlockState(pos.relative(d)).getBlock())) {
					hasBedrockNeighbour = true;
					break;
				}
			}
			if (headBlocked || !hasBedrockNeighbour) {
				return pos;
			}
		}
		return null;
	}

	/** First cached lever that is still present. */
	private static BlockPos firstPlacedLever(Level level) {
		for (BlockPos pos : placedLevers) {
			if (level.getBlockState(pos).is(Blocks.LEVER)) {
				return pos;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Messages
	// ------------------------------------------------------------------

	/** Shows a message above the hotbar (action bar), like vanilla sleep prompts. */
	private static void showActionBar(String message) {
		Component text = Component.literal(message);
		//? if >=26.2 {
		/*Minecraft.getInstance().gui.hud.setOverlayMessage(text, false);*/
		//?} else {
		Minecraft.getInstance().gui.setOverlayMessage(text, false);
		//?}
	}

	/** Direction of a single-axis delta; null when not on one axis. */
	private static Direction directionFromDelta(int dx, int dy, int dz) {
		if (dx != 0) {
			return Direction.fromAxisAndDirection(Direction.Axis.X, dx > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
		}
		if (dy != 0) {
			return Direction.fromAxisAndDirection(Direction.Axis.Y, dy > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
		}
		if (dz != 0) {
			return Direction.fromAxisAndDirection(Direction.Axis.Z, dz > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
		}
		return null;
	}

	// Chat-bar messages were moved to the action bar (showActionBar) to reduce
	// chat spam; kept for reference / future use.
	// private static void sendMessage(LocalPlayer player, String message) {
	// 	//? if >=26.1 {
	// 	player.sendSystemMessage(Component.literal(message));
	// 	//?} else {
	// 	/*player.displayClientMessage(Component.literal(message), false);*/
	// 	//?}
	// }
}
