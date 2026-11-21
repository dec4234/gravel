package net.dec4234.gravel.client.bridge;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.SettingsUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dec4234.gravel.Gravel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Pathfinding with the Baritone API: go to a place, mine blocks, follow an entity, run any Baritone command and
 * change its settings. Only load this class when Baritone is installed (see {@link Pathfinding}).
 *
 * <p>Baritone works over many ticks, but a bridge call stops after 30 s. So each start action returns at once (or
 * after wait_ms), and path_status reports the progress.
 */
final class BaritoneActions {
	private static final int TICK_MS = 50;
	/** Baritone needs a few ticks to start a process, so a wait does not end in this time. */
	private static final int MIN_WAIT_TICKS = 3;
	private static final int EVENT_CAPACITY = 200;
	private static final int EVENTS_SHOWN = 20;
	private static final int MAX_TEXT = 300;
	/** Baritone sends its chat messages one tick later (Minecraft.execute), so wait this long to read them. */
	private static final int OUTPUT_TICKS = 2;
	private static final Set<PathEvent> FAIL_EVENTS = Set.of(PathEvent.CALC_FAILED, PathEvent.NEXT_CALC_FAILED);

	private record EventEntry(long id, String event) {
	}

	/**
	 * The last task that Gravel started. goal is null when Baritone picks the goals (mine, follow, block).
	 * canceled is true after path_cancel. (Baritone also sends CANCELED when a task ends normally.)
	 */
	private record Task(String kind, String description, Goal goal, long firstEventId, long startNanos,
						boolean canceled) {
		Task cancel() {
			return new Task(kind, description, goal, firstEventId, startNanos, true);
		}
	}

	/** The attack_entity task. Only the client thread changes it. result is null while it runs. */
	private static final class AttackJob {
		final int entityId;
		final String type;
		int oldFollowRadius;
		final Task task;
		int hits;
		Float health;
		String result;

		AttackJob(int entityId, String type, int oldFollowRadius, Task task) {
			this.entityId = entityId;
			this.type = type;
			this.oldFollowRadius = oldFollowRadius;
			this.task = task;
		}
	}

	/** Baritone's followRadius during an attack, so the player stays in reach of the target. */
	private static final int ATTACK_FOLLOW_RADIUS = 1;

	private static final Deque<EventEntry> EVENTS = new ArrayDeque<>();
	private static long nextEventId = 1;
	private static volatile Task task;
	private static volatile AttackJob attack;
	private static boolean hooked;

	private BaritoneActions() {
	}

	static void registerAll(ActionRegistry registry) {
		registry.registerAsync("path_to", BaritoneActions::pathTo);
		registry.registerAsync("path_status", BaritoneActions::pathStatus);
		registry.register("path_cancel", BaritoneActions::pathCancel);
		registry.registerAsync("mine", BaritoneActions::mine);
		registry.registerAsync("follow", BaritoneActions::follow);
		registry.registerAsync("attack_entity", BaritoneActions::attackEntity);
		registry.registerAsync("baritone_command", BaritoneActions::command);
		ClientTickEvents.END_CLIENT_TICK.register(BaritoneActions::attackTick);
		registry.register("baritone_settings", BaritoneActions::settings);
		// Hook in when the game is ready, so Baritone messages go to read_chat from the start.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			try {
				hook();
			} catch (RuntimeException e) {
				Gravel.LOGGER.warn("Could not connect to Baritone", e);
			}
		});
		Pathfinding.setBusyCheck(() -> {
			if (!hooked || Minecraft.getInstance().player == null) {
				return null;
			}
			IBaritone b = primary();
			return active(b) ? "Baritone is moving the player (" + processName(b) + ")." : null;
		});
	}

	/** Records path events and copies Baritone's chat messages into the chat log. Runs one time. */
	private static synchronized void hook() {
		if (hooked) {
			return;
		}
		primary().getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
			@Override
			public void onPathEvent(PathEvent event) {
				addEvent(event);
			}
		});
		Settings.Setting<Consumer<Component>> logger = BaritoneAPI.getSettings().logger;
		Consumer<Component> original = logger.value;
		logger.value = message -> {
			ChatLog.add("baritone", null, message.getString());
			original.accept(message);
		};
		hooked = true;
	}

	private static IBaritone primary() {
		return BaritoneAPI.getProvider().getPrimaryBaritone();
	}

	/** Baritone for a new action: hooked in, with a player in a world. */
	private static IBaritone ready(Minecraft mc) {
		InventoryActions.requirePlayer(mc);
		hook();
		return primary();
	}

	private static synchronized void addEvent(PathEvent event) {
		EVENTS.addLast(new EventEntry(nextEventId++, event.name()));
		while (EVENTS.size() > EVENT_CAPACITY) {
			EVENTS.removeFirst();
		}
	}

	private static synchronized long nextEventId() {
		return nextEventId;
	}

	// ---------------------------------------------------------------- actions

	private static CompletableFuture<JsonObject> pathTo(Minecraft mc, JsonObject params) {
		IBaritone b = ready(mc);
		Integer x = integer(params, "x");
		Integer y = integer(params, "y");
		Integer z = integer(params, "z");
		Integer radius = integer(params, "radius");
		String block = string(params, "block");
		int waitMs = Pathfinding.waitMs(params);

		if (block != null) {
			if (x != null || y != null || z != null) {
				throw new IllegalArgumentException("Use block or x/y/z, not both.");
			}
			BlockOptionalMeta target = new BlockOptionalMeta(block);
			b.getPathingBehavior().cancelEverything();
			start("path_to", "nearest " + target, null);
			b.getGetToBlockProcess().getToBlock(target);
			return waitThenStatus(mc, b, waitMs, MIN_WAIT_TICKS);
		}

		Goal goal;
		if (x != null && z != null) {
			if (y == null) {
				if (radius != null) {
					throw new IllegalArgumentException("radius needs y. Without y the goal is the x/z column.");
				}
				goal = new GoalXZ(x, z);
			} else if (radius != null && radius > 0) {
				goal = new GoalNear(new BlockPos(x, y, z), radius);
			} else {
				goal = new GoalBlock(x, y, z);
			}
		} else if (x == null && z == null && y != null) {
			goal = new GoalYLevel(y);
		} else {
			throw new IllegalArgumentException("Give x, y and z (a block), x and z (a column), y only (a height), "
					+ "or block (the nearest block of a type).");
		}
		b.getPathingBehavior().cancelEverything();
		start("path_to", goal.toString(), goal);
		b.getCustomGoalProcess().setGoalAndPath(goal);
		return waitThenStatus(mc, b, waitMs, MIN_WAIT_TICKS);
	}

	private static CompletableFuture<JsonObject> pathStatus(Minecraft mc, JsonObject params) {
		IBaritone b = ready(mc);
		return waitThenStatus(mc, b, Pathfinding.waitMs(params), 0);
	}

	private static JsonObject pathCancel(Minecraft mc, JsonObject params) {
		IBaritone b = ready(mc);
		boolean wasActive = active(b);
		b.getPathingBehavior().cancelEverything();
		AttackJob job = attack;
		if (job != null) {
			endAttack(b, job, "canceled", false);
		}
		Task t = task;
		if (wasActive && t != null) {
			task = t.cancel();
		}
		JsonObject o = status(mc, b);
		o.addProperty("wasActive", wasActive);
		return o;
	}

	private static CompletableFuture<JsonObject> mine(Minecraft mc, JsonObject params) {
		IBaritone b = ready(mc);
		List<String> blocks = Pathfinding.names(params.get("blocks"), "blocks");
		Integer count = integer(params, "count");
		if (count != null && count < 0) {
			throw new IllegalArgumentException("count must be 0 (no limit) or more.");
		}
		// Check the names first, so an unknown block gives a clear error.
		BlockOptionalMeta[] targets = blocks.stream().map(BlockOptionalMeta::new).toArray(BlockOptionalMeta[]::new);
		int waitMs = Pathfinding.waitMs(params);
		b.getPathingBehavior().cancelEverything();
		start("mine", (count == null || count == 0 ? "" : count + " x ") + String.join(", ", blocks), null);
		b.getMineProcess().mine(count == null ? 0 : count, targets);
		return waitThenStatus(mc, b, waitMs, MIN_WAIT_TICKS);
	}

	private static CompletableFuture<JsonObject> follow(Minecraft mc, JsonObject params) {
		IBaritone b = ready(mc);
		LocalPlayer self = mc.player;
		Integer id = integer(params, "entity");
		String player = string(params, "player");
		String type = string(params, "type");
		int given = (id != null ? 1 : 0) + (player != null ? 1 : 0) + (type != null ? 1 : 0);
		if (given != 1) {
			throw new IllegalArgumentException("Give one of: entity (id from list_entities), player (name), type "
					+ "(entity type such as minecraft:cow).");
		}
		Predicate<Entity> filter;
		String description;
		if (id != null) {
			filter = e -> e.getId() == id;
			description = "entity #" + id;
		} else if (player != null) {
			filter = e -> e instanceof Player p && p.getName().getString().equalsIgnoreCase(player);
			description = "player " + player;
		} else {
			String wanted = type.contains(":") ? type.toLowerCase(Locale.ROOT) : "minecraft:" + type.toLowerCase(Locale.ROOT);
			filter = e -> SurroundingsActions.entityTypeId(e).equals(wanted);
			description = "type " + wanted;
		}
		Predicate<Entity> notSelf = e -> e != self && filter.test(e);
		boolean any = false;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (notSelf.test(e)) {
				any = true;
				break;
			}
		}
		if (!any) {
			throw new IllegalArgumentException("No loaded entity matches " + description + ". Use list_entities.");
		}
		int waitMs = Pathfinding.waitMs(params);
		b.getPathingBehavior().cancelEverything();
		start("follow", description, null);
		b.getFollowProcess().follow(notSelf);
		return waitThenStatus(mc, b, waitMs, MIN_WAIT_TICKS);
	}

	// ---------------------------------------------------------------- attack

	/**
	 * Baritone follows the target, and a tick handler hits it when it is in reach, the attack is fully charged and
	 * the target can take damage again (hurtTime 0). Baritone has no attack feature, so Gravel does the hits.
	 */
	private static CompletableFuture<JsonObject> attackEntity(Minecraft mc, JsonObject params) {
		IBaritone b = ready(mc);
		LocalPlayer self = mc.player;
		Integer id = integer(params, "entity");
		String type = string(params, "type");
		if ((id == null) == (type == null)) {
			throw new IllegalArgumentException("Give entity (id from list_entities) or type (such as cow: the nearest "
					+ "one of that type), not both.");
		}
		Entity target;
		if (id != null) {
			target = mc.level.getEntity(id);
			if (target == null || target == self) {
				throw new IllegalArgumentException("No entity with id " + id + " near the player. Use list_entities.");
			}
		} else {
			String wanted = typeId(type);
			target = null;
			double best = Double.MAX_VALUE;
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e != self && e.isAlive() && SurroundingsActions.entityTypeId(e).equals(wanted)) {
					double d = self.distanceToSqr(e);
					if (d < best) {
						best = d;
						target = e;
					}
				}
			}
			if (target == null) {
				throw new IllegalArgumentException("No loaded entity of type " + wanted + ". Use list_entities.");
			}
		}
		if (!(target instanceof LivingEntity) || !target.isAlive()) {
			throw new IllegalArgumentException(SurroundingsActions.entityTypeId(target) + " #" + target.getId()
					+ " is not a living entity, so it can not be killed.");
		}
		int waitMs = Pathfinding.waitMs(params);

		AttackJob old = attack;
		if (old != null && old.result == null) {
			endAttack(b, old, "replaced", false);
		}
		b.getPathingBehavior().cancelEverything();
		int targetId = target.getId();
		String description = SurroundingsActions.entityTypeId(target) + " #" + targetId;
		start("attack", description, null);
		Settings.Setting<Integer> radius = BaritoneAPI.getSettings().followRadius;
		attack = new AttackJob(targetId, SurroundingsActions.entityTypeId(target), radius.value, task);
		// Stay close, so the target is in reach most of the time. endAttack puts the old value back.
		radius.value = ATTACK_FOLLOW_RADIUS;
		b.getFollowProcess().follow(e -> e.getId() == targetId);
		return waitThenStatus(mc, b, waitMs, MIN_WAIT_TICKS);
	}

	/** Runs at the end of each client tick. Hits the target of the attack task when it can. */
	private static void attackTick(Minecraft mc) {
		AttackJob job = attack;
		if (job == null || job.result != null || !hooked) {
			return;
		}
		IBaritone b = primary();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			endAttack(b, job, "left_world", false);
			return;
		}
		Entity e = mc.level.getEntity(job.entityId);
		if (e instanceof LivingEntity living) {
			job.health = living.getHealth();
		}
		if (!(e instanceof LivingEntity target) || !e.isAlive() || target.isDeadOrDying()) {
			boolean killed = e != null || (job.health != null && job.health <= 0);
			endAttack(b, job, killed ? "killed" : "lost", true);
			return;
		}
		// Another task (path_to, mine, a command) or Baritone itself stopped the follow.
		if (task != job.task || !b.getFollowProcess().isActive()) {
			endAttack(b, job, "stopped", false);
			return;
		}
		if (mc.gui.screen() != null || player.isHandsBusy()
				|| !player.isWithinEntityInteractionRange(target, 0.0) || !player.hasLineOfSight(target)) {
			return;
		}
		Vec3 eyes = player.getEyePosition();
		Vec3 center = target.getBoundingBox().getCenter();
		if (eyes.distanceToSqr(center) > 1.0e-6) {
			float[] angles = MovementActions.anglesTo(eyes, center);
			MovementActions.turn(player, angles[0], angles[1]);
		}
		// Wait for a full attack charge, and until the target can take damage again.
		if (player.getAttackStrengthScale(0.5f) < 1f || target.hurtTime > 0) {
			return;
		}
		ItemStack held = player.getMainHandItem();
		mc.gameMode.attack(player, target);
		player.swing(InteractionHand.MAIN_HAND, held.getAttackAnimation(), false);
		player.connection.send(ServerboundPunchPacket.INSTANCE);
		job.hits++;
	}

	/** Ends an attack task and puts back the follow radius. stopFollow stops Baritone if it still follows. */
	private static void endAttack(IBaritone b, AttackJob job, String result, boolean stopFollow) {
		if (job.result != null) {
			return;
		}
		job.result = result;
		BaritoneAPI.getSettings().followRadius.value = job.oldFollowRadius;
		if (stopFollow && task == job.task && b.getFollowProcess().isActive()) {
			b.getPathingBehavior().cancelEverything();
		}
	}

	private static JsonObject attackJson(AttackJob job) {
		JsonObject o = new JsonObject();
		o.addProperty("target", job.entityId);
		o.addProperty("type", job.type);
		o.addProperty("hits", job.hits);
		if (job.health != null) {
			o.addProperty("targetHealth", SurroundingsActions.round(Math.max(0f, job.health)));
		}
		o.addProperty("result", job.result == null ? "running" : job.result);
		return o;
	}

	private static String typeId(String type) {
		String t = type.strip().toLowerCase(Locale.ROOT);
		return t.contains(":") ? t : "minecraft:" + t;
	}

	private static CompletableFuture<JsonObject> command(Minecraft mc, JsonObject params) {
		IBaritone b = ready(mc);
		String text = string(params, "command");
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("Give a command, for example \"goto 100 64 -20\" or \"help\".");
		}
		text = text.strip();
		String prefix = BaritoneAPI.getSettings().prefix.value;
		if (text.startsWith(prefix)) {
			text = text.substring(prefix.length()).strip();
		}
		int waitMs = Pathfinding.waitMs(params);
		long chatBefore = ChatLog.latestId();
		long eventsBefore = nextEventId();
		boolean known = b.getCommandManager().execute(text);
		if (active(b)) {
			task = new Task("command", text, b.getCustomGoalProcess().getGoal(), eventsBefore, System.nanoTime(), false);
		}
		String command = text;
		int minTicks = active(b) ? MIN_WAIT_TICKS : 0;
		return afterTicks(mc, OUTPUT_TICKS).thenCompose(v -> waitThenStatus(mc, b, waitMs, minTicks)).thenApply(status -> {
			JsonObject o = new JsonObject();
			o.addProperty("command", command);
			o.addProperty("known", known);
			o.add("output", ChatLog.read(chatBefore, 100, false).get("messages"));
			o.add("status", status);
			return o;
		});
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static JsonObject settings(Minecraft mc, JsonObject params) {
		hook();
		Settings settings = BaritoneAPI.getSettings();
		String name = string(params, "name");
		String value = string(params, "value");
		String filter = string(params, "filter");
		JsonObject o = new JsonObject();
		if (name != null) {
			Settings.Setting setting = settings.byLowerName.get(name.toLowerCase(Locale.ROOT));
			if (setting == null) {
				throw new IllegalArgumentException("No Baritone setting '" + name + "'. Use filter to search the names.");
			}
			if (value != null) {
				if (setting.isJavaOnly()) {
					throw new IllegalArgumentException("Setting " + setting.getName() + " can not be changed here.");
				}
				o.addProperty("old", SettingsUtil.settingValueToString(setting));
				SettingsUtil.parseAndApply(settings, setting.getName().toLowerCase(Locale.ROOT), value);
				saveSettings(settings, setting);
			}
			o.add("setting", settingJson(setting));
			return o;
		}
		if (value != null) {
			throw new IllegalArgumentException("value needs name.");
		}
		JsonArray list = new JsonArray();
		List<Settings.Setting> picked = filter == null
				? SettingsUtil.modifiedSettings(settings)
				: (List) settings.allSettings;
		String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
		for (Settings.Setting s : picked) {
			if (s.isJavaOnly() || (needle != null && !s.getName().toLowerCase(Locale.ROOT).contains(needle))) {
				continue;
			}
			list.add(settingJson(s));
			if (list.size() >= 100) {
				break;
			}
		}
		o.addProperty("shown", filter == null ? "changed settings (pass filter to search all)" : "names with '" + filter + "'");
		o.add("settings", list);
		return o;
	}

	/**
	 * Saves the settings file. During an attack, followRadius has a temporary value, so the file gets the value from
	 * before the attack. A new followRadius from the user is kept for the end of the attack.
	 */
	@SuppressWarnings("rawtypes")
	private static void saveSettings(Settings settings, Settings.Setting changed) {
		AttackJob job = attack;
		Settings.Setting<Integer> radius = settings.followRadius;
		if (job == null || job.result != null) {
			SettingsUtil.save(settings);
			return;
		}
		if (changed == radius) {
			job.oldFollowRadius = radius.value;
		}
		radius.value = job.oldFollowRadius;
		try {
			SettingsUtil.save(settings);
		} finally {
			radius.value = ATTACK_FOLLOW_RADIUS;
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static JsonObject settingJson(Settings.Setting s) {
		JsonObject o = new JsonObject();
		o.addProperty("name", s.getName());
		o.addProperty("type", SettingsUtil.settingTypeToString(s));
		o.addProperty("value", SettingsUtil.settingValueToString(s));
		o.addProperty("default", SettingsUtil.settingDefaultToString(s));
		return o;
	}

	// ---------------------------------------------------------------- status

	private static void start(String kind, String description, Goal goal) {
		task = new Task(kind, description, goal, nextEventId(), System.nanoTime(), false);
	}

	/** Completes on the client thread after the given number of ticks. */
	private static CompletableFuture<Void> afterTicks(Minecraft mc, int ticks) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		CompletableFuture.delayedExecutor((long) ticks * TICK_MS, TimeUnit.MILLISECONDS)
				.execute(() -> mc.execute(() -> done.complete(null)));
		return done;
	}

	/**
	 * Waits until Baritone is idle or waitMs ends, then gives the status. minTicks stops an early end before
	 * Baritone has started the new process.
	 */
	private static CompletableFuture<JsonObject> waitThenStatus(Minecraft mc, IBaritone b, int waitMs, int minTicks) {
		CompletableFuture<JsonObject> done = new CompletableFuture<>();
		if (waitMs <= 0) {
			done.complete(status(mc, b));
			return done;
		}
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
		poll(mc, b, deadline, minTicks, 0, done);
		return done;
	}

	private static void poll(Minecraft mc, IBaritone b, long deadline, int minTicks, int ticks,
							 CompletableFuture<JsonObject> done) {
		CompletableFuture.delayedExecutor(TICK_MS, TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
			try {
				boolean idle = ticks + 1 >= minTicks && !active(b);
				if (mc.player == null || idle || System.nanoTime() >= deadline) {
					done.complete(status(mc, b));
				} else {
					poll(mc, b, deadline, minTicks, ticks + 1, done);
				}
			} catch (RuntimeException e) {
				done.completeExceptionally(e);
			}
		}));
	}

	private static List<IBaritoneProcess> processes(IBaritone b) {
		return List.of(b.getCustomGoalProcess(), b.getGetToBlockProcess(), b.getMineProcess(), b.getFollowProcess(),
				b.getExploreProcess(), b.getFarmProcess(), b.getBuilderProcess(), b.getElytraProcess());
	}

	private static boolean active(IBaritone b) {
		if (b.getPathingBehavior().isPathing()) {
			return true;
		}
		for (IBaritoneProcess p : processes(b)) {
			if (p.isActive()) {
				return true;
			}
		}
		return false;
	}

	private static String processName(IBaritone b) {
		Optional<IBaritoneProcess> inControl = b.getPathingControlManager().mostRecentInControl();
		if (inControl.isPresent() && inControl.get().isActive()) {
			return shorten(inControl.get().displayName());
		}
		for (IBaritoneProcess p : processes(b)) {
			if (p.isActive()) {
				return shorten(p.displayName());
			}
		}
		return b.getPathingBehavior().isPathing() ? "pathing" : null;
	}

	/** Mine and other processes have goals with many parts. Keep the text short. */
	private static String shorten(String text) {
		return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "... (" + text.length() + " chars)";
	}

	private static JsonObject status(Minecraft mc, IBaritone b) {
		JsonObject o = new JsonObject();
		boolean active = active(b);
		Task t = task;
		LocalPlayer player = mc.player;
		Goal goal = b.getPathingBehavior().getGoal();
		if (goal == null && t != null) {
			goal = t.goal();
		}

		// The events since the task started. CANCELED events before the first CALC_STARTED come from the stop of
		// the task before, so they are left out.
		List<String> taskEvents = new ArrayList<>();
		synchronized (BaritoneActions.class) {
			long from = t == null ? 0 : t.firstEventId();
			for (EventEntry e : EVENTS) {
				if (e.id() < from || (taskEvents.isEmpty() && e.event().equals("CANCELED"))) {
					continue;
				}
				taskEvents.add(e.event());
			}
		}
		boolean failed = !taskEvents.isEmpty()
				&& FAIL_EVENTS.contains(PathEvent.valueOf(lastPathEnd(taskEvents)));
		JsonArray events = new JsonArray();
		taskEvents.subList(Math.max(0, taskEvents.size() - EVENTS_SHOWN), taskEvents.size()).forEach(events::add);

		Boolean inGoal = null;
		if (player != null && t != null && t.goal() != null) {
			BlockPos feet = player.blockPosition();
			inGoal = t.goal().isInGoal(feet.getX(), feet.getY(), feet.getZ());
		}
		String state;
		if (active) {
			state = "running";
		} else if (t == null) {
			state = "idle";
		} else if (Boolean.TRUE.equals(inGoal)) {
			state = "at_goal";
		} else if (t.canceled()) {
			state = "canceled";
		} else if (failed) {
			state = "failed";
		} else {
			state = t.goal() != null ? "stopped" : "finished";
		}

		o.addProperty("state", state);
		o.addProperty("active", active);
		o.addProperty("process", processName(b));
		o.addProperty("goal", goal == null ? null : shorten(goal.toString()));
		if (inGoal != null) {
			o.addProperty("inGoal", inGoal);
		}
		if (t != null) {
			JsonObject tj = new JsonObject();
			tj.addProperty("kind", t.kind());
			tj.addProperty("description", t.description());
			tj.addProperty("elapsedMs", (System.nanoTime() - t.startNanos()) / 1_000_000);
			o.add("task", tj);
			AttackJob job = attack;
			// path_cancel replaces the task with a canceled copy, so compare the start time.
			if (job != null && job.task.startNanos() == t.startNanos()) {
				o.add("attack", attackJson(job));
			}
		}
		if (active) {
			b.getPathingBehavior().estimatedTicksToGoal().ifPresent(ticks ->
					o.addProperty("estimatedSecondsToGoal", SurroundingsActions.round(ticks / 20.0)));
		}
		if (player != null) {
			o.add("position", TeleportActions.position(player));
		}
		o.add("events", events);
		return o;
	}

	/** The last event that is not CANCELED (Baritone sends CANCELED after a normal end too). */
	private static String lastPathEnd(List<String> events) {
		for (int i = events.size() - 1; i >= 0; i--) {
			if (!events.get(i).equals("CANCELED")) {
				return events.get(i);
			}
		}
		return "CANCELED";
	}

	// ---------------------------------------------------------------- params

	private static Integer integer(JsonObject params, String name) {
		JsonElement e = params.get(name);
		return e == null || e.isJsonNull() ? null : (int) Math.floor(e.getAsDouble());
	}

	private static String string(JsonObject params, String name) {
		JsonElement e = params.get(name);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}
}
