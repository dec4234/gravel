package net.dec4234.gravel.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Gravel read the tooltip of a widget without the mouse over it. */
@Mixin(AbstractWidget.class)
public interface AbstractWidgetAccessor {
	@Accessor("tooltip")
	WidgetTooltipHolder gravel$getTooltip();
}
