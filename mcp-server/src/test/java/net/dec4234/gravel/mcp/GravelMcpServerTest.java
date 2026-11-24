package net.dec4234.gravel.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GravelMcpServerTest {
	@TempDir
	Path project;

	/** Collects each line the server writes to its protocol channel. */
	private static final class LineSink extends OutputStream {
		final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

		@Override
		public synchronized void write(int b) {
			if (b == '\n') {
				lines.add(buf.toString(StandardCharsets.UTF_8).stripTrailing());
				buf.reset();
			} else {
				buf.write(b);
			}
		}

		JsonObject next() throws InterruptedException {
			String line = lines.poll(10, TimeUnit.SECONDS);
			assertNotNull(line, "server sent no reply in time");
			return JsonParser.parseString(line).getAsJsonObject();
		}
	}

	private final LineSink sink = new LineSink();

	private GravelMcpServer server(int bridgePort) {
		return new GravelMcpServer(new PrintStream(sink, true, StandardCharsets.UTF_8), project, bridgePort);
	}

	private static JsonObject request(int id, String method, JsonObject params) {
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("id", id);
		msg.addProperty("method", method);
		if (params != null) {
			msg.add("params", params);
		}
		return msg;
	}

	private static JsonObject toolCall(int id, String tool, JsonObject args) {
		JsonObject params = new JsonObject();
		params.addProperty("name", tool);
		params.add("arguments", args == null ? new JsonObject() : args);
		return request(id, "tools/call", params);
	}

	private static String toolText(JsonObject reply) {
		return reply.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
	}

	private static boolean toolIsError(JsonObject reply) {
		return reply.getAsJsonObject("result").get("isError").getAsBoolean();
	}

	// ---------------------------------------------------------------- protocol

	@Test
	void initializeEchoesProtocolVersion() throws Exception {
		JsonObject params = new JsonObject();
		params.addProperty("protocolVersion", "2099-01-01");
		server(FakeBridge.unusedPort()).handle(request(1, "initialize", params));

		JsonObject reply = sink.next();
		assertEquals(1, reply.get("id").getAsInt());
		JsonObject result = reply.getAsJsonObject("result");
		assertEquals("2099-01-01", result.get("protocolVersion").getAsString());
		assertEquals("gravel", result.getAsJsonObject("serverInfo").get("name").getAsString());
		assertTrue(result.getAsJsonObject("capabilities").has("tools"));
	}

	@Test
	void initializeWithoutVersionUsesFallback() throws Exception {
		server(FakeBridge.unusedPort()).handle(request(1, "initialize", null));
		assertFalse(sink.next().getAsJsonObject("result").get("protocolVersion").getAsString().isBlank());
	}

	@Test
	void pingReturnsEmptyResult() throws Exception {
		server(FakeBridge.unusedPort()).handle(request(5, "ping", null));
		JsonObject reply = sink.next();
		assertEquals(5, reply.get("id").getAsInt());
		assertTrue(reply.getAsJsonObject("result").isEmpty());
	}

	@Test
	void notificationsAndResponsesGetNoReply() throws Exception {
		GravelMcpServer server = server(FakeBridge.unusedPort());
		JsonObject notification = new JsonObject();
		notification.addProperty("jsonrpc", "2.0");
		notification.addProperty("method", "notifications/initialized");
		server.handle(notification);

		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.addProperty("id", 9);
		response.add("result", new JsonObject());
		server.handle(response);

		server.handle(request(10, "ping", null));
		assertEquals(10, sink.next().get("id").getAsInt(), "only the ping should get a reply");
	}

	@Test
	void unknownMethodIsError() throws Exception {
		server(FakeBridge.unusedPort()).handle(request(2, "does/not/exist", null));
		JsonObject error = sink.next().getAsJsonObject("error");
		assertEquals(-32601, error.get("code").getAsInt());
	}

	@Test
	void toolsListHasAllToolsWithSchemas() throws Exception {
		server(FakeBridge.unusedPort()).handle(request(3, "tools/list", null));
		JsonArray tools = sink.next().getAsJsonObject("result").getAsJsonArray("tools");

		List<String> names = new ArrayList<>();
		tools.forEach(t -> {
			JsonObject o = t.getAsJsonObject();
			names.add(o.get("name").getAsString());
			assertFalse(o.get("description").getAsString().isBlank());
			assertEquals("object", o.getAsJsonObject("inputSchema").get("type").getAsString());
		});
		assertEquals(List.of("launch_minecraft", "stop_minecraft", "minecraft_status", "join_world",
				"join_server", "read_disconnects", "open_chat", "send_chat", "read_chat", "wait_for", "select_hotbar_slot",
				"open_inventory", "read_inventory",
				"move_item", "click_slot", "open_pause_menu", "open_options", "close_screen", "list_options",
				"set_option", "chunk_info", "teleport", "scan_blocks", "list_entities", "list_players",
				"debug_screen", "screenshot", "press_key", "enter_text", "read_screen", "interact_widget", "look", "move", "jump", "attack", "use", "reload_resources", "reload_chunks", "path_to", "path_status", "path_cancel", "mine", "follow", "attack_entity", "baritone_command",
				"baritone_settings", "minecraft_launch_log", "list_minecraft_logs", "scan_minecraft_log"), names);
	}

	@Test
	void unknownToolIsError() throws Exception {
		server(FakeBridge.unusedPort()).handle(toolCall(4, "no_such_tool", null));
		JsonObject error = sink.next().getAsJsonObject("error");
		assertEquals(-32602, error.get("code").getAsInt());
		assertTrue(error.get("message").getAsString().contains("no_such_tool"));
	}

	@Test
	void serveSkipsBlankLinesAndReportsParseErrors() throws Exception {
		String input = "\n{not json\n" + request(7, "ping", null) + "\n";
		server(FakeBridge.unusedPort()).serve(new BufferedReader(new StringReader(input)));

		JsonObject parseError = sink.next();
		assertTrue(parseError.get("id").isJsonNull());
		assertEquals(-32700, parseError.getAsJsonObject("error").get("code").getAsInt());
		assertEquals(7, sink.next().get("id").getAsInt());
	}

	// ---------------------------------------------------------------- tools

	@Test
	void statusWhenBridgeIsDown() throws Exception {
		server(FakeBridge.unusedPort()).handle(toolCall(1, "minecraft_status", null));
		JsonObject reply = sink.next();
		assertFalse(toolIsError(reply));
		JsonObject status = JsonParser.parseString(toolText(reply)).getAsJsonObject();
		assertFalse(status.get("launcherProcessAlive").getAsBoolean());
		assertFalse(status.get("bridgeReachable").getAsBoolean());
		assertFalse(status.has("game"));
	}

	@Test
	void statusIncludesGameStateWhenBridgeIsUp() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("status", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("inWorld", true);
				return FakeBridge.Reply.ok(o);
			});
			server(fake.port()).handle(toolCall(1, "minecraft_status", null));
			JsonObject status = JsonParser.parseString(toolText(sink.next())).getAsJsonObject();
			assertTrue(status.get("bridgeReachable").getAsBoolean());
			assertTrue(status.getAsJsonObject("game").get("inWorld").getAsBoolean());
		}
	}

	@Test
	void bridgeFailureBecomesToolError() throws Exception {
		server(FakeBridge.unusedPort()).handle(toolCall(1, "read_chat", null));
		JsonObject reply = sink.next();
		assertTrue(toolIsError(reply));
		assertFalse(toolText(reply).isBlank());
	}

	@Test
	void readChatPassesDefaults() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("read_chat", params -> FakeBridge.Reply.ok(new JsonObject()));
			server(fake.port()).handle(toolCall(1, "read_chat", null));
			assertFalse(toolIsError(sink.next()));

			JsonObject sent = fake.calls.getFirst().params();
			assertEquals(0, sent.get("since_id").getAsInt());
			assertEquals(50, sent.get("limit").getAsInt());
			assertFalse(sent.get("include_action_bar").getAsBoolean());
		}
	}

	@Test
	void openChatPassesTextOnlyWhenGiven() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("open_chat", params -> FakeBridge.Reply.ok(new JsonObject()));
			GravelMcpServer server = server(fake.port());

			server.handle(toolCall(1, "open_chat", null));
			assertEquals("Chat screen opened.", toolText(sink.next()));
			assertFalse(fake.calls.get(0).params().has("text"));

			JsonObject args = new JsonObject();
			args.addProperty("text", "/give");
			server.handle(toolCall(2, "open_chat", args));
			sink.next();
			assertEquals("/give", fake.calls.get(1).params().get("text").getAsString());
		}
	}

	@Test
	void sendChatReturnsRepliesAfterSend() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("send_chat", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("sent", params.get("message").getAsString());
				o.addProperty("chatIdBeforeSend", 41);
				return FakeBridge.Reply.ok(o);
			});
			fake.on("read_chat", params -> {
				JsonObject line = new JsonObject();
				line.addProperty("text", "Set the time to 1000");
				JsonArray messages = new JsonArray();
				messages.add(line);
				JsonObject o = new JsonObject();
				o.add("messages", messages);
				o.addProperty("latestId", 42);
				return FakeBridge.Reply.ok(o);
			});

			JsonObject args = new JsonObject();
			args.addProperty("message", "/time set day");
			args.addProperty("wait_ms", 0);
			server(fake.port()).handle(toolCall(1, "send_chat", args));

			JsonObject result = JsonParser.parseString(toolText(sink.next())).getAsJsonObject();
			assertEquals("/time set day", result.get("sent").getAsString());
			assertEquals(42, result.get("latestId").getAsInt());
			assertEquals("Set the time to 1000",
					result.getAsJsonArray("replies").get(0).getAsJsonObject().get("text").getAsString());
			// The read must start after the last line seen before the send.
			assertEquals(41, fake.calls.get(1).params().get("since_id").getAsLong());
		}
	}

	@Test
	void sendChatWaitsForReplyInGameInsteadOfSleeping() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("send_chat", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("sent", params.get("message").getAsString());
				o.addProperty("chatIdBeforeSend", 41);
				return FakeBridge.Reply.ok(o);
			});
			fake.on("wait_for", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("matched", true);
				o.addProperty("elapsedMs", 120);
				return FakeBridge.Reply.ok(o);
			});
			fake.on("read_chat", params -> {
				JsonObject o = new JsonObject();
				o.add("messages", new JsonArray());
				o.addProperty("latestId", 43);
				return FakeBridge.Reply.ok(o);
			});

			JsonObject args = new JsonObject();
			args.addProperty("message", "/give @s stone");
			args.addProperty("wait_ms", 5000);
			args.addProperty("wait_for", "Gave");
			server(fake.port()).handle(toolCall(1, "send_chat", args));
			JsonObject reply = sink.next();
			assertFalse(toolIsError(reply), toolText(reply));

			assertEquals(List.of("send_chat", "wait_for", "read_chat"),
					fake.calls.stream().map(FakeBridge.Call::action).toList());
			JsonObject wait = fake.calls.get(1).params();
			assertEquals("Gave", wait.get("chat").getAsString());
			assertEquals(41, wait.get("since_id").getAsLong());
			assertEquals(5000, wait.get("timeout_ms").getAsInt());
			JsonObject result = JsonParser.parseString(toolText(reply)).getAsJsonObject();
			assertTrue(result.get("replyFound").getAsBoolean());
			assertEquals(120, result.get("waitedMs").getAsInt());
		}
	}

	@Test
	void sendChatRejectsTooLongWait() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("send_chat", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("sent", "hi");
				o.addProperty("chatIdBeforeSend", 1);
				return FakeBridge.Reply.ok(o);
			});
			JsonObject args = new JsonObject();
			args.addProperty("message", "hi");
			args.addProperty("wait_ms", GravelMcpServer.MAX_WAIT_MS + 1);
			server(fake.port()).handle(toolCall(1, "send_chat", args));
			assertTrue(toolIsError(sink.next()));
		}
	}

	@Test
	void waitForPassesOnlyGivenArguments() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("wait_for", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("matched", true);
				o.addProperty("stoppedBy", "condition");
				return FakeBridge.Reply.ok(o);
			});
			JsonObject args = new JsonObject();
			args.addProperty("screen", "none");
			args.addProperty("ticks", 40);
			server(fake.port()).handle(toolCall(1, "wait_for", args));
			JsonObject reply = sink.next();
			assertFalse(toolIsError(reply), toolText(reply));

			JsonObject sent = fake.calls.getFirst().params();
			assertEquals(2, sent.size());
			assertEquals("none", sent.get("screen").getAsString());
			assertEquals(40, sent.get("ticks").getAsInt());
			assertEquals("condition", JsonParser.parseString(toolText(reply)).getAsJsonObject().get("stoppedBy").getAsString());
		}
	}

	@Test
	void joinWorldDefaultsToTempAndReportsAlreadyInWorld() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("join_world", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("alreadyInWorld", true);
				return FakeBridge.Reply.ok(o);
			});
			server(fake.port()).handle(toolCall(1, "join_world", null));

			assertEquals("Already in world 'temp'.", toolText(sink.next()));
			JsonObject sent = fake.calls.getFirst().params();
			assertEquals("temp", sent.get("name").getAsString());
			assertFalse(sent.get("recreate").getAsBoolean());
		}
	}

	@Test
	void joinWorldWaitsUntilPlayerIsInWorld() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("join_world", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("action", "create");
				o.addProperty("wasInWorld", false);
				return FakeBridge.Reply.ok(o);
			});
			fake.on("status", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("inWorld", true);
				o.addProperty("world", "mine");
				o.addProperty("screen", (String) null);
				return FakeBridge.Reply.ok(o);
			});
			JsonObject args = new JsonObject();
			args.addProperty("name", "mine");
			server(fake.port()).handle(toolCall(1, "join_world", args));

			String text = toolText(sink.next());
			assertTrue(text.startsWith("Joined world 'mine' (create)."), text);
		}
	}

	@Test
	void joinWorldReportsWorldError() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("join_world", params -> FakeBridge.Reply.ok(new JsonObject()));
			fake.on("status", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("inWorld", false);
				o.addProperty("worldError", "disk full");
				return FakeBridge.Reply.ok(o);
			});
			server(fake.port()).handle(toolCall(1, "join_world", null));

			JsonObject reply = sink.next();
			assertTrue(toolIsError(reply));
			assertTrue(toolText(reply).contains("disk full"));
		}
	}

	private static JsonObject serverStatus(boolean inWorld, String server, JsonObject attempt) {
		JsonObject conn = new JsonObject();
		conn.addProperty("server", server);
		conn.add("attempt", attempt);
		JsonObject o = new JsonObject();
		o.addProperty("inWorld", inWorld);
		o.addProperty("screen", (String) null);
		o.add("serverConnection", conn);
		return o;
	}

	private static JsonObject attempt(long id, String state) {
		JsonObject a = new JsonObject();
		a.addProperty("id", id);
		a.addProperty("address", "localhost");
		a.addProperty("state", state);
		return a;
	}

	private static FakeBridge.Reply joinStarted(JsonObject params) {
		JsonObject o = new JsonObject();
		o.addProperty("attemptId", 7);
		o.addProperty("scheduled", true);
		return FakeBridge.Reply.ok(o);
	}

	private static JsonObject addressArgs(String address) {
		JsonObject args = new JsonObject();
		args.addProperty("address", address);
		return args;
	}

	@Test
	void joinServerNeedsAddress() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			server(fake.port()).handle(toolCall(1, "join_server", null));

			assertTrue(toolIsError(sink.next()));
			assertTrue(fake.calls.isEmpty());
		}
	}

	@Test
	void joinServerWaitsUntilJoined() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("join_server", GravelMcpServerTest::joinStarted);
			fake.on("status", params -> FakeBridge.Reply.ok(serverStatus(true, "localhost", attempt(7, "joined"))));
			JsonObject args = addressArgs(" localhost ");
			args.addProperty("resource_pack", "enabled");
			server(fake.port()).handle(toolCall(1, "join_server", args));

			String text = toolText(sink.next());
			assertTrue(text.startsWith("Joined server 'localhost'."), text);
			JsonObject sent = fake.calls.getFirst().params();
			assertEquals("localhost", sent.get("address").getAsString());
			assertEquals("enabled", sent.get("resource_pack").getAsString());
		}
	}

	@Test
	void joinServerReportsDisconnectScreenText() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("join_server", GravelMcpServerTest::joinStarted);
			fake.on("status", params -> {
				JsonObject d = new JsonObject();
				d.addProperty("title", "Failed to connect to the server");
				d.addProperty("reason", "Failed to verify username!");
				d.addProperty("hint", "The server is in online mode");
				JsonObject a = attempt(7, "failed");
				a.add("disconnect", d);
				return FakeBridge.Reply.ok(serverStatus(false, null, a));
			});
			server(fake.port()).handle(toolCall(1, "join_server", addressArgs("localhost")));

			JsonObject reply = sink.next();
			assertTrue(toolIsError(reply));
			String text = toolText(reply);
			assertTrue(text.contains("Could not join 'localhost': Failed to connect to the server: "
					+ "Failed to verify username!"), text);
			assertTrue(text.contains("Hint: The server is in online mode"), text);
		}
	}

	@Test
	void joinServerReportsModError() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("join_server", GravelMcpServerTest::joinStarted);
			fake.on("status", params -> {
				JsonObject a = attempt(7, "failed");
				a.addProperty("error", "The connect screen did not open");
				return FakeBridge.Reply.ok(serverStatus(false, null, a));
			});
			server(fake.port()).handle(toolCall(1, "join_server", addressArgs("localhost")));

			JsonObject reply = sink.next();
			assertTrue(toolIsError(reply));
			assertTrue(toolText(reply).contains("The connect screen did not open"));
		}
	}

	@Test
	void joinServerReportsAlreadyOnServer() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("join_server", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("alreadyOnServer", true);
				return FakeBridge.Reply.ok(o);
			});
			server(fake.port()).handle(toolCall(1, "join_server", addressArgs("localhost")));

			assertEquals("Already on server 'localhost'.", toolText(sink.next()));
			assertFalse(fake.calls.getFirst().params().has("resource_pack"));
		}
	}

	@Test
	void moveItemSendsCountOnlyWhenGiven() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("move_item", params -> FakeBridge.Reply.ok(new JsonObject()));
			GravelMcpServer server = server(fake.port());

			JsonObject args = new JsonObject();
			args.addProperty("from", 36);
			args.addProperty("to", 9);
			server.handle(toolCall(1, "move_item", args));
			assertFalse(toolIsError(sink.next()));
			JsonObject sent = fake.calls.get(0).params();
			assertEquals(36, sent.get("from").getAsInt());
			assertEquals(9, sent.get("to").getAsInt());
			assertFalse(sent.has("count"));

			args.addProperty("count", 5);
			server.handle(toolCall(2, "move_item", args));
			sink.next();
			assertEquals(5, fake.calls.get(1).params().get("count").getAsInt());
		}
	}

	@Test
	void clickSlotPassesDefaults() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("click_slot", params -> FakeBridge.Reply.ok(new JsonObject()));
			JsonObject args = new JsonObject();
			args.addProperty("slot", 40);
			server(fake.port()).handle(toolCall(1, "click_slot", args));
			assertFalse(toolIsError(sink.next()));

			JsonObject sent = fake.calls.getFirst().params();
			assertEquals(40, sent.get("slot").getAsInt());
			assertEquals("PICKUP", sent.get("action").getAsString());
			assertEquals(0, sent.get("button").getAsInt());
		}
	}

	@Test
	void setOptionPassesValueAsText() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("set_option", params -> FakeBridge.Reply.ok(new JsonObject()));
			JsonObject args = new JsonObject();
			args.addProperty("name", "fov");
			args.addProperty("value", 90);
			server(fake.port()).handle(toolCall(1, "set_option", args));
			assertFalse(toolIsError(sink.next()));

			JsonObject sent = fake.calls.getFirst().params();
			assertEquals("fov", sent.get("name").getAsString());
			assertEquals("90", sent.get("value").getAsString());
		}
	}

	@Test
	void teleportSendsCoordinatesAndReportsNewPosition() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("teleport", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("sent", "/tp @s 10 ~5 -3");
				o.addProperty("chatIdBeforeSend", 7);
				o.add("from", new JsonObject());
				return FakeBridge.Reply.ok(o);
			});
			fake.on("read_chat", params -> {
				JsonObject o = new JsonObject();
				o.add("messages", new JsonArray());
				return FakeBridge.Reply.ok(o);
			});
			fake.on("status", params -> {
				JsonObject player = new JsonObject();
				player.addProperty("x", 10.5);
				JsonObject o = new JsonObject();
				o.add("player", player);
				return FakeBridge.Reply.ok(o);
			});
			JsonObject args = new JsonObject();
			args.addProperty("x", 10);
			args.addProperty("y", "~5");
			args.addProperty("z", "-3");
			args.addProperty("wait_ms", 0);
			server(fake.port()).handle(toolCall(1, "teleport", args));
			JsonObject reply = sink.next();
			assertFalse(toolIsError(reply), toolText(reply));

			JsonObject sent = fake.calls.get(0).params();
			assertEquals("10", sent.get("x").getAsString());
			assertEquals("~5", sent.get("y").getAsString());
			assertFalse(sent.has("yaw"));
			assertEquals(7, fake.calls.get(1).params().get("since_id").getAsInt());
			JsonObject result = JsonParser.parseString(toolText(reply)).getAsJsonObject();
			assertEquals(10.5, result.getAsJsonObject("to").get("x").getAsDouble());
		}
	}

	@Test
	void reloadResourcesPassesOnlyGivenArguments() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("reload_resources", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("resourcesReloaded", false);
				o.addProperty("dataReloaded", true);
				return FakeBridge.Reply.ok(o);
			});
			JsonObject args = new JsonObject();
			args.addProperty("resources", false);
			server(fake.port()).handle(toolCall(1, "reload_resources", args));
			JsonObject reply = sink.next();
			assertFalse(toolIsError(reply), toolText(reply));

			JsonObject sent = fake.calls.getFirst().params();
			assertFalse(sent.get("resources").getAsBoolean());
			assertFalse(sent.has("data"));
			JsonObject result = JsonParser.parseString(toolText(reply)).getAsJsonObject();
			assertTrue(result.get("dataReloaded").getAsBoolean());
		}
	}

	@Test
	void reloadChunksPassesWaitTime() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("reload_chunks", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("allBuilt", true);
				return FakeBridge.Reply.ok(o);
			});
			JsonObject args = new JsonObject();
			args.addProperty("wait_ms", 500);
			server(fake.port()).handle(toolCall(1, "reload_chunks", args));
			JsonObject reply = sink.next();
			assertFalse(toolIsError(reply), toolText(reply));
			assertEquals(500, fake.calls.getFirst().params().get("wait_ms").getAsInt());
			assertTrue(JsonParser.parseString(toolText(reply)).getAsJsonObject().get("allBuilt").getAsBoolean());
		}
	}

	@Test
	void scanBlocksPassesOnlyGivenArguments() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("scan_blocks", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("blockCount", 3);
				return FakeBridge.Reply.ok(o);
			});
			JsonObject args = new JsonObject();
			args.addProperty("radius", 6);
			args.addProperty("filter", "ore");
			args.addProperty("exposed_only", true);
			args.addProperty("not_a_parameter", 1);
			server(fake.port()).handle(toolCall(1, "scan_blocks", args));
			JsonObject reply = sink.next();
			assertFalse(toolIsError(reply), toolText(reply));

			JsonObject sent = fake.calls.get(0).params();
			assertEquals(6, sent.get("radius").getAsInt());
			assertEquals("ore", sent.get("filter").getAsString());
			assertTrue(sent.get("exposed_only").getAsBoolean());
			assertFalse(sent.has("include_air"));
			assertFalse(sent.has("not_a_parameter"));
			assertEquals(3, JsonParser.parseString(toolText(reply)).getAsJsonObject().get("blockCount").getAsInt());
		}
	}

	@Test
	void listEntitiesAndPlayersCallTheirActions() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("list_entities", params -> FakeBridge.Reply.ok(new JsonObject()));
			fake.on("list_players", params -> FakeBridge.Reply.ok(new JsonObject()));
			JsonObject args = new JsonObject();
			args.addProperty("max_distance", 32.5);
			args.addProperty("type", "zombie,skeleton");
			server(fake.port()).handle(toolCall(1, "list_entities", args));
			assertFalse(toolIsError(sink.next()));
			server(fake.port()).handle(toolCall(2, "list_players", null));
			assertFalse(toolIsError(sink.next()));

			assertEquals("list_entities", fake.calls.get(0).action());
			assertEquals(32.5, fake.calls.get(0).params().get("max_distance").getAsDouble());
			assertEquals("zombie,skeleton", fake.calls.get(0).params().get("type").getAsString());
			assertEquals("list_players", fake.calls.get(1).action());
			assertEquals(0, fake.calls.get(1).params().size());
		}
	}

	@Test
	void debugScreenPassesOnlyShown() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("debug_screen", params -> {
				JsonObject o = new JsonObject();
				o.add("entries", new JsonArray());
				return FakeBridge.Reply.ok(o);
			});
			JsonObject args = new JsonObject();
			args.addProperty("only_shown", true);
			server(fake.port()).handle(toolCall(1, "debug_screen", args));
			JsonObject reply = sink.next();
			assertFalse(toolIsError(reply), toolText(reply));
			assertTrue(fake.calls.get(0).params().get("only_shown").getAsBoolean());

			server(fake.port()).handle(toolCall(2, "debug_screen", null));
			assertFalse(toolIsError(sink.next()));
			assertEquals(0, fake.calls.get(1).params().size());
		}
	}

	/** A PNG of the given size, base64, like the mod sends. */
	private static String pngBase64(int width, int height) throws Exception {
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, height,
				java.awt.image.BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, 0xFFFF0000);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", bytes);
		return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
	}

	private FakeBridge screenshotBridge(int width, int height, String screen) throws Exception {
		String png = pngBase64(width, height);
		FakeBridge fake = new FakeBridge();
		fake.on("screenshot", params -> {
			JsonObject o = new JsonObject();
			o.addProperty("width", width);
			o.addProperty("height", height);
			o.addProperty("screen", screen);
			o.addProperty("png", png);
			return FakeBridge.Reply.ok(o);
		});
		return fake;
	}

	private static java.awt.image.BufferedImage image(JsonObject content) throws Exception {
		byte[] bytes = java.util.Base64.getDecoder().decode(content.get("data").getAsString());
		return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
	}

	@Test
	void screenshotReturnsImageContent() throws Exception {
		try (FakeBridge fake = screenshotBridge(200, 100, "PauseScreen")) {
			server(fake.port()).handle(toolCall(1, "screenshot", null));
			JsonObject reply = sink.next();
			assertFalse(toolIsError(reply), toolText(reply));
			JsonArray content = reply.getAsJsonObject("result").getAsJsonArray("content");
			assertEquals(2, content.size());
			assertTrue(toolText(reply).contains("200x100"), toolText(reply));
			assertTrue(toolText(reply).contains("PauseScreen"), toolText(reply));
			JsonObject img = content.get(1).getAsJsonObject();
			assertEquals("image", img.get("type").getAsString());
			assertEquals("image/png", img.get("mimeType").getAsString());
			assertEquals(200, image(img).getWidth());
		}
	}

	@Test
	void screenshotScalesDownAndMakesJpeg() throws Exception {
		try (FakeBridge fake = screenshotBridge(2000, 1000, null)) {
			JsonObject args = new JsonObject();
			args.addProperty("max_width", 500);
			args.addProperty("format", "jpeg");
			server(fake.port()).handle(toolCall(1, "screenshot", args));
			JsonObject reply = sink.next();
			assertFalse(toolIsError(reply), toolText(reply));
			assertTrue(toolText(reply).contains("scaled to 500x250"), toolText(reply));
			assertTrue(toolText(reply).contains("in game"), toolText(reply));
			JsonObject img = reply.getAsJsonObject("result").getAsJsonArray("content").get(1).getAsJsonObject();
			assertEquals("image/jpeg", img.get("mimeType").getAsString());
			java.awt.image.BufferedImage decoded = image(img);
			assertEquals(500, decoded.getWidth());
			assertEquals(250, decoded.getHeight());
		}
	}

	@Test
	void screenshotRejectsUnknownFormat() throws Exception {
		try (FakeBridge fake = screenshotBridge(10, 10, null)) {
			JsonObject args = new JsonObject();
			args.addProperty("format", "gif");
			server(fake.port()).handle(toolCall(1, "screenshot", args));
			JsonObject reply = sink.next();
			assertTrue(toolIsError(reply));
			assertTrue(toolText(reply).contains("png or jpeg"), toolText(reply));
		}
	}

	@Test
	void pressKeyPassesArguments() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("press_key", params -> FakeBridge.Reply.ok(params));
			JsonObject args = new JsonObject();
			args.addProperty("key", "w");
			args.addProperty("action", "tap");
			args.addProperty("hold_ms", 1000);
			args.addProperty("modifiers", "ctrl+shift");
			server(fake.port()).handle(toolCall(1, "press_key", args));
			assertFalse(toolIsError(sink.next()));
			assertEquals(args, fake.calls.get(0).params());
		}
	}

	@Test
	void enterTextPassesArguments() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("enter_text", params -> FakeBridge.Reply.ok(params));
			JsonObject args = new JsonObject();
			args.addProperty("text", "My World");
			args.addProperty("field", 1);
			args.addProperty("mode", "set");
			args.addProperty("clear", true);
			args.addProperty("submit", true);
			server(fake.port()).handle(toolCall(1, "enter_text", args));
			assertFalse(toolIsError(sink.next()));
			assertEquals(args, fake.calls.get(0).params());

			server(fake.port()).handle(toolCall(2, "enter_text", null));
			assertFalse(toolIsError(sink.next()));
			assertEquals(0, fake.calls.get(1).params().size(), "no text only lists the text boxes");
		}
	}

	@Test
	void readScreenPassesArguments() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("read_screen", params -> FakeBridge.Reply.ok(params));
			JsonObject args = new JsonObject();
			args.addProperty("interactive_only", true);
			args.addProperty("include_hidden", false);
			server(fake.port()).handle(toolCall(1, "read_screen", args));
			assertFalse(toolIsError(sink.next()));
			assertEquals(args, fake.calls.get(0).params());
		}
	}

	@Test
	void interactWidgetPassesArguments() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("interact_widget", params -> FakeBridge.Reply.ok(params));
			JsonObject args = new JsonObject();
			args.addProperty("widget", 7);
			args.addProperty("label", "FOV");
			args.addProperty("action", "set");
			args.addProperty("value", "90");
			args.addProperty("fraction", 0.5);
			args.addProperty("amount", 3);
			server(fake.port()).handle(toolCall(1, "interact_widget", args));
			assertFalse(toolIsError(sink.next()));
			assertEquals(args, fake.calls.get(0).params());
		}
	}

	@Test
	void launchLogShowsTail() throws Exception {
		Path log = project.resolve("build/gravel-mcp/launch.log");
		Files.createDirectories(log.getParent());
		Files.writeString(log, "a\nb\nc\n");

		JsonObject args = new JsonObject();
		args.addProperty("lines", 2);
		server(FakeBridge.unusedPort()).handle(toolCall(1, "minecraft_launch_log", args));

		String text = toolText(sink.next());
		assertTrue(text.endsWith("\nb\nc"), text);
		assertTrue(text.startsWith("Log file: "), text);
	}
}
