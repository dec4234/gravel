package net.dec4234.gravel.client.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Gravel read the tab list header and footer. */
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
	@Accessor("header")
	Component gravel$getHeader();

	@Accessor("footer")
	Component gravel$getFooter();

	@Accessor("visible")
	boolean gravel$isVisible();
}
