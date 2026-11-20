package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Player movement: turn the view (look), walk or sprint (move) and jump. Movement holds the real key bindings
 * (forward, left, sprint, jump and others), so the game moves the player the same way as for a real player.
 */
public final class MovementActions {
	/** Longest time one move or jump call can run. The bridge waits for the end, and it stops after 30 s. */
	static final int MAX_MOVE_MS = 20_000;
	private static final int TICK_MS = 50;
	private static final int DEFAULT_MOVE_MS = 1000;
	/** Without hold_ms, jump holds the key until the player leaves the ground, but not longer than this. */
	private static final int JUMP_PRESS_MS = 250;
	private static final int DEFAULT_LAND_WAIT_MS = 1500;
	/** Time for the game to render a frame after a turn, so the crosshair target is new. */
	private static final int LOOK_SETTLE_MS = 100;

	private MovementActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.registerAsync("look", MovementActions::look);
		registry.registerAsync("move", MovementActions::move);
		registry.registerAsync("jump", MovementActions::jump);
	}

	// ---------------------------------------------------------------- look

	private static CompletableFuture<JsonObject> look(Minecraft mc, JsonObject params) {
		LocalPlayer player = InventoryActions.requirePlayer(mc);
		Double yaw = number(params, "yaw");
		Double pitch = number(params, "pitch");
		Double x = number(params, "x");
		Double y = number(params, "y");
		Double z = number(params, "z");
		boolean relative = bool(params, "relative");
		Integer entityId = params.has("entity") && !params.get("entity").isJsonNull() ? params.get("entity").getAsInt() : null;

		boolean hasPoint = x != null || y != null || z != null;
		boolean hasAngles = yaw != null || pitch != null;
		int modes = (hasPoint ? 1 : 0) + (hasAngles ? 1 : 0) + (entityId != null ? 1 : 0);
		if (modes == 0) {
			throw new IllegalArgumentException("Pass yaw and/or pitch, or x, y and z, or entity.");
		}
		if (modes > 1) {
			throw new IllegalArgumentException("Use only one of: yaw/pitch, x/y/z, entity.");
		}

		JsonObject result = new JsonObject();
		result.add("from", rotation(player));
		float newYaw;
		float newPitch;
		if (entityId != null) {
			Entity target = mc.level.getEntity(entityId);
			if (target == null || target == player) {
				throw new IllegalArgumentException("No entity with id " + entityId + " near the player. Use list_entities.");
			}
			Vec3 center = target.getBoundingBox().getCenter();
			float[] angles = anglesTo(player.getEyePosition(), center);
			newYaw = angles[0];
			newPitch = angles[1];
			result.addProperty("lookingAt", SurroundingsActions.entityTypeId(target) + " #" + entityId);
		} else if (hasPoint) {
			if (x == null || y == null || z == null) {
				throw new IllegalArgumentException("Pass all of x, y and z.");
			}
			float[] angles = anglesTo(player.getEyePosition(), new Vec3(x, y, z));
			newYaw = angles[0];
			newPitch = angles[1];
		} else if (relative) {
			newYaw = player.getYRot() + (yaw == null ? 0f : yaw.floatValue());
			newPitch = player.getXRot() + (pitch == null ? 0f : pitch.floatValue());
		} else {
			newYaw = yaw == null ? player.getYRot() : yaw.floatValue();
			newPitch = pitch == null ? player.getXRot() : pitch.floatValue();
		}
		turn(player, newYaw, newPitch);

		// The crosshair target updates when the game renders the next frame.
		CompletableFuture<JsonObject> done = new CompletableFuture<>();
		CompletableFuture.delayedExecutor(LOOK_SETTLE_MS, TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
			try {
				LocalPlayer now = InventoryActions.requirePlayer(mc);
				result.add("to", rotation(now));
				result.add("target", SurroundingsActions.lookTarget(mc, now));
				done.complete(result);
			} catch (RuntimeException e) {
				done.completeExceptionally(e);
			}
		}));
		return done;
	}

	/** Sets the view direction at once, with no smooth turn between the old and new direction. */
	static void turn(LocalPlayer player, float yaw, float pitch) {
		float p = Mth.clamp(pitch, -90f, 90f);
		player.setYRot(yaw);
		player.setXRot(p);
		player.yRotO = yaw;
		player.xRotO = p;
		player.setYHeadRot(yaw);
		player.yHeadRotO = yaw;
	}

	/** Yaw and pitch (degrees) to look from one point to another. Yaw 0 is south (+Z), 90 west, 180 north, -90 east. */
	static float[] anglesTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		if (horizontal < 1.0e-6 && Math.abs(dy) < 1.0e-6) {
			throw new IllegalArgumentException("That point is at the player's eyes. Give a different point.");
		}
		return new float[]{yaw, pitch};
	}

	private static JsonObject rotation(LocalPlayer player) {
		JsonObject o = new JsonObject();
		o.addProperty("yaw", SurroundingsActions.round(player.getYRot()));
		o.addProperty("pitch", SurroundingsActions.round(player.getXRot()));
		o.addProperty("facing", player.getDirection().getName());
		return o;
	}

	// ---------------------------------------------------------------- move

	private static CompletableFuture<JsonObject> move(Minecraft mc, JsonObject params) {
		LocalPlayer player = requireControl(mc);
		Options options = mc.options;
		List<KeyMapping> keys = new ArrayList<>(directionKeys(options, params.get("direction")));
		boolean sprint = bool(params, "sprint");
		boolean sneak = bool(params, "sneak");
		boolean jump = bool(params, "jump");
		if (sprint && sneak) {
			throw new IllegalArgumentException("sprint and sneak can not be used together.");
		}
		if (sprint && !keys.contains(options.keyUp)) {
			throw new IllegalArgumentException("sprint needs a forward direction (forward, forward+left, forward+right).");
		}
		if (keys.isEmpty() && !sneak && !jump) {
			throw new IllegalArgumentException("direction none moves nothing. Add sneak or jump, or give a direction.");
		}
		Double distance = number(params, "distance");
		if (distance != null && distance <= 0) {
			throw new IllegalArgumentException("distance must be more than 0.");
		}
		int durationMs = params.has("duration_ms") && !params.get("duration_ms").isJsonNull()
				? params.get("duration_ms").getAsInt() : (distance == null ? DEFAULT_MOVE_MS : MAX_MOVE_MS);
		if (durationMs < 0 || durationMs > MAX_MOVE_MS) {
			throw new IllegalArgumentException("duration_ms must be 0-" + MAX_MOVE_MS);
		}
		if (sneak) {
			keys.add(options.keyShift);
		}
		if (sprint) {
			keys.add(options.keySprint);
		}
		if (jump) {
			keys.add(options.keyJump);
		}

		Vec3 start = player.position();
		JsonObject result = new JsonObject();
		result.add("from", TeleportActions.position(player));
		JsonArray held = new JsonArray();
		keys.forEach(k -> held.add(k.getName()));
		result.add("keys", held);

		keys.forEach(k -> hold(k, true));
		boolean[] sprinted = {false};
		long t0 = System.nanoTime();
		return everyTick(mc, () -> keys.forEach(k -> hold(k, false)), () -> {
			long elapsed = (System.nanoTime() - t0) / 1_000_000;
			LocalPlayer now = mc.player;
			sprinted[0] |= now != null && now.isSprinting();
			String stop = null;
			if (now != player) {
				stop = "left_world";
			} else if (mc.gui.screen() != null) {
				stop = "screen_opened";
			} else if (distance != null && horizontal(start, now.position()) >= distance) {
				stop = "distance";
			} else if (elapsed >= durationMs) {
				stop = "time";
			}
			if (stop == null) {
				return null;
			}
			keys.forEach(k -> hold(k, false));
			result.addProperty("stoppedBy", stop);
			result.addProperty("durationMs", elapsed);
			if (now == player) {
				result.add("to", TeleportActions.position(now));
				result.addProperty("distance", SurroundingsActions.round(horizontal(start, now.position())));
				result.addProperty("heightChange", SurroundingsActions.round(now.getY() - start.y));
				result.addProperty("sprinted", sprinted[0]);
				result.addProperty("onGround", now.onGround());
				result.addProperty("blocked", now.horizontalCollision);
			}
			Screen screen = mc.gui.screen();
			result.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
			return result;
		});
	}

	/** The movement keys for a direction such as forward, back, left, right, forward+left or none. */
	static Set<KeyMapping> directionKeys(Options options, JsonElement value) {
		Set<KeyMapping> keys = new LinkedHashSet<>();
		String raw = value == null || value.isJsonNull() ? "forward" : value.getAsString();
		for (String part : directionParts(raw)) {
			switch (part) {
				case "forward", "forwards", "w" -> keys.add(options.keyUp);
				case "back", "backward", "backwards", "s" -> keys.add(options.keyDown);
				case "left", "a" -> keys.add(options.keyLeft);
				case "right", "d" -> keys.add(options.keyRight);
				case "none" -> {
				}
				default -> throw new IllegalArgumentException("Unknown direction '" + part
						+ "'. Use forward, back, left, right, a mix such as forward+left, or none.");
			}
		}
		if (keys.contains(options.keyUp) && keys.contains(options.keyDown)
				|| keys.contains(options.keyLeft) && keys.contains(options.keyRight)) {
			throw new IllegalArgumentException("Direction '" + raw + "' has two opposite directions.");
		}
		return keys;
	}

	static List<String> directionParts(String raw) {
		List<String> parts = new ArrayList<>();
		for (String part : raw.trim().toLowerCase(Locale.ROOT).split("[+,_ \\-]+")) {
			if (!part.isEmpty()) {
				parts.add(part);
			}
		}
		if (parts.isEmpty()) {
			parts.add("forward");
		}
		return parts;
	}

	// ---------------------------------------------------------------- jump

	private static CompletableFuture<JsonObject> jump(Minecraft mc, JsonObject params) {
		LocalPlayer player = requireControl(mc);
		KeyMapping key = mc.options.keyJump;
		Integer holdMs = params.has("hold_ms") && !params.get("hold_ms").isJsonNull() ? params.get("hold_ms").getAsInt() : null;
		if (holdMs != null && (holdMs < 0 || holdMs > MAX_MOVE_MS)) {
			throw new IllegalArgumentException("hold_ms must be 0-" + MAX_MOVE_MS);
		}
		int waitMs = params.has("wait_ms") && !params.get("wait_ms").isJsonNull()
				? params.get("wait_ms").getAsInt() : DEFAULT_LAND_WAIT_MS;
		if (waitMs < 0 || waitMs > MAX_MOVE_MS) {
			throw new IllegalArgumentException("wait_ms must be 0-" + MAX_MOVE_MS);
		}

		Vec3 start = player.position();
		JsonObject result = new JsonObject();
		result.add("from", TeleportActions.position(player));
		result.addProperty("wasOnGround", player.onGround());
		double[] peak = {start.y};
		boolean[] leftGround = {false};
		long[] releasedAt = {-1};

		hold(key, true);
		long t0 = System.nanoTime();
		return everyTick(mc, () -> hold(key, false), () -> {
			long elapsed = (System.nanoTime() - t0) / 1_000_000;
			LocalPlayer now = mc.player;
			if (now != player || mc.gui.screen() != null) {
				hold(key, false);
				result.addProperty("stoppedBy", now != player ? "left_world" : "screen_opened");
				return finishJump(mc, result, start, peak[0], leftGround[0], elapsed);
			}
			peak[0] = Math.max(peak[0], now.getY());
			leftGround[0] |= !now.onGround();
			if (releasedAt[0] < 0) {
				boolean release = holdMs != null ? elapsed >= holdMs : leftGround[0] || elapsed >= JUMP_PRESS_MS;
				if (release) {
					hold(key, false);
					releasedAt[0] = elapsed;
					result.addProperty("heldMs", elapsed);
				}
				return null;
			}
			boolean landed = leftGround[0] && now.onGround();
			if (landed || elapsed - releasedAt[0] >= waitMs) {
				result.addProperty("stoppedBy", landed ? "landed" : "time");
				return finishJump(mc, result, start, peak[0], leftGround[0], elapsed);
			}
			return null;
		});
	}

	private static JsonObject finishJump(Minecraft mc, JsonObject result, Vec3 start, double peakY, boolean leftGround, long elapsed) {
		LocalPlayer now = mc.player;
		result.addProperty("leftGround", leftGround);
		result.addProperty("durationMs", elapsed);
		result.addProperty("peakY", peakY);
		result.addProperty("rise", SurroundingsActions.round(peakY - start.y));
		if (now != null) {
			result.add("to", TeleportActions.position(now));
			result.addProperty("onGround", now.onGround());
			result.addProperty("heightChange", SurroundingsActions.round(now.getY() - start.y));
		}
		return result;
	}

	// ---------------------------------------------------------------- helpers

	/** A step that runs once per tick on the client thread. It gives the result when the action is done, else null. */
	@FunctionalInterface
	private interface Step {
		JsonObject run();
	}

	/**
	 * Runs a step each tick on the client thread until it gives a result. If the step fails, cleanup runs
	 * (for example to release the held keys).
	 */
	private static CompletableFuture<JsonObject> everyTick(Minecraft mc, Runnable cleanup, Step step) {
		CompletableFuture<JsonObject> done = new CompletableFuture<>();
		schedule(mc, cleanup, step, done);
		return done;
	}

	private static void schedule(Minecraft mc, Runnable cleanup, Step step, CompletableFuture<JsonObject> done) {
		CompletableFuture.delayedExecutor(TICK_MS, TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
			try {
				JsonObject result = step.run();
				if (result != null) {
					done.complete(result);
				} else {
					schedule(mc, cleanup, step, done);
				}
			} catch (RuntimeException e) {
				try {
					cleanup.run();
				} finally {
					done.completeExceptionally(e);
				}
			}
		}));
	}

	/**
	 * Sets a key binding down or up. Toggle bindings (sprint and sneak with the toggle option on) flip on each
	 * press and do not react to a release, so to put them up, they get one more press.
	 */
	static void hold(KeyMapping key, boolean down) {
		if (key.isDown() == down) {
			return;
		}
		key.setDown(down);
		if (key.isDown() != down) {
			key.setDown(true);
		}
	}

	/** The player, in a world with no screen open, so that movement keys act. */
	private static LocalPlayer requireControl(Minecraft mc) {
		LocalPlayer player = InventoryActions.requirePlayer(mc);
		Screen screen = mc.gui.screen();
		if (screen != null) {
			throw new IllegalStateException("Screen " + screen.getClass().getSimpleName()
					+ " is open, so the player can not move. Close it first with close_screen.");
		}
		return player;
	}

	static double horizontal(Vec3 a, Vec3 b) {
		double dx = b.x - a.x;
		double dz = b.z - a.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static Double number(JsonObject params, String name) {
		return params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsDouble() : null;
	}

	private static boolean bool(JsonObject params, String name) {
		return params.has(name) && !params.get(name).isJsonNull() && params.get(name).getAsBoolean();
	}
}
