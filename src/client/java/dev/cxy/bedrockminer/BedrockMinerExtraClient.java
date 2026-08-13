package dev.cxy.bedrockminer;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

import java.util.Set;

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
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
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
							.executes(BedrockMinerExtraClient::whitelistAdd)))
					.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
						.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("block", StringArgumentType.word())
							.executes(BedrockMinerExtraClient::whitelistRemove)))
					.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
						.executes(BedrockMinerExtraClient::whitelistList))));
		});
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
		sendChatMessage(player, Component.literal("Bedrock Miner Extra 自动模式已开启！左键点击基岩即可自动破基岩。"));
	}

	private static void sendAutoDisablePrompt(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		sendChatMessage(player, Component.literal("Bedrock Miner Extra 自动模式已关闭！"));
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
		sendChatMessage(player, Component.literal("Bedrock Miner Extra 已启动！"));
		// Integrated server is a singleplayer world; a remote connection means
		// an actual multiplayer server.
		if (client.getSingleplayerServer() == null) {
			sendChatMessage(player, Component.literal("看起来你好像是在服务器使用Bedrock Miner Extra？").withStyle(ChatFormatting.GRAY));
			sendChatMessage(player, Component.literal("在使用本mod前请先征询其他玩家的意见。").withStyle(ChatFormatting.GRAY));
		}
	}

	private static void sendDisablePrompt(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		sendChatMessage(player, Component.literal("Bedrock Miner Extra 已关闭！"));
	}

	private static int whitelistAdd(CommandContext<FabricClientCommandSource> ctx) {
		String id = StringArgumentType.getString(ctx, "block");
		String result = BedrockBreaker.addWhitelistedBlock(id);
		ctx.getSource().sendFeedback(Component.literal("Bedrock Miner Extra: " + result));
		return 1;
	}

	private static int whitelistRemove(CommandContext<FabricClientCommandSource> ctx) {
		String id = StringArgumentType.getString(ctx, "block");
		String result = BedrockBreaker.removeWhitelistedBlock(id);
		ctx.getSource().sendFeedback(Component.literal("Bedrock Miner Extra: " + result));
		return 1;
	}

	private static int whitelistList(CommandContext<FabricClientCommandSource> ctx) {
		Set<String> whitelist = BedrockBreaker.getBlockWhitelist();
		ctx.getSource().sendFeedback(Component.literal("Bedrock Miner Extra whitelist (" + whitelist.size() + "): "
			+ String.join(", ", whitelist)));
		return 1;
	}
}
