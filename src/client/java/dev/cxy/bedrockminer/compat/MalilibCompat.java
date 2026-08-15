package dev.cxy.bedrockminer.compat;

//? if >=1.19.4 {

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

import dev.cxy.bedrockminer.BedrockBreaker;

/**
 * Optional malilib integration (26.x only).
 *
 * This class must NOT reference any malilib type directly: it is loaded
 * unconditionally at startup, and a direct reference would pull in
 * MalilibConfigs / MalilibGui (whose static init touches malilib classes) even
 * when malilib is absent, causing a NoClassDefFoundError. Everything is
 * therefore loaded reflectively, only when malilib is actually present.
 */
public final class MalilibCompat {

	private static boolean initialized;

	private MalilibCompat() {
	}

	public static boolean isAvailable() {
		return FabricLoader.getInstance().isModLoaded("malilib");
	}

	public static void init() {
		if (initialized || !isAvailable()) {
			return;
		}
		initialized = true;
		try {
			Class.forName("dev.cxy.bedrockminer.config.MalilibConfigs").getMethod("init").invoke(null);
		} catch (Throwable t) {
			BedrockBreaker.LOGGER.warn("Bedrock Miner Extra: failed to init malilib configs", t);
		}
	}

	public static void openGui() {
		if (!isAvailable()) {
			return;
		}
		try {
			Class<?> guiClass = Class.forName("dev.cxy.bedrockminer.config.MalilibGui");
			Object gui = guiClass.getConstructor(Screen.class).newInstance((Screen) null);
			Class.forName("fi.dy.masa.malilib.gui.GuiBase").getMethod("openGui", Screen.class).invoke(null, gui);
		} catch (Throwable t) {
			BedrockBreaker.LOGGER.warn("Bedrock Miner Extra: failed to open config gui", t);
		}
	}
}

//?}
