package net.dec4234.gravel.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.dec4234.gravel.client.bridge.BridgeServer;
import net.dec4234.gravel.client.bridge.WorldActions;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Drives the mod through its real HTTP bridge, the same way the MCP server does.
 *
 * <p>In a client game test the game only ticks while the test waits (waitTick, waitFor, ...). Bridge actions
 * run on the client thread, so each HTTP call is sent async and the test waits ticks until the reply comes.
 */
public class BridgeClientGameTest implements FabricClientGameTest {
	private static final String WORLD = "gravel_gametest";
	private static final int WORLD_LOAD_TIMEOUT_TICKS = 20 * 300;

	private final HttpClient http = HttpClient.newHttpClient();
	private final String base = "http://127.0.0.1:" + BridgeServer.configuredPort();

	@Override
	public void runTest(ClientGameTestContext context) {
		healthAndActionList(context);
		statusOnTitleScreen(context);
		chatActionsNeedAWorld(context);
		joinWorldCreatesNewWorld(context);
		joinSameWorldAgainDoesNothing(context);
		sendCommandAndReadFeedback(context);
		sendChatMessage(context);
		openChatScreen(context);
		selectHotbarSlot(context);
		moveItemsInInventory(context);
		pauseAndOptionsMenus(context);
		changeOptions(context);
		seeSurroundings(context);
		recreateWorld(context);
		leaveWorld(context);
	}

	// ---------------------------------------------------------------- steps

	private void healthAndActionList(ClientGameTestContext context) {
		JsonObject health = get(context, "/health");
		check(health.get("ok").getAsBoolean(), "health is ok");

		JsonArray actions = get(context, "/actions").getAsJsonArray("actions");
		for (String name : new String[]{"status", "shutdown", "join_world", "open_chat", "send_chat", "read_chat",
				"select_hotbar_slot", "open_inventory", "read_inventory", "click_slot", "move_item", "open_pause_menu",
				"open_options", "close_screen", "list_options", "set_option", "scan_blocks", "list_entities", "list_players",
				"debug_screen", "screenshot", "press_key", "enter_text"}) {
			check(actions.contains(new com.google.gson.JsonPrimitive(name)), "action list has " + name);
		}
	}

	private void statusOnTitleScreen(ClientGameTestContext context) {
		context.waitForScreen(TitleScreen.class);
		JsonObject status = action(context, "status", new JsonObject());
		check(!status.get("inWorld").getAsBoolean(), "not in a world on the title screen");
		checkEquals("TitleScreen", string(status, "screen"), "screen");
		check(!status.has("player"), "no player data outside a world");
	}

	private void chatActionsNeedAWorld(ClientGameTestContext context) {
		JsonObject params = new JsonObject();
		params.addProperty("message", "hello");
		Reply reply = post(context, "/action/send_chat", params);
		checkEquals(500, reply.status(), "send_chat outside a world fails");
		check(reply.body().get("error").getAsString().contains("Not in a world"), "error says why: " + reply.body());
	}

	private void joinWorldCreatesNewWorld(ClientGameTestContext context) {
		JsonObject params = new JsonObject();
		params.addProperty("name", WORLD);
		params.addProperty("recreate", true); // a world left over from an earlier run must not change the result
		JsonObject started = action(context, "join_world", params);
		checkEquals("recreate", string(started, "action"), "join_world action");
		check(started.get("scheduled").getAsBoolean(), "join is scheduled");

		waitUntilInWorld(context);

		JsonObject status = action(context, "status", new JsonObject());
		check(status.get("inWorld").getAsBoolean(), "in world after join");
		checkEquals(WORLD, string(status, "world"), "world name");
		check(string(status, "worldError") == null, "no world error: " + status);
		JsonObject player = status.getAsJsonObject("player");
		check(player != null && player.get("health").getAsFloat() > 0, "player data present: " + status);
	}

	private void joinSameWorldAgainDoesNothing(ClientGameTestContext context) {
		JsonObject params = new JsonObject();
		params.addProperty("name", WORLD);
		JsonObject result = action(context, "join_world", params);
		check(result.has("alreadyInWorld") && result.get("alreadyInWorld").getAsBoolean(), "already in world: " + result);
	}

	private void sendCommandAndReadFeedback(ClientGameTestContext context) {
		JsonObject params = new JsonObject();
		params.addProperty("message", "/time set day");
		JsonObject sent = action(context, "send_chat", params);
		check(sent.get("command").getAsBoolean(), "sent as a command");
		long before = sent.get("chatIdBeforeSend").getAsLong();

		JsonArray replies = waitForChat(context, before);
		check(replies.size() > 0, "command gave feedback");
	}

	private void sendChatMessage(ClientGameTestContext context) {
		String text = "gravel gametest " + System.nanoTime();
		JsonObject params = new JsonObject();
		params.addProperty("message", text);
		JsonObject sent = action(context, "send_chat", params);
		check(!sent.get("command").getAsBoolean(), "sent as chat");

		JsonArray replies = waitForChat(context, sent.get("chatIdBeforeSend").getAsLong());
		boolean found = false;
		for (JsonElement e : replies) {
			found |= e.getAsJsonObject().get("text").getAsString().contains(text);
		}
		check(found, "own chat message came back: " + replies);
	}

	private void openChatScreen(ClientGameTestContext context) {
		JsonObject params = new JsonObject();
		params.addProperty("text", "/give @s stone");
		action(context, "open_chat", params);
		context.waitForScreen(ChatScreen.class);
		context.setScreen(() -> null);
	}

	private void selectHotbarSlot(ClientGameTestContext context) {
		JsonObject params = new JsonObject();
		params.addProperty("slot", 3);
		JsonObject result = action(context, "select_hotbar_slot", params);
		checkEquals(3, result.get("selectedSlot").getAsInt(), "selected slot");
		context.runOnClient(mc -> checkEquals(3, mc.player.getInventory().getSelectedSlot(), "player selected slot"));

		params.addProperty("slot", 9);
		checkEquals(500, post(context, "/action/select_hotbar_slot", params).status(), "slot 9 is not a hotbar slot");

		params.addProperty("slot", 0);
		action(context, "select_hotbar_slot", params);
	}

	private void moveItemsInInventory(ClientGameTestContext context) {
		JsonObject clear = new JsonObject();
		clear.addProperty("message", "/clear @s");
		action(context, "send_chat", clear);
		JsonObject give = new JsonObject();
		give.addProperty("message", "/give @s minecraft:stone 10");
		action(context, "send_chat", give);
		context.waitFor(mc -> mc.player.getInventory().getItem(0).getCount() == 10, 200);

		checkEquals(500, post(context, "/action/move_item", new JsonObject()).status(), "move_item needs an open inventory");

		JsonObject opened = action(context, "open_inventory", new JsonObject());
		check(string(opened, "screen") != null, "a screen opened: " + opened);

		// Hotbar slot 0 is menu slot 36 in the player inventory menu.
		checkEquals(10, slotCount(context, 36), "stone in hotbar before the move");

		JsonObject move = new JsonObject();
		move.addProperty("from", 36);
		move.addProperty("to", 9);
		move.addProperty("count", 4);
		JsonObject moved = action(context, "move_item", move);
		checkEquals(6, moved.getAsJsonObject("from").get("count").getAsInt(), "items left in from slot");
		checkEquals(4, moved.getAsJsonObject("to").get("count").getAsInt(), "items in to slot");
		checkEquals(0, moved.getAsJsonObject("carried").get("count").getAsInt(), "cursor empty after move");

		// Move the whole stack, with a raw click pair.
		JsonObject click = new JsonObject();
		click.addProperty("slot", 36);
		action(context, "click_slot", click);
		click.addProperty("slot", 9);
		JsonObject placed = action(context, "click_slot", click);
		checkEquals(10, placed.getAsJsonObject("slotNow").get("count").getAsInt(), "stacks merged by clicks");
		checkEquals(0, slotCount(context, 36), "hotbar slot empty");

		context.waitFor(mc -> mc.player.getInventory().getItem(9).getCount() == 10, 100);

		JsonObject closed = action(context, "close_screen", new JsonObject());
		check(string(closed, "screen") == null, "inventory closed: " + closed);
	}

	private void pauseAndOptionsMenus(ClientGameTestContext context) {
		JsonObject paused = action(context, "open_pause_menu", new JsonObject());
		checkEquals("PauseScreen", string(paused, "screen"), "pause menu open");

		JsonObject options = action(context, "open_options", new JsonObject());
		checkEquals("OptionsScreen", string(options, "screen"), "options menu open");

		JsonObject back = action(context, "close_screen", new JsonObject());
		checkEquals("PauseScreen", string(back, "screen"), "options close back to the pause menu");

		JsonObject resumed = action(context, "close_screen", new JsonObject());
		check(string(resumed, "screen") == null, "pause menu closed: " + resumed);
	}

	private void changeOptions(ClientGameTestContext context) {
		JsonObject filter = new JsonObject();
		filter.addProperty("filter", "fov");
		JsonObject listed = action(context, "list_options", filter).getAsJsonObject("options");
		check(listed.has("fov"), "fov is listed: " + listed);
		String oldFov = listed.get("fov").getAsString();

		checkEquals("95", setOption(context, "fov", "95"), "fov after set");
		context.runOnClient(mc -> checkEquals(95, mc.options.fov().get(), "fov in game"));
		setOption(context, "fov", oldFov);

		// An enum option, given as a bare word.
		String oldHand = action(context, "list_options", new JsonObject()).getAsJsonObject("options").get("mainHand").getAsString();
		checkEquals("LEFT", setOption(context, "mainHand", "left"), "main hand after set");
		setOption(context, "mainHand", oldHand);

		// A plain field and a key binding.
		setOption(context, "pauseOnLostFocus", "false");
		context.runOnClient(mc -> check(!mc.options.pauseOnLostFocus, "pauseOnLostFocus is off"));
		setOption(context, "pauseOnLostFocus", "true");

		checkEquals("key.keyboard.j", setOption(context, "key_key.jump", "key.keyboard.j"), "jump key after set");
		setOption(context, "key_key.jump", "key.keyboard.space");

		JsonObject bad = new JsonObject();
		bad.addProperty("name", "fov");
		bad.addProperty("value", "banana");
		checkEquals(500, post(context, "/action/set_option", bad).status(), "bad value is refused");
		bad.addProperty("value", "1000");
		checkEquals(500, post(context, "/action/set_option", bad).status(), "out of range value is refused");
		bad.addProperty("name", "no_such_option");
		checkEquals(500, post(context, "/action/set_option", bad).status(), "unknown option is refused");
	}

	private String setOption(ClientGameTestContext context, String name, String value) {
		JsonObject params = new JsonObject();
		params.addProperty("name", name);
		params.addProperty("value", value);
		return action(context, "set_option", params).get("value").getAsString();
	}

	private int slotCount(ClientGameTestContext context, int slot) {
		for (JsonElement e : action(context, "read_inventory", new JsonObject()).getAsJsonArray("slots")) {
			JsonObject s = e.getAsJsonObject();
			if (s.get("slot").getAsInt() == slot) {
				return s.get("count").getAsInt();
			}
		}
		return 0;
	}

	private void seeSurroundings(ClientGameTestContext context) {
		command(context, "/setblock ~2 ~ ~ minecraft:gold_block");
		command(context, "/summon minecraft:pig ~3 ~ ~ {NoAI:1b}");

		JsonObject scanParams = new JsonObject();
		scanParams.addProperty("radius", 4);
		scanParams.addProperty("filter", "gold_block");
		JsonObject scan = action(context, "scan_blocks", scanParams);
		JsonArray blocks = scan.getAsJsonArray("blocks");
		checkEquals(1, blocks.size(), "one kind of block matches the filter: " + scan);
		JsonObject gold = blocks.get(0).getAsJsonObject();
		checkEquals("minecraft:gold_block", string(gold, "block"), "scanned block id");
		checkEquals(1, gold.get("count").getAsInt(), "one gold block");
		check(scan.has("standingOn") && scan.has("lookingAt"), "scan has standingOn and lookingAt: " + scan);

		JsonObject all = action(context, "scan_blocks", new JsonObject());
		check(all.get("kinds").getAsInt() > 1, "default scan sees several kinds of blocks: " + all.get("kinds"));

		// The new pig needs a few ticks to get to the client.
		JsonObject entityParams = new JsonObject();
		entityParams.addProperty("type", "pig");
		JsonObject entities = null;
		for (int i = 0; i < 100; i++) {
			entities = action(context, "list_entities", entityParams);
			if (entities.get("total").getAsInt() > 0) {
				break;
			}
			context.waitTicks(2);
		}
		check(entities.get("total").getAsInt() > 0, "list_entities sees the pig: " + entities);
		JsonObject pig = entities.getAsJsonArray("entities").get(0).getAsJsonObject();
		checkEquals("minecraft:pig", string(pig, "type"), "entity type");
		check(pig.get("distance").getAsDouble() < 8, "pig is near: " + pig);
		check(pig.has("health"), "pig has health: " + pig);

		JsonObject players = action(context, "list_players", new JsonObject());
		checkEquals(0, players.get("nearbyCount").getAsInt(), "no other players nearby: " + players);
		check(players.has("self"), "list_players names the player: " + players);

		JsonObject debug = action(context, "debug_screen", new JsonObject());
		JsonArray debugEntries = debug.getAsJsonArray("entries");
		java.util.Set<String> debugIds = new java.util.HashSet<>();
		for (com.google.gson.JsonElement e : debugEntries) {
			debugIds.add(string(e.getAsJsonObject(), "id"));
		}
		for (String id : new String[]{"minecraft:game_version", "minecraft:fps", "minecraft:player_position",
				"minecraft:biome", "minecraft:memory"}) {
			check(debugIds.contains(id), "debug_screen has " + id + ": " + debugIds);
		}
		check(!debug.has("errors"), "debug_screen entries have no errors: " + debug.get("errors"));
		check(debug.getAsJsonArray("renderToggles").size() > 0, "debug_screen lists render toggles");
	}

	/** Sends a command and waits for its feedback. */
	private void command(ClientGameTestContext context, String message) {
		JsonObject params = new JsonObject();
		params.addProperty("message", message);
		JsonObject sent = action(context, "send_chat", params);
		waitForChat(context, sent.get("chatIdBeforeSend").getAsLong());
	}

	private void recreateWorld(ClientGameTestContext context) {
		JsonObject params = new JsonObject();
		params.addProperty("name", WORLD);
		params.addProperty("recreate", true);
		JsonObject started = action(context, "join_world", params);
		check(started.get("existed").getAsBoolean(), "world existed before recreate");
		check(started.get("wasInWorld").getAsBoolean(), "was in a world before recreate");

		// Wait for the old world to close first, so it is not taken as the new one.
		context.waitFor(mc -> mc.level == null, WORLD_LOAD_TIMEOUT_TICKS);
		waitUntilInWorld(context);
	}

	private void leaveWorld(ClientGameTestContext context) {
		// The game test must end on the title screen, so go there directly instead of via the saving screen.
		context.runOnClient(mc -> mc.disconnect(new TitleScreen(), false));
		context.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, WORLD_LOAD_TIMEOUT_TICKS);
		context.waitForScreen(TitleScreen.class);

		JsonObject status = action(context, "status", new JsonObject());
		check(!status.get("inWorld").getAsBoolean(), "left the world");
	}

	// ---------------------------------------------------------------- helpers

	private void waitUntilInWorld(ClientGameTestContext context) {
		context.waitFor(mc -> {
			if (WorldActions.lastError() != null) {
				throw new AssertionError("World error: " + WorldActions.lastError());
			}
			Screen screen = mc.gui.screen();
			String screenName = screen == null ? "" : screen.getClass().getSimpleName();
			boolean loading = screenName.equals("LevelLoadingScreen") || screenName.equals("ReceivingLevelScreen");
			return mc.player != null && mc.level != null && !loading && WORLD.equals(WorldActions.currentWorld(mc));
		}, WORLD_LOAD_TIMEOUT_TICKS);
	}

	/** Reads chat after the given id until at least one line is there. */
	private JsonArray waitForChat(ClientGameTestContext context, long sinceId) {
		JsonObject params = new JsonObject();
		params.addProperty("since_id", sinceId);
		params.addProperty("limit", 100);
		for (int i = 0; i < 100; i++) {
			JsonArray lines = action(context, "read_chat", params).getAsJsonArray("messages");
			if (!lines.isEmpty()) {
				return lines;
			}
			context.waitTicks(2);
		}
		throw new AssertionError("No chat lines after id " + sinceId);
	}

	private record Reply(int status, JsonObject body) {
	}

	private JsonObject action(ClientGameTestContext context, String name, JsonObject params) {
		Reply reply = post(context, "/action/" + name, params);
		checkEquals(200, reply.status(), "HTTP status of " + name + " (" + reply.body() + ")");
		return reply.body();
	}

	private JsonObject get(ClientGameTestContext context, String path) {
		Reply reply = await(context, http.sendAsync(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
				HttpResponse.BodyHandlers.ofString()));
		checkEquals(200, reply.status(), "HTTP status of GET " + path);
		return reply.body();
	}

	private Reply post(ClientGameTestContext context, String path, JsonObject params) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(params.toString()))
				.build();
		return await(context, http.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
	}

	/** Ticks the game until the HTTP call is done. The bridge needs ticks to run the action on the client thread. */
	private static Reply await(ClientGameTestContext context, CompletableFuture<HttpResponse<String>> future) {
		context.waitFor(mc -> future.isDone());
		HttpResponse<String> res = future.join();
		String body = res.body();
		return new Reply(res.statusCode(), body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject());
	}

	private static String string(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("Check failed: " + what);
		}
	}

	private static void checkEquals(Object expected, Object actual, String what) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
