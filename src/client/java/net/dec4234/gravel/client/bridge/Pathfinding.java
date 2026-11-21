package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Pathfinding tools (path_to, mine, follow and others). They use the Baritone mod, which is optional. This class
 * has no Baritone types, so it is safe to load when Baritone is not installed. {@link BaritoneActions} has the code
 * that calls Baritone.
 */
public final class Pathfinding {
	public static final String MOD_ID = "baritone";
	static final List<String> ACTIONS = List.of(
			"path_to", "path_status", "path_cancel", "mine", "follow", "attack_entity", "baritone_command", "baritone_settings");
	/** The longest wait_ms. The bridge stops each call after 30 s. */
	static final int MAX_WAIT_MS = 20_000;

	/** Gives a message when Baritone is moving the player, else null. Set by {@link BaritoneActions}. */
	private static volatile Supplier<String> busy = () -> null;

	private Pathfinding() {
	}

	public static void registerAll(ActionRegistry registry) {
		if (FabricLoader.getInstance().isModLoaded(MOD_ID)) {
			BaritoneActions.registerAll(registry);
		} else {
			for (String name : ACTIONS) {
				registry.register(name, (mc, params) -> {
					throw new IllegalStateException("Baritone is not installed, so pathfinding is not available. Put "
							+ "baritone-api-fabric-<version>.jar (for this Minecraft version) in run/mods and restart "
							+ "the game.");
				});
			}
		}
	}

	static void setBusyCheck(Supplier<String> check) {
		busy = check;
	}

	/** Stops a manual movement action while Baritone is moving the player, because both would press the keys. */
	static void requireNotPathing() {
		String reason = busy.get();
		if (reason != null) {
			throw new IllegalStateException(reason + " Use path_cancel first.");
		}
	}

	// ---------------------------------------------------------------- parameter helpers (tested)

	/** wait_ms from the params: 0 (default) returns at once, at most {@link #MAX_WAIT_MS}. */
	static int waitMs(JsonObject params) {
		JsonElement e = params.get("wait_ms");
		int ms = e == null || e.isJsonNull() ? 0 : e.getAsInt();
		if (ms < 0 || ms > MAX_WAIT_MS) {
			throw new IllegalArgumentException("wait_ms must be 0-" + MAX_WAIT_MS);
		}
		return ms;
	}

	/**
	 * Block names from a JSON array or a comma separated string, such as ["diamond_ore", "minecraft:iron_ore"] or
	 * "diamond_ore, iron_ore". Names are lower case. The "minecraft:" part stays if it was given.
	 */
	static List<String> names(JsonElement value, String param) {
		List<String> out = new ArrayList<>();
		if (value != null && !value.isJsonNull()) {
			if (value.isJsonArray()) {
				for (JsonElement e : value.getAsJsonArray()) {
					addNames(out, e.getAsString());
				}
			} else {
				addNames(out, value.getAsString());
			}
		}
		if (out.isEmpty()) {
			throw new IllegalArgumentException("Give at least one name in " + param + ".");
		}
		return out;
	}

	private static void addNames(List<String> out, String raw) {
		for (String part : raw.split("[,\\s]+")) {
			String name = part.trim().toLowerCase(Locale.ROOT);
			if (!name.isEmpty() && !out.contains(name)) {
				out.add(name);
			}
		}
	}

	static JsonArray jsonArray(List<String> values) {
		JsonArray a = new JsonArray();
		values.forEach(a::add);
		return a;
	}
}
