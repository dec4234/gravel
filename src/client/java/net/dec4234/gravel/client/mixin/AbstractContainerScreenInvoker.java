package net.dec4234.gravel.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets Gravel click a slot the same way a mouse click does. Screens that override the click
 * (such as the creative inventory) still run their own code.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenInvoker {
	@Invoker("slotClicked")
	void gravel$slotClicked(Slot slot, int slotId, int button, ContainerInput input);
}
