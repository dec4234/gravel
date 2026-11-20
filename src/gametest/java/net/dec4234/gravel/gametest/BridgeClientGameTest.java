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
		recreateWorld(context);
		leaveWorld(context);
	}

	// ---------------------------------------------------------------- steps

	private void healthAndActionList(ClientGameTestContext context) {
		JsonObject health = get(context, "/health");
		check(health.get("ok").getAsBoolean(), "health is ok");

		JsonArray actions = get(context, "/actions").getAsJsonArray("actions");
		for (String name : new String[]{"status", "shutdown", "join_world", "open_chat", "send_chat", "read_chat"}) {
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
