package dev.cxy.bedrockminer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.fabricmc.api.ClientModInitializer;
//? if >=1.19.4 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
//?} else {
/*import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;*/
//?}
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class BedrockMinerExtraClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		BedrockBreaker.loadConfig();
		registerCommands();
		ClientTickEvents.END_CLIENT_TICK.register(BedrockBreaker::onClientTick);
		ClientTickEvents.END_CLIENT_TICK.register(AutoBreaker::tick);
		// Auto mode: left-click a whitelisted block to queue it for breaking.
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (!world.isClientSide()) {
				return InteractionResult.PASS;
			}
			if (!BedrockBreaker.isAutoEnabled()) {
				return InteractionResult.PASS;
			}
			if (BedrockBreaker.isWhitelisted(world.getBlockState(pos).getBlock())) {
				AutoBreaker.enqueue(pos);
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});
		// Show the "started" prompt when joining a world if the mod is on.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (BedrockBreaker.isEnabled()) {
				sendEnablePrompt(client);
			}
		});
		BedrockBreaker.LOGGER.info("Bedrock Miner Extra (client) initialized.");
	}

	private static void registerCommands() {
		//? if >=1.19.4 {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			registerCommand(dispatcher);
		});
		//?} else {
		/*registerCommand(ClientCommandManager.DISPATCHER);*/
		//?}
	}

	private static void registerCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bedrockMinerExtra")
			.executes(BedrockMinerExtraClient::toggleSwitch)
			.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(ctx -> setSwitch(ctx, true)))
			.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(ctx -> setSwitch(ctx, false)))
			.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("auto")
				.executes(BedrockMinerExtraClient::toggleAuto)
				.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(ctx -> setAuto(ctx, true)))
				.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(ctx -> setAuto(ctx, false))))
			.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("blockWhitelist")
				.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
					.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("block", StringArgumentType.word())
						.suggests(BedrockMinerExtraClient::suggestAllBlocks)
						.executes(BedrockMinerExtraClient::whitelistAdd)))
				.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
					.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("block", StringArgumentType.word())
						.suggests(BedrockMinerExtraClient::suggestWhitelist)
						.executes(BedrockMinerExtraClient::whitelistRemove)))
				.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
					.executes(BedrockMinerExtraClient::whitelistList))));
	}

	private static int toggleSwitch(CommandContext<FabricClientCommandSource> ctx) {
		boolean now = BedrockBreaker.toggle();
		if (now) {
			sendEnablePrompt(Minecraft.getInstance());
		} else {
			sendDisablePrompt(Minecraft.getInstance());
		}
		return 1;
	}

	private static int setSwitch(CommandContext<FabricClientCommandSource> ctx, boolean value) {
		BedrockBreaker.setEnabled(value);
		if (value) {
			sendEnablePrompt(Minecraft.getInstance());
		} else {
			sendDisablePrompt(Minecraft.getInstance());
		}
		return 1;
	}

	private static int toggleAuto(CommandContext<FabricClientCommandSource> ctx) {
		boolean now = BedrockBreaker.toggleAuto();
		if (now) {
			sendAutoEnablePrompt(Minecraft.getInstance());
		} else {
			sendAutoDisablePrompt(Minecraft.getInstance());
		}
		return 1;
	}

	private static int setAuto(CommandContext<FabricClientCommandSource> ctx, boolean value) {
		BedrockBreaker.setAutoEnabled(value);
		if (value) {
			sendAutoEnablePrompt(Minecraft.getInstance());
		} else {
			sendAutoDisablePrompt(Minecraft.getInstance());
		}
		return 1;
	}

	private static void sendAutoEnablePrompt(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		sendChatMessage(player, text("Bedrock Miner Extra 自动模式已开启！左键点击基岩即可自动破基岩。"));
	}

	private static void sendAutoDisablePrompt(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		sendChatMessage(player, text("Bedrock Miner Extra 自动模式已关闭！"));
	}

	/** Cross-version literal text: Component.literal on 1.19+, a new
	 *  TextComponent before that. Returns MutableComponent so withStyle etc.
	 *  exist in every version. */
	private static net.minecraft.network.chat.MutableComponent text(String message) {
		//? if >=1.19 {
		return Component.literal(message);
		//?} else {
		/*return new net.minecraft.network.chat.TextComponent(message);*/
		//?}
	}

	/**
	 * Chat messages: 26.x uses {@code sendSystemMessage}, 1.21.11 and older use
	 * {@code displayClientMessage(component, false)} (sendSystemMessage was
	 * removed in 1.21.11 and displayClientMessage removed in 26.x).
	 */
	private static void sendChatMessage(LocalPlayer player, Component message) {
		//? if >=26.1 {
		player.sendSystemMessage(message);
		//?} else {
		/*player.displayClientMessage(message, false);*/
		//?}
	}

	private static void sendEnablePrompt(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		sendChatMessage(player, text("Bedrock Miner Extra 已启动！"));
		// Integrated server is a singleplayer world; a remote connection means
		// an actual multiplayer server.
		if (client.getSingleplayerServer() == null) {
			sendChatMessage(player, text("看起来你好像是在服务器使用Bedrock Miner Extra？").withStyle(ChatFormatting.GRAY));
			sendChatMessage(player, text("在使用本mod前请先征询其他玩家的意见。").withStyle(ChatFormatting.GRAY));
		}
	}

	private static void sendDisablePrompt(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		sendChatMessage(player, text("Bedrock Miner Extra 已关闭！"));
	}

	private static int whitelistAdd(CommandContext<FabricClientCommandSource> ctx) {
		String id = StringArgumentType.getString(ctx, "block");
		String result = BedrockBreaker.addWhitelistedBlock(id);
		ctx.getSource().sendFeedback(text("Bedrock Miner Extra: " + result));
		return 1;
	}

	private static int whitelistRemove(CommandContext<FabricClientCommandSource> ctx) {
		String id = StringArgumentType.getString(ctx, "block");
		String result = BedrockBreaker.removeWhitelistedBlock(id);
		ctx.getSource().sendFeedback(text("Bedrock Miner Extra: " + result));
		return 1;
	}

	private static int whitelistList(CommandContext<FabricClientCommandSource> ctx) {
		Set<String> whitelist = BedrockBreaker.getBlockWhitelist();
		ctx.getSource().sendFeedback(text("Bedrock Miner Extra whitelist (" + whitelist.size() + "): "
			+ String.join(", ", whitelist)));
		return 1;
	}

	/** Tab-completes every registered block id (for {@code blockWhitelist add}).
	 *  Matches the full id ("minecraft:bedrock") and the bare path ("bedrock",
	 *  "bed" as a prefix) so the namespace can be omitted. */
	private static CompletableFuture<Suggestions> suggestAllBlocks(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
		String remaining = builder.getRemainingLowerCase();
		for (var key : BedrockBreaker.blockRegistry().keySet()) {
			String id = key.toString();
			if (matchesBlockId(id, remaining)) {
				builder.suggest(id);
			}
		}
		return builder.buildFuture();
	}

	/** Tab-completes the blocks currently in the whitelist (for {@code blockWhitelist remove}). */
	private static CompletableFuture<Suggestions> suggestWhitelist(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
		String remaining = builder.getRemainingLowerCase();
		for (String id : BedrockBreaker.getBlockWhitelist()) {
			if (matchesBlockId(id, remaining)) {
				builder.suggest(id);
			}
		}
		return builder.buildFuture();
	}

	/** True if {@code id} ("minecraft:bedrock") matches the typed {@code remaining}:
	 *  as a prefix of the full id, or of the part after the namespace. */
	private static boolean matchesBlockId(String id, String remaining) {
		String lower = id.toLowerCase(Locale.ROOT);
		if (lower.startsWith(remaining)) {
			return true;
		}
		int colon = lower.indexOf(':');
		return colon >= 0 && lower.substring(colon + 1).startsWith(remaining);
	}
}
