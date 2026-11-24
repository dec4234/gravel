package net.dec4234.gravel.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets Gravel do a left or right click the same way as the game does for the mouse buttons. */
@Mixin(Minecraft.class)
public interface MinecraftInvoker {
	@Invoker("startAttack")
	boolean gravel$startAttack();

	@Invoker("startUseItem")
	void gravel$startUseItem();

	@Accessor("missTime")
	int gravel$getMissTime();
}
