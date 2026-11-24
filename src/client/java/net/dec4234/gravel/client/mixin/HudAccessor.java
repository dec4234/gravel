package net.dec4234.gravel.client.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Gravel read the title, subtitle and action bar text that the HUD shows. */
@Mixin(Hud.class)
public interface HudAccessor {
	@Accessor("title")
	Component gravel$getTitle();

	@Accessor("subtitle")
	Component gravel$getSubtitle();

	@Accessor("titleTime")
	int gravel$getTitleTime();

	@Accessor("overlayMessageString")
	Component gravel$getOverlayMessage();

	@Accessor("overlayMessageTime")
	int gravel$getOverlayMessageTime();
}
