package dev.cxy.bedrockminer.config;

//? if >=1.19.4 {

import com.google.common.collect.ImmutableList;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;

import dev.cxy.bedrockminer.BedrockBreaker;
import dev.cxy.bedrockminer.AutoBreaker;

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

	public static final ConfigBoolean ALLOW_STICKY_PISTON = new ConfigBoolean(
		"allowStickyPiston", false, "允许自动模式和手动模式使用粘性活塞"
	);

	public static final ConfigStringList BLOCK_WHITELIST = new ConfigStringList(
		"blockWhitelist", ImmutableList.of("minecraft:bedrock"), "可破方块白名单（每行一个方块 ID）"
	);

	public static final ConfigHotkey OPEN_GUI = new ConfigHotkey(
		"openConfigGui", "LEFT_ALT,B", "打开 Bedrock Miner Extra 配置界面的快捷键"
	);

	public static final ConfigHotkey TOGGLE_AUTO = new ConfigHotkey(
		"toggleAuto", "", KeybindSettings.PRESS_ALLOWEXTRA_EMPTY,
		"切换 Bedrock Miner Extra 自动模式的快捷键"
	);

	private static boolean syncing;
	private static final String CONFIG_ID = "bedrock-miner-extra";

	private MalilibConfigs() {
	}

	public static void init() {
		Holder.INSTANCE.register();
	}

	private void register() {
		ConfigManager.getInstance().registerConfigHandler(CONFIG_ID, this);
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
		ALLOW_STICKY_PISTON.setValueChangeCallback(config -> {
			if (!syncing) {
				BedrockBreaker.setStickyPistonAllowed(config.getBooleanValue());
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
		TOGGLE_AUTO.setValueChangeCallback(config -> {
			if (!syncing) {
				BedrockBreaker.setAutoToggleHotkey(config.getStringValue());
				InputEventHandler.getKeybindManager().updateUsedKeys();
			}
		});
		OPEN_GUI.getKeybind().setCallback((action, key) -> {
			GuiBase.openGui(new MalilibGui(null));
			return true;
		});
		TOGGLE_AUTO.getKeybind().setCallback((action, key) -> {
			boolean enabled = BedrockBreaker.toggleAuto();
			AutoBreaker.showActionBar(enabled
				? "Bedrock Miner Extra 自动模式已开启"
				: "Bedrock Miner Extra 自动模式已关闭");
			syncAutoStateFromBreaker();
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
			ALLOW_STICKY_PISTON.setBooleanValue(BedrockBreaker.isStickyPistonAllowed());
			BLOCK_WHITELIST.setStrings(new ArrayList<>(BedrockBreaker.getBlockWhitelist()));
			OPEN_GUI.setValueFromString(BedrockBreaker.getOpenGuiHotkey());
			TOGGLE_AUTO.setValueFromString(BedrockBreaker.getAutoToggleHotkey());
		} finally {
			syncing = false;
		}
		InputEventHandler.getKeybindManager().updateUsedKeys();
	}

	/** Updates only the toggle widgets after a hotkey activation. Rebinding the
	 * key here would reset its pressed-key state and could retrigger on a hold. */
	private static void syncAutoStateFromBreaker() {
		syncing = true;
		try {
			MOD_ENABLED.setBooleanValue(BedrockBreaker.isEnabled());
			AUTO_ENABLED.setBooleanValue(BedrockBreaker.isAutoEnabled());
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
		// The GUI edits malilib's Config objects directly. Persist the final
		// values when malilib commits its config handlers, including hotkeys.
		boolean modEnabled = MOD_ENABLED.getBooleanValue();
		BedrockBreaker.setEnabled(modEnabled);
		BedrockBreaker.setAutoEnabled(modEnabled && AUTO_ENABLED.getBooleanValue());
		BedrockBreaker.setStickyPistonAllowed(ALLOW_STICKY_PISTON.getBooleanValue());
		syncWhitelistToBreaker(BLOCK_WHITELIST);
		BedrockBreaker.setOpenGuiHotkey(OPEN_GUI.getStringValue());
		BedrockBreaker.setAutoToggleHotkey(TOGGLE_AUTO.getStringValue());
		InputEventHandler.getKeybindManager().updateUsedKeys();
	}

	@Override
	public void addKeysToMap(IKeybindManager manager) {
		manager.addKeybindToMap(OPEN_GUI.getKeybind());
		manager.addKeybindToMap(TOGGLE_AUTO.getKeybind());
	}

	@Override
	public void addHotkeys(IKeybindManager manager) {
		manager.addHotkeysForCategory("bedrock-miner-extra", "bedrock-miner-extra.hotkeys.generic", List.of(OPEN_GUI, TOGGLE_AUTO));
	}

	private static final class Holder {
		private static final MalilibConfigs INSTANCE = new MalilibConfigs();
	}
}

//?}
