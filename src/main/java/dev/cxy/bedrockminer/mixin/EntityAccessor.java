package dev.cxy.bedrockminer.mixin;

import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.1.2 made {@code Entity.xRot}/{@code yRot} private without a setter for
 * xRot; we need to briefly redirect the player view so the server derives the
 * piston facing from it.
 */
@Mixin(Entity.class)
public interface EntityAccessor {

	@Accessor("xRot")
	void bedrockMinerExtra$setXRot(float xRot);

	@Accessor("yRot")
	void bedrockMinerExtra$setYRot(float yRot);
}
