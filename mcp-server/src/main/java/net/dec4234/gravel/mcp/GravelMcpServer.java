package net.dec4234.gravel.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal MCP server (JSON-RPC 2.0 over stdio) that lets Claude start Minecraft and talk to the
 * Gravel mod inside it.
 */
public final class GravelMcpServer {
	private static final String PROTOCOL_FALLBACK = "2025-06-18";
	private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

	private final PrintStream out;
	private final Map<String, Tool> tools = new LinkedHashMap<>();
	private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "gravel-mcp-tool");
		t.setDaemon(true);
		return t;
	});

	private final BridgeClient bridge;
	private final MinecraftLauncher launcher;

	public static void main(String[] args) throws Exception {
		// Stdout is the protocol channel. Keep a handle to it and send all other output to stderr.
		PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
		System.setOut(System.err);

		Path projectDir = findProjectDir();
		int port = Integer.parseInt(envOr("GRAVEL_BRIDGE_PORT", "25599"));
		System.err.println("[gravel-mcp] project dir: " + projectDir + ", bridge port: " + port);

		new GravelMcpServer(protocolOut, projectDir, port)
				.serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
	}

	GravelMcpServer(PrintStream out, Path projectDir, int port) {
		this.out = out;
		this.bridge = new BridgeClient(port);
		this.launcher = new MinecraftLauncher(projectDir, bridge);
		registerTools();
	}

	// ---------------------------------------------------------------- tools

	private void registerTools() {
		add(new Tool("launch_minecraft",
				"Start the Minecraft dev client (gradlew runClient) with the Gravel mod loaded, and wait until the mod "
						+ "bridge is ready. The first launch can take several minutes. Optionally open a singleplayer "
						+ "world by its folder name right after startup.",
				Schema.object()
						.prop("world", "string", "Name of an existing singleplayer world (save folder) to open on start. Optional.")
						.prop("timeout_seconds", "integer", "How long to wait for the game to be ready. Default 600.")
						.build(),
				args -> {
					String world = Schema.optString(args, "world");
					int timeout = Schema.optInt(args, "timeout_seconds", 600);
					return launcher.launch(world, Duration.ofSeconds(timeout));
				}));

		add(new Tool("stop_minecraft",
				"Quit the running Minecraft client. Kills the process if it does not stop by itself.",
				Schema.object().build(),
				args -> launcher.stop()));

		add(new Tool("minecraft_status",
				"Report whether Minecraft is running, and if so the in-game state (in a world, current screen, "
						+ "player position and health).",
				Schema.object().build(),
				args -> {
					JsonObject result = new JsonObject();
					result.addProperty("launcherProcessAlive", launcher.isProcessAlive());
					boolean up = bridge.isUp();
					result.addProperty("bridgeReachable", up);
					if (up) {
						result.add("game", bridge.action("status", null));
					}
					return PRETTY.toJson(result);
				}));

		add(new Tool("join_world",
				"Join a singleplayer world by its save folder name (default \"temp\"). If the world does not exist, "
						+ "it is created (creative mode, cheats on). Set recreate=true to delete the world and make a "
						+ "fresh one. Leaves the current world first if needed. Waits until the player is in the world.",
				Schema.object()
						.prop("name", "string", "World save folder name. Default \"temp\".")
						.prop("recreate", "boolean", "Delete the world (if it exists) and create a new one. Default false.")
						.prop("timeout_seconds", "integer", "How long to wait for the world to load. Default 180.")
						.build(),
				args -> {
					String name = Schema.optString(args, "name");
					if (name == null || name.isBlank()) {
						name = "temp";
					}
					JsonObject params = new JsonObject();
					params.addProperty("name", name);
					params.addProperty("recreate", Schema.optBool(args, "recreate", false));
					JsonObject started = bridge.action("join_world", params);
					if (started.has("alreadyInWorld")) {
						return "Already in world '" + name + "'.";
					}
					return waitForWorld(name, started, Duration.ofSeconds(Schema.optInt(args, "timeout_seconds", 180)));
				}));

		add(new Tool("open_chat",
				"Open the in-game chat screen, like pressing T (or / when text starts with \"/\"). "
						+ "Optionally put text in the input box without sending it.",
				Schema.object()
						.prop("text", "string", "Text to put in the chat box. Optional.")
						.build(),
				args -> {
					JsonObject params = new JsonObject();
					String text = Schema.optString(args, "text");
					if (text != null) {
						params.addProperty("text", text);
					}
					bridge.action("open_chat", params);
					return "Chat screen opened.";
				}));

		add(new Tool("send_chat",
				"Send a chat message, or a command if the text starts with \"/\" (for example \"/time set day\"). "
						+ "Returns the chat lines received soon after, such as command feedback.",
				Schema.object()
						.requiredProp("message", "string", "The chat message or /command to send.")
						.prop("wait_ms", "integer", "How long to wait for replies before returning. Default 1000.")
						.build(),
				args -> {
					JsonObject params = new JsonObject();
					params.addProperty("message", Schema.optString(args, "message"));
					JsonObject sent = bridge.action("send_chat", params);
					Thread.sleep(Math.max(0, Schema.optInt(args, "wait_ms", 1000)));
					JsonObject read = new JsonObject();
					read.addProperty("since_id", sent.get("chatIdBeforeSend").getAsLong());
					read.addProperty("limit", 100);
					JsonObject replies = bridge.action("read_chat", read);
					JsonObject result = new JsonObject();
					result.add("sent", sent.get("sent"));
					result.add("replies", replies.get("messages"));
					result.add("latestId", replies.get("latestId"));
					return PRETTY.toJson(result);
				}));

		add(new Tool("read_chat",
				"Read chat lines the game received: player chat, system messages and command feedback. "
						+ "Pass since_id (the latestId from an earlier call) to get only new lines.",
				Schema.object()
						.prop("since_id", "integer", "Only return lines with an id greater than this. Default 0 (all kept lines).")
						.prop("limit", "integer", "Maximum number of lines (the newest ones). Default 50.")
						.prop("include_action_bar", "boolean", "Also include action bar text. Default false.")
						.build(),
				args -> {
					JsonObject params = new JsonObject();
					params.addProperty("since_id", Schema.optInt(args, "since_id", 0));
					params.addProperty("limit", Schema.optInt(args, "limit", 50));
					params.addProperty("include_action_bar", Schema.optBool(args, "include_action_bar", false));
					return PRETTY.toJson(bridge.action("read_chat", params));
				}));

		add(new Tool("minecraft_launch_log",
				"Show the last lines of the Gradle/Minecraft launch log. Use this to debug a failed launch.",
				Schema.object()
						.prop("lines", "integer", "Number of lines from the end. Default 80.")
						.build(),
				args -> "Log file: " + launcher.logFile() + "\n" + launcher.tailLog(Schema.optInt(args, "lines", 80))));
	}

	/** Polls the game status until the player is in the named world, the mod reports an error, or time runs out. */
	private String waitForWorld(String name, JsonObject started, Duration timeout) throws Exception {
		String action = started.has("action") ? started.get("action").getAsString() : "open";
		// If we started in a world, first see the player leave it, so the old world is not taken as the new one.
		boolean leftOldWorld = !(started.has("wasInWorld") && started.get("wasInWorld").getAsBoolean());
		long deadline = System.nanoTime() + timeout.toNanos();
		JsonObject status = null;
		while (System.nanoTime() < deadline) {
			Thread.sleep(1000);
			if (!bridge.isUp()) {
				continue;
			}
			status = bridge.action("status", null);
			if (status.has("worldError") && !status.get("worldError").isJsonNull()) {
				throw new IllegalStateException(status.get("worldError").getAsString());
			}
			boolean inWorld = status.get("inWorld").getAsBoolean();
			String world = status.has("world") && !status.get("world").isJsonNull() ? status.get("world").getAsString() : null;
			if (!inWorld) {
				leftOldWorld = true;
			}
			String screen = status.has("screen") && !status.get("screen").isJsonNull() ? status.get("screen").getAsString() : "";
			boolean loading = screen.equals("LevelLoadingScreen") || screen.equals("ReceivingLevelScreen");
			if (leftOldWorld && inWorld && !loading && name.equals(world)) {
				return "Joined world '" + name + "' (" + action + ").\n" + PRETTY.toJson(status);
			}
		}
		return "Timed out after " + timeout.toSeconds() + "s waiting to join '" + name + "'. Last status:\n"
				+ (status == null ? "(none)" : PRETTY.toJson(status));
	}

	private void add(Tool tool) {
		tools.put(tool.name(), tool);
	}

	// ---------------------------------------------------------------- JSON-RPC loop

	void serve(BufferedReader in) throws Exception {
		String line;
		while ((line = in.readLine()) != null) {
			if (line.isBlank()) {
				continue;
			}
			JsonObject msg;
			try {
				msg = JsonParser.parseString(line).getAsJsonObject();
			} catch (RuntimeException e) {
				sendError(JsonNull.INSTANCE, -32700, "Parse error");
				continue;
			}
			handle(msg);
		}
		// Stdin closed: Claude Code ended the session. Minecraft keeps running on purpose.
		workers.shutdownNow();
	}

	void handle(JsonObject msg) {
		JsonElement id = msg.get("id");
		String method = msg.has("method") ? msg.get("method").getAsString() : null;
		JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
				? msg.getAsJsonObject("params") : new JsonObject();

		if (method == null) {
			return; // a response to a request we never send; ignore
		}
		if (id == null || id.isJsonNull()) {
			return; // notification (initialized, cancelled, ...)
		}

		switch (method) {
			case "initialize" -> {
				JsonObject result = new JsonObject();
				String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOL_FALLBACK;
				result.addProperty("protocolVersion", requested);
				JsonObject caps = new JsonObject();
				caps.add("tools", new JsonObject());
				result.add("capabilities", caps);
				JsonObject info = new JsonObject();
				info.addProperty("name", "gravel");
				info.addProperty("version", "0.1.0");
				result.add("serverInfo", info);
				result.addProperty("instructions",
						"Controls a Minecraft client running the Gravel Fabric mod. Call launch_minecraft first, "
								+ "then minecraft_status to see the game state.");
				sendResult(id, result);
			}
			case "ping" -> sendResult(id, new JsonObject());
			case "tools/list" -> {
				JsonArray list = new JsonArray();
				for (Tool t : tools.values()) {
					JsonObject o = new JsonObject();
					o.addProperty("name", t.name());
					o.addProperty("description", t.description());
					o.add("inputSchema", t.inputSchema());
					list.add(o);
				}
				JsonObject result = new JsonObject();
				result.add("tools", list);
				sendResult(id, result);
			}
			case "tools/call" -> {
				String name = params.has("name") ? params.get("name").getAsString() : "";
				JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
						? params.getAsJsonObject("arguments") : new JsonObject();
				Tool tool = tools.get(name);
				if (tool == null) {
					sendError(id, -32602, "Unknown tool: " + name);
					return;
				}
				// Run off the read loop so long calls (launch) do not block pings.
				workers.submit(() -> {
					String text;
					boolean isError = false;
					try {
						text = tool.handler().call(args);
					} catch (Exception e) {
						isError = true;
						text = e.getClass().getSimpleName() + ": " + e.getMessage();
					}
					JsonObject result = new JsonObject();
					JsonArray content = new JsonArray();
					JsonObject c = new JsonObject();
					c.addProperty("type", "text");
					c.addProperty("text", text);
					content.add(c);
					result.add("content", content);
					result.addProperty("isError", isError);
					sendResult(id, result);
				});
			}
			default -> sendError(id, -32601, "Method not found: " + method);
		}
	}

	private synchronized void sendResult(JsonElement id, JsonObject result) {
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.add("id", id);
		msg.add("result", result);
		out.println(GSON.toJson(msg));
	}

	private synchronized void sendError(JsonElement id, int code, String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.add("id", id);
		msg.add("error", err);
		out.println(GSON.toJson(msg));
	}

	// ---------------------------------------------------------------- helpers

	/** GRAVEL_PROJECT_DIR, else the repo that contains this jar (mcp-server/build/libs/gravel-mcp.jar), else cwd. */
	private static Path findProjectDir() {
		String env = System.getenv("GRAVEL_PROJECT_DIR");
		if (env != null && !env.isBlank()) {
			return Path.of(env).toAbsolutePath();
		}
		try {
			Path jar = Path.of(GravelMcpServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			Path candidate = jar;
			for (int i = 0; i < 6 && candidate != null; i++) {
				if (Files.exists(candidate.resolve("gradlew")) && Files.exists(candidate.resolve("settings.gradle"))) {
					return candidate;
				}
				candidate = candidate.getParent();
			}
		} catch (URISyntaxException | RuntimeException ignored) {
			// fall back to cwd
		}
		return Path.of("").toAbsolutePath();
	}

	private static String envOr(String name, String fallback) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? fallback : v;
	}

	@FunctionalInterface
	interface Handler {
		String call(JsonObject args) throws Exception;
	}

	record Tool(String name, String description, JsonObject inputSchema, Handler handler) {
	}
}
