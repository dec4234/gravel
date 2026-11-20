package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Chat actions: open the chat screen, send chat or commands, and read received chat. */
public final class ChatActions {
	private ChatActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("open_chat", ChatActions::openChat);
		registry.register("send_chat", ChatActions::sendChat);
		registry.register("read_chat", ChatActions::readChat);
	}

	private static JsonObject openChat(Minecraft mc, JsonObject params) {
		requireInWorld(mc);
		String text = params.has("text") ? params.get("text").getAsString() : "";
		ChatComponent.ChatMethod method = text.startsWith("/")
				? ChatComponent.ChatMethod.COMMAND : ChatComponent.ChatMethod.MESSAGE;
		if (text.isEmpty()) {
			mc.gui.openChatScreen(method);
		} else {
			// The command method already shows the "/" prefix, so do not add it twice.
			String body = method == ChatComponent.ChatMethod.COMMAND ? text.substring(1) : text;
			mc.gui.openChatAndAddText(method, body);
		}
		JsonObject o = new JsonObject();
		o.addProperty("opened", true);
		return o;
	}

	private static JsonObject sendChat(Minecraft mc, JsonObject params) {
		requireInWorld(mc);
		String message = params.has("message") ? params.get("message").getAsString().trim() : "";
		if (message.isEmpty()) {
			throw new IllegalArgumentException("Parameter 'message' is required");
		}
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			throw new IllegalStateException("Not connected to a world");
		}

		long before = ChatLog.latestId();
		boolean isCommand = message.startsWith("/");
		if (isCommand) {
			connection.sendCommand(message.substring(1));
		} else {
			connection.sendChat(message);
		}

		JsonObject o = new JsonObject();
		o.addProperty("sent", message);
		o.addProperty("command", isCommand);
		o.addProperty("chatIdBeforeSend", before);
		return o;
	}

	private static JsonObject readChat(Minecraft mc, JsonObject params) {
		long since = params.has("since_id") ? params.get("since_id").getAsLong() : 0;
		int limit = params.has("limit") ? params.get("limit").getAsInt() : 50;
		boolean actionBar = params.has("include_action_bar") && params.get("include_action_bar").getAsBoolean();
		return ChatLog.read(since, Math.max(1, limit), actionBar);
	}

	private static void requireInWorld(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			throw new IllegalStateException("Not in a world. Join a world first.");
		}
	}
}
