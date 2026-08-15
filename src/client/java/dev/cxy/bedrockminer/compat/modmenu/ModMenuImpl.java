package dev.cxy.bedrockminer.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

/**
 * Mod Menu entry point: exposes "config" in the Mod Menu mod list. Works on
 * every MC version, but the actual screen (a malilib GUI) only exists on 26.x
 * and only when malilib is installed - the screen class is loaded reflectively
 * so older versions (no malilib on the classpath) never touch malilib types.
 */
public class ModMenuImpl implements ModMenuApi {

	@Override
	public ConfigScreenFactory<Screen> getModConfigScreenFactory() {
		return ModMenuImpl::createConfigScreen;
	}

	private static Screen createConfigScreen(Screen parent) {
		if (!FabricLoader.getInstance().isModLoaded("malilib")) {
			return null;
		}
		try {
			Class<?> cls = Class.forName("dev.cxy.bedrockminer.config.MalilibGui");
			return (Screen) cls.getConstructor(Screen.class).newInstance(parent);
		} catch (Exception e) {
			return null;
		}
	}
}
