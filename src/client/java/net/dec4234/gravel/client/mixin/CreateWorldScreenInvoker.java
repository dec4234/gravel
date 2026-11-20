package net.dec4234.gravel.client.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets Gravel press "Create New World" without clicking the button. */
@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenInvoker {
	@Invoker("onCreate")
	void gravel$onCreate();
}
