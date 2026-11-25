package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/** read_packets and clear_packets: the packet log of the client connection (see {@link PacketLog}). */
public final class PacketActions {
	static final int DEFAULT_CHANNEL_LIMIT = 50;
	static final int DEFAULT_RECENT_LIMIT = 20;

	private PacketActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("read_packets", PacketActions::read);
		registry.register("clear_packets", PacketActions::clear);
	}

	private static JsonObject read(Minecraft mc, JsonObject params) {
		long sinceId = params.has("since_id") && !params.get("since_id").isJsonNull() ? params.get("since_id").getAsLong() : 0;
		int channelLimit = optInt(params, "limit", DEFAULT_CHANNEL_LIMIT);
		int recentLimit = optInt(params, "recent", DEFAULT_RECENT_LIMIT);
		if (channelLimit < 0 || recentLimit < 0) {
			throw new IllegalArgumentException("limit and recent must be 0 or more.");
		}
		return PacketLog.read(sinceId, filter(params), channelLimit, recentLimit);
	}

	private static JsonObject clear(Minecraft mc, JsonObject params) {
		PacketLog.clear();
		JsonObject o = new JsonObject();
		o.addProperty("cleared", true);
		o.addProperty("latestId", PacketLog.latestId());
		return o;
	}

	/** The filter from the params channel, channel_regex, direction and custom_only. */
	static PacketLog.Filter filter(JsonObject params) {
		String channel = optString(params, "channel");
		boolean regex = params.has("channel_regex") && params.get("channel_regex").getAsBoolean();
		boolean customOnly = params.has("custom_only") && params.get("custom_only").getAsBoolean();
		return new PacketLog.Filter(channel == null || channel.isEmpty() ? null : WaitActions.textMatcher("channel", channel, regex),
				PacketLog.Filter.direction(optString(params, "direction")), customOnly);
	}

	private static int optInt(JsonObject params, String name, int fallback) {
		return params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsInt() : fallback;
	}

	private static String optString(JsonObject params, String name) {
		return params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsString() : null;
	}
}
