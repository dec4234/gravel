package net.dec4234.gravel.client.mixin;

import net.dec4234.gravel.Gravel;
import net.dec4234.gravel.client.bridge.ServerConnection;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the text of each disconnect screen: connection failures ("Failed to connect to the server"),
 * login rejections, and kicks. All the other constructors call this one.
 */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin {
	@Inject(method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;"
			+ "Lnet/minecraft/network/DisconnectionDetails;Lnet/minecraft/network/chat/Component;)V", at = @At("RETURN"))
	private void gravel$recordDisconnect(Screen parent, Component title, DisconnectionDetails details,
	                                     Component buttonText, CallbackInfo ci) {
		Component reason = details.reason();
		var d = ServerConnection.disconnected(title.getString(), reason.getString(), reasonKey(reason));
		Gravel.LOGGER.info("Disconnect screen: {}: {}", d.title(), d.reason());
	}

	/** The translation key of the reason. "disconnect.genericReason" only wraps the real reason, so look inside it. */
	private static String reasonKey(Component reason) {
		if (!(reason.getContents() instanceof TranslatableContents t)) {
			return null;
		}
		if (t.getKey().equals("disconnect.genericReason") && t.getArgs().length == 1
				&& t.getArgs()[0] instanceof Component inner && inner.getContents() instanceof TranslatableContents it) {
			return it.getKey();
		}
		return t.getKey();
	}
}
