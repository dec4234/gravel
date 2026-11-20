package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps the state of the last multiplayer join attempt, and the most recent disconnect messages
 * (the text of each "Disconnected" or "Failed to connect to the server" screen).
 *
 * <p>An attempt goes through these states:
 * <ul>
 *   <li>{@code leaving}: Gravel leaves the current world first.</li>
 *   <li>{@code connecting}: the connect screen is open (resolve, connect, log in, configure).</li>
 *   <li>{@code joined}: the client got into the play phase on the server.</li>
 *   <li>{@code failed}: a disconnect screen came before the join, or Gravel could not start the connection.</li>
 *   <li>{@code disconnected}: the client joined, then the server kicked it or the connection was lost.</li>
 * </ul>
 */
public final class ServerConnection {
	private static final int CAPACITY = 50;

	/** One disconnect screen: its title, the reason text, and the translation key of the reason (if it has one). */
	public record Disconnect(long id, Instant time, String address, String title, String reason, String reasonKey,
	                         String hint) {
		JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("time", time.toString());
			o.addProperty("address", address);
			o.addProperty("title", title);
			o.addProperty("reason", reason);
			o.addProperty("reasonKey", reasonKey);
			if (hint != null) {
				o.addProperty("hint", hint);
			}
			return o;
		}
	}

	private static final Deque<Disconnect> DISCONNECTS = new ArrayDeque<>();
	private static long nextDisconnectId = 1;
	private static long nextAttemptId = 1;

	private static long attemptId;
	private static String attemptAddress;
	private static String attemptState;
	private static String attemptError;
	private static Disconnect attemptDisconnect;

	/** The address of the server the client is on or connecting to, so a disconnect can name it. */
	private static String activeAddress;

	private ServerConnection() {
	}

	/** Starts a new attempt in the {@code leaving} state and gives back its id. */
	public static synchronized long begin(String address) {
		attemptId = nextAttemptId++;
		attemptAddress = address;
		attemptState = "leaving";
		attemptError = null;
		attemptDisconnect = null;
		return attemptId;
	}

	/** The connect screen is open for the attempt. */
	public static synchronized void connecting(long id) {
		if (id == attemptId && "leaving".equals(attemptState)) {
			attemptState = "connecting";
			activeAddress = attemptAddress;
		}
	}

	/** Gravel could not start the connection. */
	public static synchronized void error(long id, String message) {
		if (id == attemptId) {
			attemptState = "failed";
			attemptError = message;
		}
	}

	/** The client is in the play phase on a server (not the singleplayer server). */
	public static synchronized void joined(String address) {
		activeAddress = address;
		if ("connecting".equals(attemptState)) {
			attemptState = "joined";
		}
	}

	/** A disconnect screen was made. Called from the DisconnectedScreen mixin. */
	public static synchronized Disconnect disconnected(String title, String reason, String reasonKey) {
		Disconnect d = new Disconnect(nextDisconnectId++, Instant.now(), activeAddress, title, reason, reasonKey,
				hintFor(reasonKey));
		DISCONNECTS.addLast(d);
		while (DISCONNECTS.size() > CAPACITY) {
			DISCONNECTS.removeFirst();
		}
		if ("connecting".equals(attemptState)) {
			attemptState = "failed";
			attemptDisconnect = d;
		} else if ("joined".equals(attemptState)) {
			attemptState = "disconnected";
			attemptDisconnect = d;
		}
		activeAddress = null;
		return d;
	}

	/**
	 * Extra help for reasons that come from the account type. Gravel only supports offline accounts for now,
	 * so an online-mode server rejects the client.
	 */
	static String hintFor(String reasonKey) {
		if (reasonKey == null) {
			return null;
		}
		if (reasonKey.equals("multiplayer.disconnect.unverified_username")
				|| reasonKey.startsWith("disconnect.loginFailedInfo")
				|| reasonKey.equals("disconnect.loginFailed")) {
			return "The server is in online mode, but this client uses an offline account. Join a server with "
					+ "online-mode=false, or start the client with a Microsoft account (Gravel does not support this yet).";
		}
		return null;
	}

	/** Removes all state. Ids keep counting up. For tests. */
	static synchronized void clear() {
		DISCONNECTS.clear();
		attemptId = 0;
		attemptAddress = null;
		attemptState = null;
		attemptError = null;
		attemptDisconnect = null;
		activeAddress = null;
	}

	/** The id of the newest disconnect, or 0 if there is none. */
	public static synchronized long latestDisconnectId() {
		return nextDisconnectId - 1;
	}

	/** The newest disconnect as JSON, or null. */
	public static synchronized JsonObject lastDisconnectJson() {
		return DISCONNECTS.isEmpty() ? null : DISCONNECTS.getLast().toJson();
	}

	/** The last join attempt as JSON, or null if there was none. */
	public static synchronized JsonObject attemptJson() {
		if (attemptId == 0) {
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("id", attemptId);
		o.addProperty("address", attemptAddress);
		o.addProperty("state", attemptState);
		o.addProperty("error", attemptError);
		o.add("disconnect", attemptDisconnect == null ? null : attemptDisconnect.toJson());
		return o;
	}

	/** Disconnects with an id greater than {@code sinceId}, oldest first. */
	public static synchronized JsonObject read(long sinceId) {
		JsonArray list = new JsonArray();
		for (Disconnect d : DISCONNECTS) {
			if (d.id() > sinceId) {
				list.add(d.toJson());
			}
		}
		JsonObject o = new JsonObject();
		o.add("disconnects", list);
		o.addProperty("latestId", latestDisconnectId());
		return o;
	}
}
