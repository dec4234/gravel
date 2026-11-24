package net.dec4234.gravel.client.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets Gravel move the game's mouse position (in window pixels). Screens draw hover effects and tooltips at this
 * position, so a click or hover from Gravel looks the same as one from the real mouse.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Accessor("xpos")
	void gravel$setXpos(double x);

	@Accessor("ypos")
	void gravel$setYpos(double y);
}
