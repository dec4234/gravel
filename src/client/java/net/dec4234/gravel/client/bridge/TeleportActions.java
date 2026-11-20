package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import java.util.regex.Pattern;

/** Teleport action: move the player in a singleplayer world with the /tp command. */
public final class TeleportActions {
	/** A number, or a relative (~) or local (^) coordinate with an optional number. */
	private static final Pattern COORDINATE = Pattern.compile("[~^]?(-?(\\d+(\\.\\d*)?|\\.\\d+))?");

	private TeleportActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("teleport", TeleportActions::teleport);
	}

	private static JsonObject teleport(Minecraft mc, JsonObject params) {
		LocalPlayer player = mc.player;
		ClientPacketListener connection = mc.getConnection();
		if (player == null || mc.level == null || connection == null) {
			throw new IllegalStateException("Not in a world. Join a world first.");
		}
		if (!mc.hasSingleplayerServer()) {
			throw new IllegalStateException("teleport is only for singleplayer worlds.");
		}

		String command = buildCommand(
				coordinate(params, "x", true), coordinate(params, "y", true), coordinate(params, "z", true),
				coordinate(params, "yaw", false), coordinate(params, "pitch", false));

		JsonObject from = position(player);
		long before = ChatLog.latestId();
		connection.sendCommand(command);

		JsonObject o = new JsonObject();
		o.addProperty("sent", "/" + command);
		o.addProperty("chatIdBeforeSend", before);
		o.add("from", from);
		return o;
	}

	/** The tp command without the "/". If only one of yaw and pitch is given, the other stays the same (~). */
	static String buildCommand(String x, String y, String z, String yaw, String pitch) {
		StringBuilder sb = new StringBuilder("tp @s ").append(x).append(' ').append(y).append(' ').append(z);
		if (yaw != null || pitch != null) {
			sb.append(' ').append(yaw == null ? "~" : yaw).append(' ').append(pitch == null ? "~" : pitch);
		}
		return sb.toString();
	}

	static String coordinate(JsonObject params, String name, boolean required) {
		String value = params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsString().trim() : "";
		if (value.isEmpty()) {
			if (required) {
				throw new IllegalArgumentException("Parameter '" + name + "' is required");
			}
			return null;
		}
		if (!COORDINATE.matcher(value).matches()) {
			throw new IllegalArgumentException("Parameter '" + name + "' must be a number, ~, ~<number>, ^ or ^<number>, not '" + value + "'");
		}
		return value;
	}

	static JsonObject position(LocalPlayer player) {
		JsonObject p = new JsonObject();
		p.addProperty("x", player.getX());
		p.addProperty("y", player.getY());
		p.addProperty("z", player.getZ());
		p.addProperty("yaw", player.getYRot());
		p.addProperty("pitch", player.getXRot());
		return p;
	}
}
