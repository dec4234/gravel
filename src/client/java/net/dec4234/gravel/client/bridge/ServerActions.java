package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import net.dec4234.gravel.Gravel;
import net.dec4234.gravel.client.mixin.ConnectScreenAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.Locale;

/**
 * Multiplayer actions. Joining a server takes many ticks, so {@code join_server} only schedules the work and
 * returns. The caller polls {@code status} and reads {@code serverConnection.state} until it is {@code joined}
 * (and the player is in the world) or {@code failed}. A failed join has the disconnect screen text.
 */
public final class ServerActions {
	private ServerActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("join_server", ServerActions::joinServer);
		registry.register("read_disconnects", ServerActions::readDisconnects);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			String address = currentServer(client);
			if (address != null) {
				ServerConnection.joined(address);
			}
		});
	}

	/** The address of the multiplayer server the client is on, or null (also null in singleplayer). */
	public static String currentServer(Minecraft mc) {
		ServerData data = mc.getCurrentServer();
		if (data == null || mc.isLocalServer()) {
			return null;
		}
		return data.ip;
	}

	/** Connection info for the status action. */
	public static JsonObject statusJson(Minecraft mc) {
		JsonObject o = new JsonObject();
		o.addProperty("server", currentServer(mc));
		if (mc.gui.screen() instanceof ConnectScreen connect) {
			// The text the connect screen shows, such as "Connecting to the server..." or "Logging in...".
			var status = ((ConnectScreenAccessor) connect).gravel$getStatus();
			o.addProperty("connectStatus", status == null ? null : status.getString());
		}
		o.add("attempt", ServerConnection.attemptJson());
		o.add("lastDisconnect", ServerConnection.lastDisconnectJson());
		o.addProperty("latestDisconnectId", ServerConnection.latestDisconnectId());
		return o;
	}

	private static JsonObject joinServer(Minecraft mc, JsonObject params) {
		String address = params.has("address") ? params.get("address").getAsString().trim() : "";
		if (address.isEmpty()) {
			throw new IllegalArgumentException("Parameter 'address' is required");
		}
		if (!ServerAddress.isValidAddress(address)) {
			throw new IllegalArgumentException("Not a valid server address: '" + address + "'");
		}
		ServerData.ServerPackStatus packStatus = packStatus(params);
		if (!mc.allowsMultiplayer()) {
			throw new IllegalStateException("Multiplayer is not allowed for this account (disabled, or banned)");
		}
		if (mc.gui.screen() instanceof ConnectScreen) {
			throw new IllegalStateException("The client is already connecting to a server");
		}

		JsonObject result = new JsonObject();
		result.addProperty("address", address);
		result.add("session", SessionInfo.json(mc));

		if (address.equals(currentServer(mc)) && mc.player != null) {
			result.addProperty("alreadyOnServer", true);
			return result;
		}

		boolean inWorld = mc.level != null || mc.getSingleplayerServer() != null;
		result.addProperty("wasInWorld", inWorld);
		long attempt = ServerConnection.begin(address);
		result.addProperty("attemptId", attempt);

		// Leave the current world (and save it, for singleplayer) first, then connect on a later tick.
		mc.execute(() -> {
			try {
				if (mc.level != null || mc.getSingleplayerServer() != null) {
					mc.disconnectWithSavingScreen();
				}
				mc.execute(() -> connect(mc, attempt, address, packStatus));
			} catch (Exception e) {
				fail(attempt, "Could not leave the current world", e);
			}
		});

		result.addProperty("scheduled", true);
		return result;
	}

	private static void connect(Minecraft mc, long attempt, String address, ServerData.ServerPackStatus packStatus) {
		try {
			ServerData data = new ServerData(address, address, ServerData.Type.OTHER);
			data.setResourcePackStatus(packStatus);
			ServerConnection.connecting(attempt);
			// Same as the Join Server button: "Back to Server List" on a disconnect screen goes to the server list.
			Screen parent = new JoinMultiplayerScreen(new TitleScreen());
			ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(address), data, false, null);
			if (!(mc.gui.screen() instanceof ConnectScreen)) {
				ServerConnection.error(attempt, "The connect screen did not open");
			}
			Gravel.LOGGER.info("Connecting to server '{}'", address);
		} catch (Exception e) {
			fail(attempt, "Could not connect to '" + address + "'", e);
		}
	}

	private static ServerData.ServerPackStatus packStatus(JsonObject params) {
		String value = params.has("resource_pack") && !params.get("resource_pack").isJsonNull()
				? params.get("resource_pack").getAsString().trim() : "";
		if (value.isEmpty()) {
			return ServerData.ServerPackStatus.PROMPT;
		}
		try {
			return ServerData.ServerPackStatus.valueOf(value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("resource_pack must be prompt, enabled or disabled, not '" + value + "'");
		}
	}

	private static JsonObject readDisconnects(Minecraft mc, JsonObject params) {
		long since = params.has("since_id") ? params.get("since_id").getAsLong() : 0;
		return ServerConnection.read(since);
	}

	private static void fail(long attempt, String message, Exception e) {
		Gravel.LOGGER.error(message, e);
		ServerConnection.error(attempt, message + ": " + e);
	}
}
