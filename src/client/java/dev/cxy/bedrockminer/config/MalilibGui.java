package dev.cxy.bedrockminer.config;

//? if >=1.19.4 {

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;

import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * The malilib config screen: toggle the mod / auto mode, edit the block
 * whitelist and set the open-GUI hotkey.
 */
public class MalilibGui extends GuiConfigsBase {

	private boolean synced;

	public MalilibGui(Screen parent) {
		super(10, 50, "bedrock-miner-extra", parent, "bedrock-miner-extra.gui.title");
	}

	@Override
	public void initGui() {
		super.initGui();
		// Read the JSON-backed state once for this screen. Re-running initGui
		// while returning from a child view must not overwrite a key currently
		// being edited in malilib's widgets.
		if (!synced) {
			MalilibConfigs.syncFromBreaker();
			synced = true;
		}
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		return ConfigOptionWrapper.createFor(List.of(
			MalilibConfigs.MOD_ENABLED,
			MalilibConfigs.AUTO_ENABLED,
			MalilibConfigs.ALLOW_STICKY_PISTON,
			MalilibConfigs.BLOCK_WHITELIST,
			MalilibConfigs.OPEN_GUI,
			MalilibConfigs.TOGGLE_AUTO
		));
	}
}

//?}
