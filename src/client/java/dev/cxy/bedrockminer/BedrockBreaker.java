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
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.phys.Vec3;

import dev.cxy.bedrockminer.mixin.EntityAccessor;
import dev.cxy.bedrockminer.mixin.InventoryAccessor;
import dev.cxy.bedrockminer.mixin.MultiPlayerGameModeAccessor;

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
 * When the piston is mined, this mod sends - around the mining-completion
 * packet - the following, so the server handles them in one tick: cut the power
 * first (switch the lever / break the torch), then place a fresh piston FACING
 * the whitelisted block. The vanilla bug then replaces that block with a piston
 * head.
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

	// Blocks that can be broken with the trick (default: bedrock only).
	private static final Set<String> blockWhitelist = new LinkedHashSet<>(DEFAULT_WHITELIST);

	// True once we sent the early rotation packet for the current mining
	// (horizontal facing fix); reset when the mining completes.
	private static boolean earlyRotSent = false;

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
		saveConfig();
		LOGGER.info("Bedrock Miner Extra: {}", enabled ? "ON" : "OFF");
	}

	public static boolean toggle() {
		setEnabled(!enabled);
		return enabled;
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
		if (loc == null || !BuiltInRegistries.BLOCK.containsKey(loc)) {
			return null;
		}
		return loc.toString();
	}

	private static boolean isWhitelisted(Block block) {
		return blockWhitelist.contains(BuiltInRegistries.BLOCK.getKey(block).toString());
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

	/** Called at the HEAD of {@code MultiPlayerGameMode.destroyBlock}. */
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
		BlockState state = level.getBlockState(pos);
		if (state.is(Blocks.PISTON)) {
			LOGGER.info("Bedrock Miner Extra: mined piston detected at {} (state {})", pos, state);
		}
		Scene scene = capture(level, pos, state, true);
		if (scene != null) {
			pendingScene = scene;
		}
	}

	/**
	 * Called right before the mining-completion packet is sent (progress full).
	 * Captures the scene while the piston is still present, cuts the power
	 * FIRST so the piston is un-powered (retracting) when it gets mined - the
	 * headless-piston trick needs the piston to be mined in that state.
	 */
	public static void onBeforeMiningCompleted(BlockPos pos) {
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
		Scene scene = capture(level, pos, level.getBlockState(pos), true);
		if (scene != null) {
			// The lever is switched off BEFORE the piston is mined (useItemOn
			// does not touch the server destroy progress, and the piston must
			// be un-powered for the headless-piston trick).
			cutPower(mc, scene);
		}
		pendingScene = scene;
		earlyRotSent = false;
	}

	/** Called at the RETURN of {@code MultiPlayerGameMode.startPrediction} (mining packet already sent). */
	public static void onPredictionPacketSent() {
		Scene scene = pendingScene;
		pendingScene = null;
		if (scene != null && enabled) {
			placeFreshPiston(scene);
		}
	}

	/** Client tick: for horizontal facings, send the rotation early so the
	 *  server view yaw has time to propagate before the piston is placed. */
	public static void onClientTick(Minecraft mc) {
		if (!enabled || mc.player == null || mc.gameMode == null || mc.level == null) {
			return;
		}
		MultiPlayerGameMode gameMode = mc.gameMode;
		BlockPos target = ((MultiPlayerGameModeAccessor) gameMode).bedrockMinerExtra$getDestroyBlockPos();
		if (target == null || target.getY() < 0) {
			return;
		}
		BlockState state = mc.level.getBlockState(target);
		Scene scene = capture(mc.level, target, state, false);
		if (scene == null || scene.bedrockDir().getAxis() == Direction.Axis.Y) {
			return;
		}
		float progress = ((MultiPlayerGameModeAccessor) gameMode).bedrockMinerExtra$getDestroyProgress();
		if (progress < 0.5F || earlyRotSent) {
			return;
		}
		float[] yp = getYawPitch(scene.bedrockDir());
		mc.player.connection.send(rotPacket(yp[0], yp[1], mc.player.onGround()));
		earlyRotSent = true;
		LOGGER.info("Bedrock Miner Extra: early rotation sent for horizontal facing {}", scene.bedrockDir());
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

		// Find a whitelisted neighbour (fresh piston faces it). Vertical
		// neighbours are preferred.
		Direction targetDir = null;
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
		gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
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

		// Switch to a piston in the hotbar.
		if (!switchToPiston(player)) {
			LOGGER.warn("Bedrock Miner Extra: no piston in hotbar, cannot place replacement");
			return;
		}

		placePiston(gameMode, player, scene);
	}

	private static boolean switchToPiston(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		int slot = -1;
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (inventory.getItem(i).is(Blocks.PISTON.asItem())) {
				slot = i;
				break;
			}
		}
		if (slot < 0) {
			return false;
		}
		((InventoryAccessor) inventory).bedrockMinerExtra$setSelected(slot);
		player.connection.send(new ServerboundSetCarriedItemPacket(slot));
		return true;
	}

	private static void placePiston(MultiPlayerGameMode gameMode, LocalPlayer player, Scene scene) {
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
			player.connection.send(rotPacket(yaw, pitch, player.onGround()));
			gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
		} finally {
			accessor.bedrockMinerExtra$setXRot(oldXRot);
			accessor.bedrockMinerExtra$setYRot(oldYRot);
			player.yHeadRot = oldYHeadRot;
			// Restore the server-side view so the player's actual view is kept.
			player.connection.send(rotPacket(oldYRot, oldXRot, player.onGround()));
		}
	}

	/**
	 * Builds the rotation packet; the constructor gained a 4th argument in
	 * 1.21.11 (horizontalCollision), before that it was (yaw, pitch, onGround).
	 */
	private static ServerboundMovePlayerPacket rotPacket(float yaw, float pitch, boolean onGround) {
		//? if >=1.21.11 {
		return new ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, false);
		//?} else {
		/*return new ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround);*/
		//?}
	}

	/** Returns {yaw, pitch} that make the server place a piston with the given facing. */
	private static float[] getYawPitch(Direction direction) {
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
