package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;

/** The base actions. Add new game actions here (or in new classes) and register them. */
public final class BuiltinActions {
	private BuiltinActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("status", BuiltinActions::status);
		registry.register("shutdown", BuiltinActions::shutdown);
	}

	private static JsonObject status(Minecraft mc, JsonObject params) {
		JsonObject o = new JsonObject();
		LocalPlayer player = mc.player;
		boolean inWorld = player != null && mc.level != null;
		o.addProperty("inWorld", inWorld);
		Screen screen = mc.gui.screen();
		o.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
		o.addProperty("world", WorldActions.currentWorld(mc));
		o.addProperty("worldError", WorldActions.lastError());
		o.add("serverConnection", ServerActions.statusJson(mc));
		o.add("session", SessionInfo.json(mc));
		o.addProperty("latestChatId", ChatLog.latestId());
		o.addProperty("paused", mc.isPaused());
		if (inWorld) {
			JsonObject p = new JsonObject();
			p.addProperty("name", player.getName().getString());
			p.addProperty("x", player.getX());
			p.addProperty("y", player.getY());
			p.addProperty("z", player.getZ());
			p.addProperty("yaw", player.getYRot());
			p.addProperty("pitch", player.getXRot());
			p.addProperty("health", player.getHealth());
			p.addProperty("selectedHotbarSlot", player.getInventory().getSelectedSlot());
			p.add("heldItem", InventoryActions.stackJson(player.getInventory().getSelectedItem()));
			o.add("player", p);
		}
		return o;
	}

	private static JsonObject shutdown(Minecraft mc, JsonObject params) {
		// Let the HTTP response go out first, then stop the game on a later tick.
		mc.execute(mc::stop);
		JsonObject o = new JsonObject();
		o.addProperty("stopping", true);
		return o;
	}
}
