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

	public MalilibGui(Screen parent) {
		super(10, 50, "bedrock-miner-extra", parent, "bedrock-miner-extra.gui.title");
	}

	@Override
	public void initGui() {
		super.initGui();
		// Re-sync from BedrockBreaker so command-line changes show up here.
		MalilibConfigs.syncFromBreaker();
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		return ConfigOptionWrapper.createFor(List.of(
			MalilibConfigs.MOD_ENABLED,
			MalilibConfigs.AUTO_ENABLED,
			MalilibConfigs.BLOCK_WHITELIST,
			MalilibConfigs.OPEN_GUI
		));
	}
}

//?}
