package net.dec4234.gravel.client.mixin;

import net.minecraft.client.gui.components.AbstractSliderButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets Gravel read a slider position (0 to 1) and move it the same way a mouse drag does. */
@Mixin(AbstractSliderButton.class)
public interface AbstractSliderButtonAccessor {
	@Accessor("value")
	double gravel$getValue();

	/** Clamps the value, updates the label and applies the value, like a drag. */
	@Invoker("setValue")
	void gravel$setValue(double value);
}
