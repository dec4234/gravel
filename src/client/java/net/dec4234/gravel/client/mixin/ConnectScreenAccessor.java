package net.dec4234.gravel.client.mixin;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Gravel read the progress text of the connect screen ("Connecting to the server...", "Logging in..."). */
@Mixin(ConnectScreen.class)
public interface ConnectScreenAccessor {
	@Accessor("status")
	Component gravel$getStatus();
}
