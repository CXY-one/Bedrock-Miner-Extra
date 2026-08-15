package dev.cxy.bedrockminer.config;

//? if >=1.19.4 {

import com.google.common.collect.ImmutableList;

import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

import dev.cxy.bedrockminer.BedrockBreaker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * malilib configuration front-end (26.x only). BedrockBreaker's
 * {@code config/bedrock-miner-extra.json} stays the single source of truth:
 * value changes here are written back through BedrockBreaker's setters, and
 * {@link #syncFromBreaker()} pushes the current state back into the widgets
 * whenever the GUI is opened or a config reload happens.
 */
public class MalilibConfigs implements IConfigHandler, IKeybindProvider {

	public static final ConfigBoolean MOD_ENABLED = new ConfigBoolean(
		"modEnabled", true, "Bedrock Miner Extra 总开关"
	);

	public static final ConfigBoolean AUTO_ENABLED = new ConfigBoolean(
		"autoEnabled", false, "自动模式：左键点击基岩自动破基岩"
	);

	public static final ConfigStringList BLOCK_WHITELIST = new ConfigStringList(
		"blockWhitelist", ImmutableList.of("minecraft:bedrock"), "可破方块白名单（每行一个方块 ID）"
	);

	public static final ConfigHotkey OPEN_GUI = new ConfigHotkey(
		"openConfigGui", "LEFT_ALT,B", "打开 Bedrock Miner Extra 配置界面的快捷键"
	);

	private static boolean syncing;

	private MalilibConfigs() {
	}

	public static void init() {
		Holder.INSTANCE.register();
	}

	private void register() {
		MOD_ENABLED.setValueChangeCallback(config -> {
			if (!syncing) {
				BedrockBreaker.setEnabled(config.getBooleanValue());
			}
		});
		AUTO_ENABLED.setValueChangeCallback(config -> {
			if (!syncing) {
				BedrockBreaker.setAutoEnabled(config.getBooleanValue());
			}
		});
		BLOCK_WHITELIST.setValueChangeCallback(config -> {
			if (!syncing) {
				syncWhitelistToBreaker(config);
			}
		});
		OPEN_GUI.setValueChangeCallback(config -> {
			if (!syncing) {
				BedrockBreaker.setOpenGuiHotkey(config.getStringValue());
			}
		});
		OPEN_GUI.getKeybind().setCallback((action, key) -> {
			GuiBase.openGui(new MalilibGui(null));
			return true;
		});

		InputEventHandler.getKeybindManager().registerKeybindProvider(this);
		syncFromBreaker();
	}

	/** Pushes BedrockBreaker's current state into the malilib widgets. */
	public static void syncFromBreaker() {
		syncing = true;
		try {
			MOD_ENABLED.setBooleanValue(BedrockBreaker.isEnabled());
			AUTO_ENABLED.setBooleanValue(BedrockBreaker.isAutoEnabled());
			BLOCK_WHITELIST.setStrings(new ArrayList<>(BedrockBreaker.getBlockWhitelist()));
			OPEN_GUI.setValueFromString(BedrockBreaker.getOpenGuiHotkey());
		} finally {
			syncing = false;
		}
	}

	private static void syncWhitelistToBreaker(ConfigStringList config) {
		Set<String> current = BedrockBreaker.getBlockWhitelist();
		List<String> next = config.getStrings();
		for (String id : new ArrayList<>(current)) {
			if (!next.contains(id)) {
				BedrockBreaker.removeWhitelistedBlock(id);
			}
		}
		for (String id : next) {
			if (!current.contains(id)) {
				BedrockBreaker.addWhitelistedBlock(id);
			}
		}
	}

	@Override
	public void load() {
		syncFromBreaker();
	}

	@Override
	public void save() {
		// Changes are written through BedrockBreaker's setters immediately;
		// there is nothing extra to persist here.
	}

	@Override
	public void addKeysToMap(IKeybindManager manager) {
		manager.addKeybindToMap(OPEN_GUI.getKeybind());
	}

	@Override
	public void addHotkeys(IKeybindManager manager) {
		manager.addHotkeysForCategory("bedrock-miner-extra", "bedrock-miner-extra.hotkeys.generic", List.of(OPEN_GUI));
	}

	private static final class Holder {
		private static final MalilibConfigs INSTANCE = new MalilibConfigs();
	}
}

//?}
