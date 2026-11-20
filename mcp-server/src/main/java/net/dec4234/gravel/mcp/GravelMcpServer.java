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
import java.util.Objects;
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

		add(new Tool("join_server",
				"Join a multiplayer server by address (host, host:port, or IP). Leaves the current world first if needed "
						+ "(a singleplayer world is saved). Waits until the player is in the server's world. If the join "
						+ "fails, the error has the text of the disconnect screen (such as \"Failed to connect to the "
						+ "server: Connection refused\" or a kick reason). Only offline accounts are supported for now, "
						+ "so the server must use online-mode=false.",
				Schema.object()
						.requiredProp("address", "string", "Server address, for example \"localhost\", \"127.0.0.1:25565\" or \"mc.example.com\".")
						.prop("resource_pack", "string", "What to do with a server resource pack: prompt (show the vanilla "
								+ "question screen, the join waits there), enabled (accept) or disabled (decline). Default prompt.")
						.prop("timeout_seconds", "integer", "How long to wait for the join. Default 120.")
						.build(),
				args -> {
					String address = Schema.optString(args, "address");
					if (address == null || address.isBlank()) {
						throw new IllegalArgumentException("address is required");
					}
					address = address.trim();
					JsonObject params = new JsonObject();
					params.addProperty("address", address);
					String pack = Schema.optString(args, "resource_pack");
					if (pack != null) {
						params.addProperty("resource_pack", pack);
					}
					JsonObject started = bridge.action("join_server", params);
					if (started.has("alreadyOnServer")) {
						return "Already on server '" + address + "'.";
					}
					return waitForServer(address, started, Duration.ofSeconds(Schema.optInt(args, "timeout_seconds", 120)));
				}));

		add(new Tool("read_disconnects",
				"Read the recent disconnect screens: connection failures, login rejections and kicks, with the "
						+ "title, the reason text and the server address. Pass since_id to get only new ones.",
				Schema.object()
						.prop("since_id", "integer", "Only return disconnects with an id greater than this. Default 0.")
						.build(),
				args -> {
					JsonObject params = new JsonObject();
					params.addProperty("since_id", Schema.optInt(args, "since_id", 0));
					return PRETTY.toJson(bridge.action("read_disconnects", params));
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

		add(new Tool("select_hotbar_slot",
				"Select a hotbar slot, like pressing a number key. Slot 0 is key 1, slot 8 is key 9. "
						+ "Returns the item now in the hand.",
				Schema.object()
						.requiredProp("slot", "integer", "Hotbar slot 0-8.")
						.build(),
				args -> {
					JsonObject params = new JsonObject();
					params.addProperty("slot", Schema.optInt(args, "slot", -1));
					return PRETTY.toJson(bridge.action("select_hotbar_slot", params));
				}));

		add(new Tool("open_inventory",
				"Open the player inventory, like pressing E. In creative mode this opens the creative inventory "
						+ "on its Survival Inventory tab, so slot numbers are the same as in survival.",
				Schema.object().build(),
				args -> PRETTY.toJson(bridge.action("open_inventory", new JsonObject()))));

		add(new Tool("read_inventory",
				"List the items in the open container screen (inventory, chest, ...), or in the player inventory if "
						+ "no container is open. Also shows the item on the cursor. Player inventory slot numbers: "
						+ "0 crafting result, 1-4 crafting grid, 5-8 armor (head to feet), 9-35 main inventory, "
						+ "36-44 hotbar, 45 offhand. Other containers list their own slots first, then the player's.",
				Schema.object()
						.prop("include_empty", "boolean", "Also list empty slots. Default false.")
						.build(),
				args -> {
					JsonObject params = new JsonObject();
					params.addProperty("include_empty", Schema.optBool(args, "include_empty", false));
					return PRETTY.toJson(bridge.action("read_inventory", params));
				}));

		add(new Tool("move_item",
				"Move items from one slot to another in the open container screen, with mouse clicks like a player. "
						+ "If the target slot holds a different item, the two stacks swap. Use read_inventory for slot numbers.",
				Schema.object()
						.requiredProp("from", "integer", "Slot to take items from.")
						.requiredProp("to", "integer", "Slot to put items in.")
						.prop("count", "integer", "How many items to move. Default: the whole stack.")
						.build(),
				args -> {
					JsonObject params = new JsonObject();
					params.addProperty("from", Schema.optInt(args, "from", -1));
					params.addProperty("to", Schema.optInt(args, "to", -1));
					if (args.has("count") && !args.get("count").isJsonNull()) {
						params.addProperty("count", Schema.optInt(args, "count", 1));
					}
					return PRETTY.toJson(bridge.action("move_item", params));
				}));

		add(new Tool("click_slot",
				"Click one slot in the open container screen, for full control. Actions: PICKUP (button 0 left, 1 right), "
						+ "QUICK_MOVE (shift-click), SWAP (button = hotbar slot 0-8, or 40 for offhand), THROW (button 0 one "
						+ "item, 1 the stack), CLONE (creative middle click), PICKUP_ALL (double click). Slot -999 clicks "
						+ "outside the window, which drops the item on the cursor.",
				Schema.object()
						.requiredProp("slot", "integer", "Slot number, or -999 for outside the window.")
						.prop("action", "string", "PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT or PICKUP_ALL. Default PICKUP.")
						.prop("button", "integer", "Mouse button or hotbar slot, see the description. Default 0.")
						.build(),
				args -> {
					JsonObject params = new JsonObject();
					params.addProperty("slot", Schema.optInt(args, "slot", 0));
					params.addProperty("action", Objects.requireNonNullElse(Schema.optString(args, "action"), "PICKUP"));
					params.addProperty("button", Schema.optInt(args, "button", 0));
					return PRETTY.toJson(bridge.action("click_slot", params));
				}));

		add(new Tool("open_pause_menu",
				"Open the pause menu (game menu), like pressing ESC in game. In singleplayer this pauses the game.",
				Schema.object().build(),
				args -> PRETTY.toJson(bridge.action("open_pause_menu", new JsonObject()))));

		add(new Tool("open_options",
				"Open the Options menu. Closing it goes back to the screen that was open, or to the pause menu.",
				Schema.object().build(),
				args -> PRETTY.toJson(bridge.action("open_options", new JsonObject()))));

		add(new Tool("close_screen",
				"Close the open screen, like pressing ESC. Some screens (such as Options) go back to their parent screen.",
				Schema.object().build(),
				args -> PRETTY.toJson(bridge.action("close_screen", new JsonObject()))));

		add(new Tool("list_options",
				"List game options with their values, by the names used in options.txt (for example fov, renderDistance, "
						+ "guiScale, soundCategory_master, key_key.jump, mainHand). Values are the real values the menus "
						+ "show (fov 70, mainHand RIGHT). Use filter to find options.",
				Schema.object()
						.prop("filter", "string", "Only names that contain this text (not case sensitive). Optional.")
						.build(),
				args -> {
					JsonObject params = new JsonObject();
					String filter = Schema.optString(args, "filter");
					if (filter != null) {
						params.addProperty("filter", filter);
					}
					return PRETTY.toJson(bridge.action("list_options", params));
				}));

		add(new Tool("set_option",
				"Change one game option and save it, like changing it in the Options menu. Use the name and value format "
						+ "from list_options. Examples: fov 90, renderDistance 8, guiScale 2, soundCategory_master 0.5, "
						+ "mainHand left, key_key.jump key.keyboard.space. A value the game does not allow gives an error.",
				Schema.object()
						.requiredProp("name", "string", "Option name from list_options.")
						.requiredProp("value", "string", "New value, in the same format list_options shows.")
						.build(),
				args -> {
					JsonObject params = new JsonObject();
					params.addProperty("name", Schema.optString(args, "name"));
					params.addProperty("value", Schema.optString(args, "value"));
					return PRETTY.toJson(bridge.action("set_option", params));
				}));

		add(new Tool("chunk_info",
				"Report how many chunks are rendered around the player: renderedChunks (chunk columns drawn in the last "
						+ "frame), renderedSections (16x16x16 sections drawn, the F3 \"C:\" value), the farthest rendered "
						+ "chunk distance, loadedChunks (chunks the client holds), the render distance (option and "
						+ "effective value with the server limit), simulation distance, and whether all sections are built.",
				Schema.object().build(),
				args -> PRETTY.toJson(bridge.action("chunk_info", new JsonObject()))));

		add(new Tool("teleport",
				"Singleplayer only: move the player with the /tp command (tp @s x y z [yaw pitch]). Each value is a number, "
						+ "or ~ / ~<number> relative to the player, or ^ / ^<number> relative to the view direction. The "
						+ "world needs cheats on. Returns the command feedback and the position before and after.",
				Schema.object()
						.requiredProp("x", "string", "X coordinate, for example \"100\", \"~\" or \"~-20\".")
						.requiredProp("y", "string", "Y coordinate, for example \"64\" or \"~10\".")
						.requiredProp("z", "string", "Z coordinate, for example \"-250.5\" or \"~\".")
						.prop("yaw", "string", "Horizontal rotation in degrees (or ~). Optional.")
						.prop("pitch", "string", "Vertical rotation in degrees, -90 up to 90 down (or ~). Optional.")
						.prop("wait_ms", "integer", "How long to wait for the move and feedback before returning. Default 1000.")
						.build(),
				args -> {
					JsonObject params = new JsonObject();
					for (String key : new String[]{"x", "y", "z", "yaw", "pitch"}) {
						String value = Schema.optString(args, key);
						if (value != null) {
							params.addProperty(key, value);
						}
					}
					JsonObject sent = bridge.action("teleport", params);
					Thread.sleep(Math.max(0, Schema.optInt(args, "wait_ms", 1000)));
					JsonObject read = new JsonObject();
					read.addProperty("since_id", sent.get("chatIdBeforeSend").getAsLong());
					read.addProperty("limit", 100);
					JsonObject replies = bridge.action("read_chat", read);
					JsonObject status = bridge.action("status", null);
					JsonObject result = new JsonObject();
					result.add("sent", sent.get("sent"));
					result.add("from", sent.get("from"));
					result.add("to", status.get("player"));
					result.add("replies", replies.get("messages"));
					return PRETTY.toJson(result);
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

	/**
	 * Polls the game status until the join attempt is in the server's world, fails, or time runs out.
	 * A failed join throws, so the tool result is an error with the disconnect screen text.
	 */
	private String waitForServer(String address, JsonObject started, Duration timeout) throws Exception {
		long attemptId = started.get("attemptId").getAsLong();
		long deadline = System.nanoTime() + timeout.toNanos();
		JsonObject status = null;
		while (System.nanoTime() < deadline) {
			Thread.sleep(500);
			if (!bridge.isUp()) {
				continue;
			}
			status = bridge.action("status", null);
			JsonObject conn = optObject(status, "serverConnection");
			JsonObject attempt = conn == null ? null : optObject(conn, "attempt");
			if (attempt == null) {
				continue;
			}
			if (attempt.get("id").getAsLong() != attemptId) {
				throw new IllegalStateException("Another join started while waiting for '" + address + "'.");
			}
			String state = optString(attempt, "state");
			if ("failed".equals(state) || "disconnected".equals(state)) {
				throw new IllegalStateException(joinFailure(address, state, attempt));
			}
			String screen = Objects.requireNonNullElse(optString(status, "screen"), "");
			boolean loading = screen.equals("LevelLoadingScreen") || screen.equals("ReceivingLevelScreen");
			boolean inWorld = status.get("inWorld").getAsBoolean();
			if ("joined".equals(state) && inWorld && !loading && address.equals(optString(conn, "server"))) {
				return "Joined server '" + address + "'.\n" + PRETTY.toJson(status);
			}
		}
		return "Timed out after " + timeout.toSeconds() + "s waiting to join '" + address + "'. Check the screen in "
				+ "the status (a resource pack or code of conduct question can stop the join). Last status:\n"
				+ (status == null ? "(none)" : PRETTY.toJson(status));
	}

	/** One message from a failed attempt: the disconnect screen title and reason, or the mod's own error. */
	static String joinFailure(String address, String state, JsonObject attempt) {
		StringBuilder sb = new StringBuilder(state.equals("disconnected")
				? "Joined '" + address + "', then got disconnected"
				: "Could not join '" + address + "'");
		JsonObject d = optObject(attempt, "disconnect");
		if (d != null) {
			sb.append(": ").append(optString(d, "title")).append(": ").append(optString(d, "reason"));
			String hint = optString(d, "hint");
			if (hint != null) {
				sb.append("\nHint: ").append(hint);
			}
		} else if (optString(attempt, "error") != null) {
			sb.append(": ").append(optString(attempt, "error"));
		}
		return sb.toString();
	}

	private static JsonObject optObject(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
	}

	private static String optString(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
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
