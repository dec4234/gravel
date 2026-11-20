package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;

/** Menu screen actions: the pause (ESC) menu, the options menu, and closing the open screen. */
public final class ScreenActions {
	private ScreenActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("open_pause_menu", ScreenActions::openPauseMenu);
		registry.register("open_options", ScreenActions::openOptions);
		registry.register("close_screen", ScreenActions::closeScreen);
	}

	private static JsonObject openPauseMenu(Minecraft mc, JsonObject params) {
		InventoryActions.requirePlayer(mc);
		// Same as pressing ESC with no screen open. In singleplayer this also pauses the game.
		mc.pauseGame(false);
		return screenResult(mc);
	}

	private static JsonObject openOptions(Minecraft mc, JsonObject params) {
		Screen parent = mc.gui.screen();
		if (parent == null) {
			// When the options close, go back where the player would come from: the pause menu or the title screen.
			parent = mc.level != null ? new PauseScreen(true) : new TitleScreen();
		}
		mc.gui.setScreen(new OptionsScreen(parent, mc.options));
		return screenResult(mc);
	}

	private static JsonObject closeScreen(Minecraft mc, JsonObject params) {
		Screen screen = mc.gui.screen();
		JsonObject o = new JsonObject();
		o.addProperty("closed", screen == null ? null : screen.getClass().getSimpleName());
		if (screen != null) {
			// Same as pressing ESC. Some screens go back to their parent screen instead of closing fully.
			screen.onClose();
		}
		Screen now = mc.gui.screen();
		o.addProperty("screen", now == null ? null : now.getClass().getSimpleName());
		return o;
	}

	private static JsonObject screenResult(Minecraft mc) {
		Screen screen = mc.gui.screen();
		JsonObject o = new JsonObject();
		o.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
		o.addProperty("paused", mc.isPaused());
		return o;
	}
}
