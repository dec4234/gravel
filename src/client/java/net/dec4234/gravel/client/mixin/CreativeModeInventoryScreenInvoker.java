package net.dec4234.gravel.client.mixin;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets Gravel select a creative tab without clicking it. */
@Mixin(CreativeModeInventoryScreen.class)
public interface CreativeModeInventoryScreenInvoker {
	@Invoker("selectTab")
	void gravel$selectTab(CreativeModeTab tab);
}
