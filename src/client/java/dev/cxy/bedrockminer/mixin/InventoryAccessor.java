package dev.cxy.bedrockminer.mixin;

import net.minecraft.world.entity.player.Inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Hotbar selection: public {@code selected} field up to 1.21.1, private field
 * + {@code setSelectedSlot} in 26.x. The field exists in every supported
 * version, so an accessor works everywhere.
 */
@Mixin(Inventory.class)
public interface InventoryAccessor {

	@Accessor("selected")
	void bedrockMinerExtra$setSelected(int selected);
}
