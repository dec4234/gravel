package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * wait_for: waits for a number of client ticks, or until conditions are true (a chat line matches, a screen is
 * open, the player is in a world, the player is near a position). The checks run at the end of each client tick.
 */
public final class WaitActions {
	/** The bridge stops each action after 30 s, so the wait and the settle time must end before that. */
	static final int MAX_TIMEOUT_MS = 25_000;
	static final int DEFAULT_TIMEOUT_MS = 10_000;
	static final int MAX_TICKS = 500;
	static final int MAX_SETTLE_TICKS = 40;
	private static final double DEFAULT_RADIUS = 1.0;

	/** Waits that are not done. Only the client thread uses this list. */
	private static final List<Waiter> WAITERS = new ArrayList<>();

	private WaitActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.registerAsync("wait_for", WaitActions::waitFor);
		ClientTickEvents.END_CLIENT_TICK.register(WaitActions::tick);
	}

	private static void tick(Minecraft mc) {
		if (WAITERS.isEmpty()) {
			return;
		}
		for (Waiter w : List.copyOf(WAITERS)) {
			w.ticks++;
			if (w.step(mc)) {
				WAITERS.remove(w);
			}
		}
	}

	private static CompletableFuture<JsonObject> waitFor(Minecraft mc, JsonObject params) {
		Conditions c = Conditions.parse(params, ChatLog.latestId());
		Waiter w = new Waiter(c);
		if (!w.step(mc)) {
			WAITERS.add(w);
		}
		return w.done;
	}

	// ---------------------------------------------------------------- conditions

	/** What to wait for. Parsed from the action params. */
	record Conditions(Integer ticks, int timeoutMs, int settleTicks,
					  Predicate<String> chat, String chatText, long sinceId, boolean includeActionBar,
					  String screen, Boolean inWorld,
					  Double x, Double y, Double z, double radius) {

		boolean hasChat() {
			return chatText != null;
		}

		boolean hasPosition() {
			return x != null || y != null || z != null;
		}

		boolean hasCondition() {
			return hasChat() || screen != null || inWorld != null || hasPosition();
		}

		static Conditions parse(JsonObject params, long latestChatId) {
			Integer ticks = optInt(params, "ticks");
			if (ticks != null && (ticks < 0 || ticks > MAX_TICKS)) {
				throw new IllegalArgumentException("ticks must be 0-" + MAX_TICKS);
			}
			Integer timeout = optInt(params, "timeout_ms");
			if (timeout != null && (timeout < 0 || timeout > MAX_TIMEOUT_MS)) {
				throw new IllegalArgumentException("timeout_ms must be 0-" + MAX_TIMEOUT_MS);
			}
			// A wait for ticks only must not stop early because of the default time limit.
			int timeoutMs = timeout != null ? timeout : ticks != null ? MAX_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;
			Integer settle = optInt(params, "settle_ticks");
			if (settle != null && (settle < 0 || settle > MAX_SETTLE_TICKS)) {
				throw new IllegalArgumentException("settle_ticks must be 0-" + MAX_SETTLE_TICKS);
			}

			String chatText = optString(params, "chat");
			boolean regex = optBool(params, "chat_regex");
			Predicate<String> chat = chatText == null ? null : chatMatcher(chatText, regex);
			Long since = params.has("since_id") && !params.get("since_id").isJsonNull()
					? params.get("since_id").getAsLong() : null;

			String screen = optString(params, "screen");
			if (screen != null && screen.isBlank()) {
				throw new IllegalArgumentException("screen must be a screen name, such as InventoryScreen, or none.");
			}
			Boolean inWorld = params.has("in_world") && !params.get("in_world").isJsonNull()
					? params.get("in_world").getAsBoolean() : null;

			Double radius = optDouble(params, "radius");
			if (radius != null && radius < 0) {
				throw new IllegalArgumentException("radius must be 0 or more.");
			}

			Conditions c = new Conditions(ticks, timeoutMs, settle == null ? 0 : settle,
					chat, chatText, since == null ? latestChatId : since, optBool(params, "include_action_bar"),
					screen == null ? null : screen.trim(), inWorld,
					optDouble(params, "x"), optDouble(params, "y"), optDouble(params, "z"),
					radius == null ? DEFAULT_RADIUS : radius);
			if (!c.hasCondition() && ticks == null) {
				throw new IllegalArgumentException("Give ticks, or a condition: chat, screen, in_world, or x/y/z.");
			}
			return c;
		}
	}

	/** Text in a chat line, case-insensitive. An empty text matches every line. With regex, a regex find. */
	static Predicate<String> chatMatcher(String text, boolean regex) {
		if (text.isEmpty()) {
			return line -> true;
		}
		if (regex) {
			try {
				Pattern p = Pattern.compile(text);
				return line -> p.matcher(line).find();
			} catch (PatternSyntaxException e) {
				throw new IllegalArgumentException("chat is not a valid regex: " + e.getDescription());
			}
		}
		String lower = text.toLowerCase(Locale.ROOT);
		return line -> line.toLowerCase(Locale.ROOT).contains(lower);
	}

	/** True if the open screen has this name. "none" means no screen. The "Screen" end of the name is optional. */
	static boolean screenMatches(String wanted, String actual) {
		if (wanted.equalsIgnoreCase("none")) {
			return actual == null;
		}
		return actual != null && (actual.equalsIgnoreCase(wanted) || actual.equalsIgnoreCase(wanted + "Screen"));
	}

	/** Distance from a point to the target, on the axes that have a target value only. */
	static double distance(double px, double py, double pz, Double x, Double y, Double z) {
		double dx = x == null ? 0 : px - x;
		double dy = y == null ? 0 : py - y;
		double dz = z == null ? 0 : pz - z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// ---------------------------------------------------------------- waiter

	private static final class Waiter {
		final Conditions c;
		final CompletableFuture<JsonObject> done = new CompletableFuture<>();
		final long t0 = System.nanoTime();
		int ticks;
		/** The tick on which the conditions were all true, or -1. */
		int matchedAt = -1;
		JsonObject chatMatch;

		Waiter(Conditions c) {
			this.c = c;
		}

		/** One check. True when the wait is done (the future is complete). */
		boolean step(Minecraft mc) {
			try {
				long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
				if (matchedAt < 0 && c.hasCondition() && met(mc)) {
					matchedAt = ticks;
				}
				String stop = null;
				if (matchedAt >= 0) {
					if (ticks - matchedAt >= c.settleTicks()) {
						stop = "condition";
					}
				} else if (c.ticks() != null && ticks >= c.ticks()) {
					stop = "ticks";
				} else if (elapsedMs >= c.timeoutMs()) {
					stop = "timeout";
				}
				if (stop == null) {
					return false;
				}
				done.complete(result(mc, stop, elapsedMs));
			} catch (RuntimeException e) {
				done.completeExceptionally(e);
			}
			return true;
		}

		private boolean met(Minecraft mc) {
			if (c.hasChat()) {
				if (chatMatch == null) {
					chatMatch = ChatLog.find(c.sinceId(), c.chat(), c.includeActionBar());
				}
				if (chatMatch == null) {
					return false;
				}
			}
			if (c.screen() != null && !screenMatches(c.screen(), screenName(mc))) {
				return false;
			}
			if (c.inWorld() != null && c.inWorld() != inWorld(mc)) {
				return false;
			}
			if (c.hasPosition()) {
				LocalPlayer p = mc.player;
				if (p == null || distance(p.getX(), p.getY(), p.getZ(), c.x(), c.y(), c.z()) > c.radius()) {
					return false;
				}
			}
			return true;
		}

		private JsonObject result(Minecraft mc, String stop, long elapsedMs) {
			JsonObject o = new JsonObject();
			o.addProperty("matched", stop.equals("condition") || stop.equals("ticks") && !c.hasCondition());
			o.addProperty("stoppedBy", stop);
			o.addProperty("ticks", ticks);
			o.addProperty("elapsedMs", elapsedMs);
			if (chatMatch != null) {
				o.add("chatMatch", chatMatch);
			}
			o.addProperty("latestChatId", ChatLog.latestId());
			o.addProperty("screen", screenName(mc));
			LocalPlayer p = mc.player;
			o.addProperty("inWorld", inWorld(mc));
			if (p != null) {
				o.add("position", TeleportActions.position(p));
				if (c.hasPosition()) {
					o.addProperty("distance", SurroundingsActions.round(
							distance(p.getX(), p.getY(), p.getZ(), c.x(), c.y(), c.z())));
				}
			}
			return o;
		}
	}

	/** In a world, and the world is not loading. The same rule as join_world uses. */
	static boolean inWorld(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return false;
		}
		String screen = screenName(mc);
		return !"LevelLoadingScreen".equals(screen) && !"ReceivingLevelScreen".equals(screen);
	}

	private static String screenName(Minecraft mc) {
		Screen screen = mc.gui.screen();
		return screen == null ? null : screen.getClass().getSimpleName();
	}

	// ---------------------------------------------------------------- params

	private static Integer optInt(JsonObject params, String name) {
		return params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsInt() : null;
	}

	private static Double optDouble(JsonObject params, String name) {
		return params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsDouble() : null;
	}

	private static String optString(JsonObject params, String name) {
		return params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsString() : null;
	}

	private static boolean optBool(JsonObject params, String name) {
		return params.has(name) && !params.get(name).isJsonNull() && params.get(name).getAsBoolean();
	}
}
