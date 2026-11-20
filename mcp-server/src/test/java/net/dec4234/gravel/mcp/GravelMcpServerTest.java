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
				"open_chat", "send_chat", "read_chat", "select_hotbar_slot", "open_inventory", "read_inventory",
				"move_item", "click_slot", "open_pause_menu", "open_options", "close_screen", "list_options",
				"set_option", "minecraft_launch_log"), names);
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
