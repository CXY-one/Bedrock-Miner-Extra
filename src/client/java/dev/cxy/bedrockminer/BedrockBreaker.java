package dev.cxy.bedrockminer;

import net.fabricmc.loader.api.FabricLoader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;*/
//?}
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.cxy.bedrockminer.mixin.EntityAccessor;
import dev.cxy.bedrockminer.mixin.InventoryAccessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-client implementation of the "headless piston" bedrock-breaking trick
 * (Mojang bug MC-27056), without any requirement for instant mining.
 *
 * Working setup (as tested in game):
 *   - place a piston next to a whitelisted block (bedrock by default), with the
 *     piston HEAD facing air so it can extend freely;
 *   - power the piston with a lever or redstone torch placed NEXT TO the piston
 *     so the piston extends;
 *   - mine the piston at any speed.
 *
 * When the piston is mined, this mod hooks {@code MultiPlayerGameMode.destroyBlock}
 * (which both the normal progress-full path and the instant-mining path go
 * through): it captures the scene and cuts the power first (switch the lever),
 * then - right after the break packet (START/STOP_DESTROY_BLOCK) is sent by
 * startPrediction - it places a fresh piston FACING the whitelisted block. The
 * server processes them in one tick; the vanilla bug then replaces that block
 * with a piston head.
 *
 * Horizontal facings: the server derives the piston facing from the player
 * view, and the view yaw propagates with a one-tick delay (yHeadRot). For
 * horizontal directions we therefore send the rotation packet early while the
 * piston is still being mined; the player's own view is restored after.
 */
public final class BedrockBreaker {

	public static final Logger LOGGER = LoggerFactory.getLogger("bedrock-miner-extra");

	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("bedrock-miner-extra.json");

	private static final List<String> DEFAULT_WHITELIST = List.of("minecraft:bedrock");

	private enum PowerKind {
		LEVER
	}

	private record PowerSource(BlockPos pos, PowerKind kind) {
	}

	/**
	 * @param pistonPos   where the mined piston was (fresh piston goes here)
	 * @param bedrockDir  direction from the piston to the target block; the
	 *                    fresh piston faces this direction
	 * @param power       the power source to cut (lever/torch)
	 */
	private record Scene(BlockPos pistonPos, Direction bedrockDir, PowerSource power) {
	}

	// Captured in the destroyBlock HEAD hook (piston block still present),
	// consumed right after the mining-completion packet is sent. Single
	// player/client, sequential hooks, so a single field is safe.
	private static Scene pendingScene;

	// Whether the mod is enabled (toggled with /bedrockMinerExtra).
	private static boolean enabled = true;

	// Whether auto mode is enabled (toggled with /bedrockMinerExtra auto).
	private static boolean autoEnabled = false;

	// Hotkey that opens the (malilib) config GUI; persisted so malilib can
	// restore it. Only meaningful when malilib is present (26.x).
	private static String openGuiHotkey = "LEFT_ALT,B";

	// Blocks that can be broken with the trick (default: bedrock only).
	private static final Set<String> blockWhitelist = new LinkedHashSet<>(DEFAULT_WHITELIST);

	// Auto mode knows exactly which bedrock it targets (targetPos - pistonPos);
	// capture() prefers this direction over its own vertical-first search, so
	// the replacement piston faces the intended bedrock, not a neighbouring one.
	private static Direction expectedBedrockDir;

	public static void setExpectedBedrockDir(Direction dir) {
		expectedBedrockDir = dir;
	}

	// True once we sent the early rotation packet for the current mining
	// (horizontal facing fix); reset when the mining completes.
	private static boolean earlyRotSent = false;
	private static BlockPos earlyRotPos;

	// True while we cut the lever power inside destroyBlock. The lever
	// useItemOn itself goes through startPrediction; its RETURN must NOT
	// trigger the fresh-piston placement (that only runs after the actual
	// break packet START/STOP has been sent).
	private static boolean inCutPower = false;

	// Fresh-piston protection: after we place the replacement piston, vanilla
	// may keep re-mining the same spot (instant-mining path re-runs
	// startDestroyBlock because destroyBlockPos was never set; the slow path
	// re-accumulates progress a few ticks later). We block that for a short
	// window so the fresh piston is not mined away. Wall clock, not tick-based,
	// so it works identically across versions.
	private static BlockPos protectedPos;
	private static long protectedUntilMs;

	private BedrockBreaker() {
	}

	// ------------------------------------------------------------------
	// Enable/disable switch
	// ------------------------------------------------------------------

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean value) {
		enabled = value;
		if (!value) {
			// Turning the mod off also turns auto mode off: auto mode cannot
			// run without the manual headless flow (the destroyBlock hooks
			// cut the power and place the fresh piston).
			autoEnabled = false;
		}
		saveConfig();
		LOGGER.info("Bedrock Miner Extra: {}", enabled ? "ON" : "OFF");
	}

	public static boolean toggle() {
		setEnabled(!enabled);
		return enabled;
	}

	// ------------------------------------------------------------------
	// Auto mode switch
	// ------------------------------------------------------------------

	public static boolean isAutoEnabled() {
		return autoEnabled;
	}

	public static void setAutoEnabled(boolean value) {
		autoEnabled = value;
		if (value) {
			// Auto mode relies on the manual headless flow (the destroyBlock
			// hooks cut the power and place the fresh piston), so keep the
			// main switch on while auto mode is on.
			enabled = true;
		}
		saveConfig();
		LOGGER.info("Bedrock Miner Extra: auto mode {}", autoEnabled ? "ON" : "OFF");
	}

	public static boolean toggleAuto() {
		setAutoEnabled(!autoEnabled);
		return autoEnabled;
	}

	// ------------------------------------------------------------------
	// Config GUI hotkey
	// ------------------------------------------------------------------

	public static String getOpenGuiHotkey() {
		return openGuiHotkey;
	}

	public static void setOpenGuiHotkey(String value) {
		openGuiHotkey = value;
		saveConfig();
	}

	// ------------------------------------------------------------------
	// Block whitelist
	// ------------------------------------------------------------------

	public static Set<String> getBlockWhitelist() {
		return new LinkedHashSet<>(blockWhitelist);
	}

	/** Returns a message describing the outcome, empty on success. */
	public static String addWhitelistedBlock(String id) {
		String normalized = normalizeBlockId(id);
		if (normalized == null) {
			return "Unknown block: " + id;
		}
		if (!blockWhitelist.add(normalized)) {
			return "Already in whitelist: " + normalized;
		}
		saveConfig();
		return "Added " + normalized + " to whitelist";
	}

	/** Returns a message describing the outcome, empty on success. */
	public static String removeWhitelistedBlock(String id) {
		String normalized = normalizeBlockId(id);
		if (normalized == null) {
			return "Unknown block: " + id;
		}
		if (!blockWhitelist.remove(normalized)) {
			return "Not in whitelist: " + normalized;
		}
		saveConfig();
		return "Removed " + normalized + " from whitelist";
	}

	private static String normalizeBlockId(String id) {
		//? if >=1.21.11 {
		Identifier loc = id.indexOf(':') >= 0
			? Identifier.tryParse(id)
			: Identifier.fromNamespaceAndPath("minecraft", id);
		//?} else if >=1.21 {
		/*ResourceLocation loc = id.indexOf(':') >= 0
			? ResourceLocation.tryParse(id)
			: ResourceLocation.fromNamespaceAndPath("minecraft", id);*/
		//?} else {
		/*ResourceLocation loc = id.indexOf(':') >= 0
			? ResourceLocation.tryParse(id)
			: new ResourceLocation("minecraft", id);*/
		//?}
		if (loc == null || !blockRegistry().containsKey(loc)) {
			return null;
		}
		return loc.toString();
	}

	public static boolean isWhitelisted(Block block) {
		return blockWhitelist.contains(blockRegistry().getKey(block).toString());
	}

	/** The block registry (BuiltInRegistries.BLOCK on every supported version). */
	public static net.minecraft.core.Registry<net.minecraft.world.level.block.Block> blockRegistry() {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK;
	}

	// ------------------------------------------------------------------
	// Config persistence
	// ------------------------------------------------------------------

	public static void loadConfig() {
		try {
			if (Files.exists(CONFIG_PATH)) {
				JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH)).getAsJsonObject();
				if (obj.has("enabled")) {
					enabled = obj.get("enabled").getAsBoolean();
				}
				if (obj.has("autoEnabled")) {
					autoEnabled = obj.get("autoEnabled").getAsBoolean();
				}
				if (obj.has("openGuiHotkey")) {
					openGuiHotkey = obj.get("openGuiHotkey").getAsString();
				}
				if (obj.has("blockWhitelist") && obj.get("blockWhitelist").isJsonArray()) {
					blockWhitelist.clear();
					for (JsonElement e : obj.getAsJsonArray("blockWhitelist")) {
						String id = e.getAsString();
						if (normalizeBlockId(id) != null) {
							blockWhitelist.add(id);
						}
					}
					if (blockWhitelist.isEmpty()) {
						blockWhitelist.addAll(DEFAULT_WHITELIST);
					}
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Bedrock Miner Extra: failed to load config: {}", e.toString());
		}
	}

	private static void saveConfig() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			JsonObject obj = new JsonObject();
			obj.addProperty("enabled", enabled);
			obj.addProperty("autoEnabled", autoEnabled);
			obj.addProperty("openGuiHotkey", openGuiHotkey);
			JsonArray arr = new JsonArray();
			for (String id : blockWhitelist) {
				arr.add(id);
			}
			obj.add("blockWhitelist", arr);
			Files.writeString(CONFIG_PATH, obj.toString());
		} catch (Exception e) {
			LOGGER.warn("Bedrock Miner Extra: failed to save config: {}", e.toString());
		}
	}

	// ------------------------------------------------------------------
	// Hooks invoked from MultiPlayerGameModeMixin
	// ------------------------------------------------------------------

	/**
	 * Called at the HEAD of {@code MultiPlayerGameMode.destroyBlock}. Both the
	 * slow path (progress full in continueDestroyBlock) and the instant-mining
	 * path (startDestroyBlock's insta-mine special case) go through
	 * destroyBlock, so this is the single place where we capture the scene AND
	 * cut the power FIRST - the headless-piston trick needs the piston to be
	 * mined while un-powered but still extended.
	 *
	 * The lever useItemOn itself goes through startPrediction; its RETURN is
	 * guarded by {@link #inCutPower}, so the fresh-piston placement only runs
	 * after the actual break packet (START/STOP) has been sent.
	 */
	public static void onBlockBreakStarted(BlockPos pos) {
		if (!enabled) {
			pendingScene = null;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Level level = mc.level;
		if (level == null) {
			pendingScene = null;
			return;
		}
		// Already handling a scene (defensive; destroyBlock is not re-entered
		// within one mining in practice).
		if (pendingScene != null) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (state.is(Blocks.PISTON)) {
			LOGGER.info("Bedrock Miner Extra: mined piston detected at {} (state {})", pos, state);
		}
		Scene scene = capture(level, pos, state, true);
		if (scene != null) {
			pendingScene = scene;
			earlyRotSent = false;
			inCutPower = true;
			try {
				cutPower(mc, scene);
			} finally {
				inCutPower = false;
			}
		}
	}

	/**
	 * Called at the RETURN of {@code MultiPlayerGameMode.startPrediction}
	 * (break packet already sent). Skips the nested startPrediction triggered
	 * by the lever cut-off inside destroyBlock.
	 */
	public static void onPredictionPacketSent() {
		Scene scene = pendingScene;
		if (scene == null || inCutPower || !enabled) {
			return;
		}
		pendingScene = null;
		placeFreshPiston(scene);
	}

	/**
	 * Client tick: while aiming at a breakable piston with a horizontal bedrock
	 * neighbour, KEEP re-sending the rotation every tick so the server's
	 * yHeadRot is (almost) always up to date when the piston gets mined. Instant
	 * mining has no tick gap, and the server's yHeadRot lags one tick, so the
	 * rotation must be continuously re-sent while aiming. The view is restored
	 * once the crosshair leaves the piston.
	 */
	public static void onClientTick(Minecraft mc) {
		if (!enabled || mc.player == null || mc.gameMode == null || mc.level == null) {
			return;
		}
		// Auto mode drives its own rotation (MINE_ROTATE); don't interfere.
		if (AutoBreaker.isBusy()) {
			return;
		}
		HitResult hit = mc.hitResult;
		if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
			restoreViewIfNeeded(mc);
			return;
		}
		BlockPos pos = ((BlockHitResult) hit).getBlockPos();
		Scene scene = capture(mc.level, pos, mc.level.getBlockState(pos), false);
		if (scene == null || scene.bedrockDir().getAxis() == Direction.Axis.Y) {
			restoreViewIfNeeded(mc);
			return;
		}
		float[] yp = getYawPitch(scene.bedrockDir());
		mc.player.connection.send(rotPacket(yp[0], yp[1], BedrockBreaker.playerOnGround(mc.player)));
		earlyRotSent = true;
		earlyRotPos = pos;
	}

	/** Restores the server-side view once the player stops aiming at the piston. */
	private static void restoreViewIfNeeded(Minecraft mc) {
		if (earlyRotSent) {
			mc.player.connection.send(rotPacket(mc.player.getYRot(), mc.player.getXRot(), BedrockBreaker.playerOnGround(mc.player)));
			earlyRotSent = false;
			earlyRotPos = null;
		}
	}

	// ------------------------------------------------------------------
	// Trigger detection
	// ------------------------------------------------------------------

	private static Scene capture(Level level, BlockPos pos, BlockState state, boolean log) {
		if (!state.is(Blocks.PISTON)) {
			return null;
		}

		Direction facing = state.getValue(PistonBaseBlock.FACING);

		// The piston must be extended (head out). An extended piston was not
		// blocked by the target; the headless-piston trick requires this state.
		if (!state.getValue(PistonBaseBlock.EXTENDED)) {
			if (log) {
				LOGGER.info("Bedrock Miner Extra: piston at {} is not extended", pos);
			}
			return null;
		}

		// Find a whitelisted neighbour (fresh piston faces it). Auto mode
		// supplies the exact direction to the target bedrock; otherwise the
		// vertical neighbours are preferred (manual-mode convention).
		Direction targetDir = null;
		if (expectedBedrockDir != null && expectedBedrockDir != facing
			&& isWhitelisted(level.getBlockState(pos.relative(expectedBedrockDir)).getBlock())) {
			targetDir = expectedBedrockDir;
		} else {
			for (Direction dir : Direction.values()) {
				if (dir == facing) {
					continue;
				}
				if (isWhitelisted(level.getBlockState(pos.relative(dir)).getBlock())) {
					if (dir == Direction.UP || dir == Direction.DOWN) {
						targetDir = dir;
						break;
					}
					if (targetDir == null) {
						targetDir = dir;
					}
				}
			}
		}
		if (targetDir == null) {
			if (log) {
				LOGGER.info("Bedrock Miner Extra: no whitelisted block adjacent to piston at {} (facing {})", pos, facing);
			}
			return null;
		}

		// The piston must currently be powered (i.e. extended).
		if (!level.hasNeighborSignal(pos)) {
			if (log) {
				LOGGER.info("Bedrock Miner Extra: piston at {} is not powered", pos);
			}
			return null;
		}

		// Find a lever / redstone torch nearby to cut the power with.
		PowerSource power = findPowerSource(level, pos);
		if (power == null) {
			if (log) {
				LOGGER.info("Bedrock Miner Extra: no powered lever within 2 blocks of piston at {}", pos);
			}
			return null;
		}

		return new Scene(pos, targetDir, power);
	}

	/**
	 * Looks within a 2-block manhattan range for a powered lever or a lit
	 * redstone torch (placed next to the piston, not necessarily attached).
	 */
	private static PowerSource findPowerSource(Level level, BlockPos pistonPos) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) {
						continue;
					}
					if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 2) {
						continue;
					}
					BlockPos p = pistonPos.offset(dx, dy, dz);
					BlockState s = level.getBlockState(p);
					if (s.getBlock() instanceof LeverBlock lever) {
						if (s.getValue(LeverBlock.POWERED)) {
							return new PowerSource(p, PowerKind.LEVER);
						}
					}
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Packet sequence
	// ------------------------------------------------------------------

	/** Cuts the power (lever / torch) before the piston is mined. */
	private static void cutPower(Minecraft mc, Scene scene) {
		LocalPlayer player = mc.player;
		MultiPlayerGameMode gameMode = mc.gameMode;
		if (player == null || gameMode == null) {
			return;
		}
		useItemOn(gameMode, player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(scene.power().pos()), Direction.UP, scene.power().pos(), false));
			LOGGER.info("Bedrock Miner Extra: cut power at {} (LEVER)", scene.power().pos());
	}

	/** Places the fresh piston facing the target, after the piston was mined. */
	private static void placeFreshPiston(Scene scene) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		MultiPlayerGameMode gameMode = mc.gameMode;
		Level level = mc.level;
		if (player == null || gameMode == null || level == null) {
			return;
		}

		// Defensive: the piston spot must already be empty (client prediction
		// removed it in destroyBlock).
		if (!level.getBlockState(scene.pistonPos()).isAir()) {
			return;
		}

			LOGGER.info("Bedrock Miner Extra: placing fresh piston at {} facing {}", scene.pistonPos(), scene.bedrockDir());

		// Prefer a piston in the hotbar (switch to its slot), fall back to a
		// piston in the offhand (no hotbar switch needed).
		InteractionHand hand = pistonHand(player);
		if (hand == null) {
			LOGGER.warn("Bedrock Miner Extra: no piston in hotbar or offhand, cannot place replacement");
			return;
		}

		placePiston(gameMode, player, scene, hand);
		// Block vanilla from re-mining the fresh piston for a short window.
		protectFreshPiston(scene.pistonPos());
	}

	/**
	 * Records that the freshly placed piston must not be re-mined by vanilla
	 * for the next 100 ms (2 ticks) — enough to block the instant-mining path's
	 * re-run of startDestroyBlock on the next frame.
	 */
	private static void protectFreshPiston(BlockPos pos) {
		protectedPos = pos;
		protectedUntilMs = System.currentTimeMillis() + 100L;
	}

	/** True while the fresh piston at pos is protected from vanilla re-mining. */
	public static boolean isProtected(BlockPos pos) {
		return protectedPos != null && protectedPos.equals(pos) && System.currentTimeMillis() < protectedUntilMs;
	}

	/**
	 * Selects a piston for the replacement: hotbar slot (switches to it) or the
	 * offhand slot 40 (no hotbar switch). Returns null if the player has no
	 * piston in either.
	 */
	private static InteractionHand pistonHand(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (inventory.getItem(i).is(Blocks.PISTON.asItem())) {
				((InventoryAccessor) inventory).bedrockMinerExtra$setSelected(i);
				player.connection.send(new ServerboundSetCarriedItemPacket(i));
				return InteractionHand.MAIN_HAND;
			}
		}
		if (inventory.getItem(40).is(Blocks.PISTON.asItem())) {
			return InteractionHand.OFF_HAND;
		}
		return null;
	}

	private static void placePiston(MultiPlayerGameMode gameMode, LocalPlayer player, Scene scene, InteractionHand hand) {
		Direction facing = scene.bedrockDir();
		BlockPos pos = scene.pistonPos();

		// Click the target face: hit position is the target block, and the
		// placement happens at the piston spot.
		BlockPos hitPos = pos.relative(facing.getOpposite());
		Vec3 hitVec = Vec3.atCenterOf(hitPos).add(facing.getStepX() * 0.5, facing.getStepY() * 0.5, facing.getStepZ() * 0.5);
		BlockHitResult hitResult = new BlockHitResult(hitVec, facing, pos, false);

		// Piston facing is derived from the player view
		// (PistonBaseBlock: FACING = getNearestLookingDirection().getOpposite()).
		float[] yp = getYawPitch(facing);
		float yaw = yp[0];
		float pitch = yp[1];

		EntityAccessor accessor = (EntityAccessor) player;
		float oldXRot = player.getXRot();
		float oldYRot = player.getYRot();
		float oldYHeadRot = player.yHeadRot;
		try {
			accessor.bedrockMinerExtra$setXRot(pitch);
			accessor.bedrockMinerExtra$setYRot(yaw);
			player.yHeadRot = yaw;
			player.connection.send(rotPacket(yaw, pitch, BedrockBreaker.playerOnGround(player)));
			useItemOn(gameMode, player, hand, hitResult);
		} finally {
			// Restore the LOCAL view only. The server-side view is left at the
			// bedrock-facing rotation on purpose: the server's yHeadRot lags
			// one tick, so restoring it here would make the NEXT placement use
			// the wrong facing. The client tick keeps re-sending the rotation
			// while aiming at the piston (or during auto-mining) and restores
			// the real view when done.
			accessor.bedrockMinerExtra$setXRot(oldXRot);
			accessor.bedrockMinerExtra$setYRot(oldYRot);
			player.yHeadRot = oldYHeadRot;
		}
	}

	/**
	 * Builds the rotation packet; the constructor gained a 4th argument in
	 * 1.21.11 (horizontalCollision), before that it was (yaw, pitch, onGround).
	 */
	/** Whether the player is on the ground: onGround() on every version from
	 *  1.20 on (yarn 1.20+ and mojmap 26.x both name the method onGround()),
	 *  isOnGround() on 1.19.4 (mojmap, where onGround is a protected field). */
	public static boolean playerOnGround(LocalPlayer player) {
		//? if >=1.20 {
		return player.onGround();
		//?} else {
		/*return player.isOnGround();*/
		//?}
	}

	/** Item use (the ClientLevel argument was dropped in 1.19.3; older versions
	 *  are not supported). */
	private static void useItemOn(MultiPlayerGameMode gameMode, LocalPlayer player, InteractionHand hand, BlockHitResult hitResult) {
		gameMode.useItemOn(player, hand, hitResult);
	}

	public static ServerboundMovePlayerPacket rotPacket(float yaw, float pitch, boolean onGround) {
		//? if >=1.21.11 {
		return new ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, false);
		//?} else {
		/*return new ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround);*/
		//?}
	}

	/** Returns {yaw, pitch} that make the server place a piston with the given facing. */
	public static float[] getYawPitch(Direction direction) {
		float yaw = switch (direction) {
			case SOUTH -> 180.0F;
			case EAST -> 90.0F;
			case NORTH -> 0.0F;
			case WEST -> -90.0F;
			default -> 0.0F;
		};
		float pitch = switch (direction) {
			case UP -> 90.0F;
			case DOWN -> -90.0F;
			default -> 0.0F;
		};
		return new float[] { yaw, pitch };
	}
}
