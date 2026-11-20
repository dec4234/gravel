package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps the most recent chat lines the client received (player chat, system and command feedback,
 * and action bar text). Each line gets an increasing id, so a reader can ask for "everything after id N".
 */
public final class ChatLog {
	private static final int CAPACITY = 1000;

	private record Entry(long id, Instant time, String type, String sender, String text) {
	}

	private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
	private static long nextId = 1;

	private ChatLog() {
	}

	public static void register() {
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, boundType, timestamp) ->
				add("chat", sender == null ? null : sender.name(), message));
		ClientReceiveMessageEvents.GAME.register((message, overlay) ->
				add(overlay ? "actionbar" : "system", null, message));
	}

	private static synchronized void add(String type, String sender, Component message) {
		ENTRIES.addLast(new Entry(nextId++, Instant.now(), type, sender, message.getString()));
		while (ENTRIES.size() > CAPACITY) {
			ENTRIES.removeFirst();
		}
	}

	/** The id of the newest line, or 0 if there is none. */
	public static synchronized long latestId() {
		return nextId - 1;
	}

	/**
	 * @param sinceId only lines with an id greater than this
	 * @param limit   at most this many lines (the newest ones)
	 * @param includeActionBar include action bar (overlay) text
	 */
	public static synchronized JsonObject read(long sinceId, int limit, boolean includeActionBar) {
		JsonArray lines = new JsonArray();
		Deque<Entry> picked = new ArrayDeque<>();
		var it = ENTRIES.descendingIterator();
		while (it.hasNext() && picked.size() < limit) {
			Entry e = it.next();
			if (e.id() <= sinceId) {
				break;
			}
			if (!includeActionBar && e.type().equals("actionbar")) {
				continue;
			}
			picked.addFirst(e);
		}
		for (Entry e : picked) {
			JsonObject o = new JsonObject();
			o.addProperty("id", e.id());
			o.addProperty("time", e.time().toString());
			o.addProperty("type", e.type());
			if (e.sender() != null) {
				o.addProperty("sender", e.sender());
			}
			o.addProperty("text", e.text());
			lines.add(o);
		}
		JsonObject result = new JsonObject();
		result.add("messages", lines);
		result.addProperty("latestId", latestId());
		return result;
	}
}
