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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal MCP server (JSON-RPC 2.0 over stdio) that lets Claude start Minecraft and talk to the
 * Gravel mod inside it.
 */
public final class GravelMcpServer {
	private static final String PROTOCOL_FALLBACK = "2025-06-18";
	private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
	/** The mod stops wait_for after this time, so its action ends before the bridge time limit (30 s). */
	static final int MAX_WAIT_MS = 25_000;
	private static final int DEFAULT_REPLY_WAIT_MS = 2000;
	private static final int REPLY_SETTLE_TICKS = 2;
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
	private final LogScanner logs;

	public static void main(String[] args) throws Exception {
		// Stdout is the protocol channel. Keep a handle to it and send all other output to stderr.
		PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
		System.setOut(System.err);
		// Screenshots are scaled with java.awt. It needs no display.
		System.setProperty("java.awt.headless", "true");

		Path projectDir = findProjectDir();
		int port = Integer.parseInt(envOr("GRAVEL_BRIDGE_PORT", "25599"));
		System.err.println("[gravel-mcp] project dir: " + projectDir + ", bridge port: " + port);

		GravelMcpServer server = new GravelMcpServer(protocolOut, projectDir, port);
		// If the server is stopped by a signal and not by the end of stdin, still close the game it started.
		Runtime.getRuntime().addShutdownHook(new Thread(server.launcher::stopIfLaunched, "gravel-mcp-stop-minecraft"));
		server.serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
	}

	GravelMcpServer(PrintStream out, Path projectDir, int port) {
		this.out = out;
		this.bridge = new BridgeClient(port);
		this.launcher = new MinecraftLauncher(projectDir, bridge);
		this.logs = new LogScanner(projectDir.resolve("run"), launcher.logFile());
		registerTools();
	}

	// ---------------------------------------------------------------- tools

	private void registerTools() {
		add(new Tool("launch_minecraft",
				"Start the Minecraft dev client (gradlew runClient) with the Gravel mod loaded, and wait until the mod "
						+ "bridge is ready. The first launch can take several minutes. Optionally open a singleplayer "
						+ "world by its folder name right after startup. Music is off unless music is true. The game "
						+ "closes when the MCP session ends.",
				Schema.object()
						.prop("world", "string", "Name of an existing singleplayer world (save folder) to open on start. Optional.")
						.prop("music", "boolean", "Let the game play music. Default false.")
						.prop("timeout_seconds", "integer", "How long to wait for the game to be ready. Default 600.")
						.build(),
				args -> {
					String world = Schema.optString(args, "world");
					int timeout = Schema.optInt(args, "timeout_seconds", 600);
					boolean music = args != null && args.has("music") && args.get("music").getAsBoolean();
					return launcher.launch(world, music, Duration.ofSeconds(timeout));
				}));

		add(new Tool("stop_minecraft",
				"Quit the running Minecraft client. Kills the process if it does not stop by itself.",
				Schema.object().build(),
				args -> launcher.stop()));

		add(new Tool("minecraft_status",
				"Report whether Minecraft is running, and if so the in-game state (in a world, current screen, "
						+ "player position and health). Set mods to also list the mods that Fabric Loader loaded "
						+ "(id, name, version, type, jar paths or the mod they are nested in) and the mixin problems "
						+ "in latest.log (mixins that could not load or apply, missing targets). The mixin problems "
						+ "show also when the game is stopped or crashed.",
				Schema.object()
						.prop("mods", "string", "summary (id, version, jar for each mod) or full (also dependencies "
								+ "with the found version, entrypoints and mixin configs). Optional.")
						.prop("mod", "string", "With mods: only mods whose id or name has this text. Optional.")
						.build(),
				args -> {
					String mods = Schema.optString(args, "mods");
					JsonObject result = new JsonObject();
					result.addProperty("launcherProcessAlive", launcher.isProcessAlive());
					boolean up = bridge.isUp();
					result.addProperty("bridgeReachable", up);
					if (up) {
						JsonObject params = null;
						if (mods != null) {
							params = copy(args, "mods", "mod");
						}
						JsonObject game = bridge.action("status", params);
						JsonElement modList = game.remove("mods");
						result.add("game", game);
						if (modList != null) {
							result.add("mods", modList);
						}
					} else if (mods != null) {
						result.addProperty("mods", "Minecraft is not running. Only the mixin problems from the log show.");
					}
					if (mods != null) {
						result.add("mixinProblems", logs.mixinProblems("latest"));
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

		add(new Tool("chat",
				"Chat and commands. Actions: send (send a message, or a command if it starts with \"/\", such as "
						+ "\"/time set day\"; waits until a reply line arrives or a line that contains wait_for, not "
						+ "longer than wait_ms, then returns the lines received since the send, such as command "
						+ "feedback), read (the chat lines the game received: player chat, system messages, command "
						+ "feedback; pass since_id, the latestId from an earlier call, to get only new lines), open "
						+ "(open the chat screen like pressing T, with message put in the box but not sent). Default: "
						+ "send if message is given, else read.",
				Schema.object()
						.prop("action", "string", "send, read or open. Default: send with message, else read.")
						.prop("message", "string", "For send: the message or /command. For open: text to put in the "
								+ "chat box. Optional for open.")
						.prop("wait_ms", "integer", "For send: longest wait for a reply, 0-" + MAX_WAIT_MS + " ms. "
								+ "Default " + DEFAULT_REPLY_WAIT_MS + ". 0 returns at once.")
						.prop("wait_for", "string", "For send: wait for a line that contains this text "
								+ "(case-insensitive). Default: any line.")
						.prop("wait_for_regex", "boolean", "For send: wait_for is a regex. Default false.")
						.prop("since_id", "integer", "For read: only lines with an id greater than this. Default 0 "
								+ "(all kept lines).")
						.prop("limit", "integer", "For read: most lines to return (the newest ones). Default 50.")
						.prop("include_action_bar", "boolean", "For read: also include action bar text. Default false.")
						.build(),
				args -> {
					String message = Schema.optString(args, "message");
					String action = action(args, message != null ? "send" : "read");
					return switch (action) {
						case "send" -> sendChat(message, args);
						case "read" -> {
							JsonObject params = new JsonObject();
							params.addProperty("since_id", Schema.optInt(args, "since_id", 0));
							params.addProperty("limit", Schema.optInt(args, "limit", 50));
							params.addProperty("include_action_bar", Schema.optBool(args, "include_action_bar", false));
							yield PRETTY.toJson(bridge.action("read_chat", params));
						}
						case "open" -> {
							JsonObject params = new JsonObject();
							if (message != null) {
								params.addProperty("text", message);
							}
							bridge.action("open_chat", params);
							yield "Chat screen opened.";
						}
						default -> throw unknownAction(action, "send, read or open");
					};
				}));

		add(new Tool("wait_for",
				"Wait for a number of client ticks (20 ticks = 1 s), or until conditions are true: a chat line "
						+ "matches, a packet on a channel arrives, a screen is open, the player is in a world or not, the player is near a position. "
						+ "With more than one condition, all must be true. With ticks and conditions, it stops at "
						+ "the first of the two. Returns matched, stoppedBy (condition, ticks or timeout), the "
						+ "matching chat line or packet, the screen and the position.",
				Schema.object()
						.prop("ticks", "integer", "Stop after this many client ticks, 0-500. Alone, it is a plain wait.")
						.prop("timeout_ms", "integer", "Longest wait, 0-" + MAX_WAIT_MS + " ms. Default 10000 ("
								+ MAX_WAIT_MS + " with ticks).")
						.prop("chat", "string", "Wait for a chat line that contains this text (case-insensitive). "
								+ "\"\" matches any new line.")
						.prop("chat_regex", "boolean", "chat is a regex (Java syntax, find). Default false.")
						.prop("since_id", "integer", "Only look at chat lines after this id (for example latestId "
								+ "from chat read). Default: lines that arrive after the call starts.")
						.prop("include_action_bar", "boolean", "Also look at action bar text. Default false.")
						.prop("packet", "string", "Wait for a packet whose channel contains this text "
								+ "(case-insensitive), such as \"mymod:sync\" or \"mymod:\". The channel of a custom "
								+ "payload is its payload id; of a vanilla packet, its type id (minecraft:set_time). "
								+ "\"\" matches any packet.")
						.prop("packet_regex", "boolean", "packet is a regex (Java syntax, find). Default false.")
						.prop("packet_direction", "string", "in (received from the server, default), out (sent by "
								+ "the client) or any.")
						.prop("packet_since_id", "integer", "Only look at packets after this id (for example "
								+ "latestId from packets). Default: packets that arrive after the call starts.")
						.prop("screen", "string", "Wait until this screen is open, by class name such as "
								+ "InventoryScreen or PauseScreen (the \"Screen\" end is optional), or none for no screen.")
						.prop("in_world", "boolean", "Wait until the player is in a world and it is not loading (true), or not in a world (false).")
						.prop("x", "number", "Wait until the player is near this X. Any of x, y, z can be given.")
						.prop("y", "number", "Wait until the player is near this Y.")
						.prop("z", "number", "Wait until the player is near this Z.")
						.prop("radius", "number", "How near to x/y/z, in blocks. Default 1.")
						.prop("settle_ticks", "integer", "After the conditions are true, wait this many more ticks "
								+ "(0-40), for example to get more chat lines. Default 0.")
						.build(),
				args -> PRETTY.toJson(bridge.action("wait_for", copy(args, "ticks", "timeout_ms", "chat", "chat_regex",
						"since_id", "include_action_bar", "packet", "packet_regex", "packet_direction", "packet_since_id",
						"screen", "in_world", "x", "y", "z", "radius", "settle_ticks")))));

		add(new Tool("inventory",
				"Items and slots. Actions: read (default; the items in the open container screen, such as the "
						+ "inventory or a chest, or in the player inventory if no container is open, and the item on "
						+ "the cursor), move (move count items from slot from to slot to with mouse clicks like a "
						+ "player; a different item in the target slot swaps), click (one slot click for full "
						+ "control, see mode), hotbar (select hotbar slot 0-8 like a number key; returns the item now "
						+ "in the hand). move and click need an open container screen (screen open inventory). Player "
						+ "inventory slots: 0 crafting result, 1-4 crafting grid, 5-8 armor (head to feet), 9-35 main "
						+ "inventory, 36-44 hotbar, 45 offhand. Other containers list their own slots first, then the "
						+ "player's.",
				Schema.object()
						.prop("action", "string", "read (default), move, click or hotbar.")
						.prop("include_empty", "boolean", "For read: also list empty slots. Default false.")
						.prop("from", "integer", "For move: slot to take items from.")
						.prop("to", "integer", "For move: slot to put items in.")
						.prop("count", "integer", "For move: how many items. Default: the whole stack.")
						.prop("slot", "integer", "For click: slot number, or -999 for outside the window (drops the "
								+ "item on the cursor). For hotbar: 0-8 (slot 0 is key 1).")
						.prop("mode", "string", "For click: PICKUP (default; button 0 left, 1 right), QUICK_MOVE "
								+ "(shift-click), SWAP (button = hotbar slot 0-8, or 40 for offhand), THROW (button 0 one "
								+ "item, 1 the stack), CLONE (creative middle click), QUICK_CRAFT or PICKUP_ALL (double "
								+ "click).")
						.prop("button", "integer", "For click: mouse button or hotbar slot, see mode. Default 0.")
						.build(),
				args -> {
					String action = action(args, "read");
					JsonObject params = new JsonObject();
					return switch (action) {
						case "read" -> {
							params.addProperty("include_empty", Schema.optBool(args, "include_empty", false));
							yield PRETTY.toJson(bridge.action("read_inventory", params));
						}
						case "move" -> {
							params.addProperty("from", requireInt(args, "from", action));
							params.addProperty("to", requireInt(args, "to", action));
							if (args.has("count") && !args.get("count").isJsonNull()) {
								params.addProperty("count", Schema.optInt(args, "count", 1));
							}
							yield PRETTY.toJson(bridge.action("move_item", params));
						}
						case "click" -> {
							params.addProperty("slot", requireInt(args, "slot", action));
							params.addProperty("action", Objects.requireNonNullElse(Schema.optString(args, "mode"), "PICKUP"));
							params.addProperty("button", Schema.optInt(args, "button", 0));
							yield PRETTY.toJson(bridge.action("click_slot", params));
						}
						case "hotbar" -> {
							params.addProperty("slot", requireInt(args, "slot", action));
							yield PRETTY.toJson(bridge.action("select_hotbar_slot", params));
						}
						default -> throw unknownAction(action, "read, move, click or hotbar");
					};
				}));

		add(new Tool("screen",
				"The open screen (menu). Actions: read (default; its class, title, size and every widget: button, "
						+ "cycle_button (value), checkbox (checked), slider (fraction 0-1, label shows the value), "
						+ "text_box (value), tab (selected), list (entries, scroll), list_entry (selected), text (a "
						+ "label), container; each with its index (widget), label, parent, interactive, active, "
						+ "visible, inView, focused, position and size in GUI pixels, and tooltip), open (name: "
						+ "inventory like pressing E, in creative on its Survival Inventory tab; pause, the game menu "
						+ "like ESC, which pauses singleplayer; options, which goes back to the open screen or the "
						+ "pause menu when closed; for chat use the chat tool), close (like ESC; some screens such as "
						+ "Options go back to their parent screen). Use screen_input to act on widgets. Works for all "
						+ "menus, also mod menus. For item slots use the inventory tool.",
				Schema.object()
						.prop("action", "string", "read (default), open or close.")
						.prop("name", "string", "For open: inventory, pause or options.")
						.prop("interactive_only", "boolean", "For read: only widgets that can be used now (no labels, "
								+ "containers or greyed out widgets). Default false.")
						.prop("include_hidden", "boolean", "For read: also list hidden widgets. Default false.")
						.build(),
				args -> {
					String action = action(args, "read");
					return switch (action) {
						case "read" -> PRETTY.toJson(bridge.action("read_screen", copy(args, "interactive_only", "include_hidden")));
						case "close" -> PRETTY.toJson(bridge.action("close_screen", new JsonObject()));
						case "open" -> {
							String name = Objects.requireNonNullElse(Schema.optString(args, "name"), "").trim().toLowerCase(Locale.ROOT);
							String bridgeAction = switch (name) {
								case "inventory" -> "open_inventory";
								case "pause", "pause_menu", "menu" -> "open_pause_menu";
								case "options" -> "open_options";
								case "chat" -> throw new IllegalArgumentException("Use the chat tool with action open.");
								default -> throw new IllegalArgumentException("For open, name must be inventory, pause or "
										+ "options, not '" + name + "'.");
							};
							yield PRETTY.toJson(bridge.action(bridgeAction, new JsonObject()));
						}
						default -> throw unknownAction(action, "read, open or close");
					};
				}));

		add(new Tool("options",
				"Game options by their options.txt names (for example fov, renderDistance, guiScale, "
						+ "soundCategory_master, key_key.jump, mainHand). Actions: list (names and the real values the "
						+ "menus show, such as fov 70 or mainHand RIGHT; filter finds options), set (change one option "
						+ "and save it, like in the Options menu; examples: fov 90, renderDistance 8, guiScale 2, "
						+ "soundCategory_master 0.5, mainHand left, key_key.jump key.keyboard.space; a value the game "
						+ "does not allow gives an error). Default: set if name and value are given, else list.",
				Schema.object()
						.prop("action", "string", "list or set. Default: set with name and value, else list.")
						.prop("filter", "string", "For list: only names that contain this text (not case sensitive).")
						.prop("name", "string", "For set: the option name.")
						.prop("value", "string", "For set: the new value, in the same format list shows.")
						.build(),
				args -> {
					boolean hasSet = Schema.optString(args, "name") != null && Schema.optString(args, "value") != null;
					String action = action(args, hasSet ? "set" : "list");
					return switch (action) {
						case "list" -> PRETTY.toJson(bridge.action("list_options", copy(args, "filter")));
						case "set" -> {
							if (!hasSet) {
								throw new IllegalArgumentException("set needs name and value.");
							}
							yield PRETTY.toJson(bridge.action("set_option", copy(args, "name", "value")));
						}
						default -> throw unknownAction(action, "list or set");
					};
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
						.prop("wait_ms", "integer", "Longest wait for the command feedback, 0-" + MAX_WAIT_MS + " ms. "
								+ "Default " + DEFAULT_REPLY_WAIT_MS + ". It returns when the feedback arrives.")
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
					long before = sent.get("chatIdBeforeSend").getAsLong();
					JsonObject wait = waitForReply(before, args);
					JsonObject read = new JsonObject();
					read.addProperty("since_id", before);
					read.addProperty("limit", 100);
					JsonObject replies = bridge.action("read_chat", read);
					JsonObject status = bridge.action("status", null);
					JsonObject result = new JsonObject();
					result.add("sent", sent.get("sent"));
					result.add("from", sent.get("from"));
					result.add("to", status.get("player"));
					if (wait != null) {
						result.add("replyFound", wait.get("matched"));
						result.add("waitedMs", wait.get("elapsedMs"));
					}
					result.add("replies", replies.get("messages"));
					return PRETTY.toJson(result);
				}));

		add(new Tool("scan_blocks",
				"See the blocks around the player in a box centered on the block the player stands in. Blocks are "
						+ "grouped by id (most common first), each with its count and [x,y,z] positions sorted nearest "
						+ "first. Also gives the block under the player's feet and what the crosshair targets. Air is "
						+ "left out unless include_air is true. Use exposed_only to see only blocks that touch air or "
						+ "another see-through block (the blocks a player can see), and filter to look for some blocks.",
				Schema.object()
						.prop("radius", "integer", "Horizontal distance in blocks from the player, 0-16. Default 4.")
						.prop("vertical_radius", "integer", "Vertical distance in blocks, 0-16. Default: same as radius.")
						.prop("filter", "string", "Only block ids that contain one of these comma-separated terms, "
								+ "for example \"ore\" or \"log,leaves\". Optional.")
						.prop("exposed_only", "boolean", "Only blocks with at least one see-through neighbor. Default false.")
						.prop("include_air", "boolean", "Also list air blocks. Default false.")
						.prop("include_properties", "boolean", "Add block state properties to the id, for example "
								+ "minecraft:oak_log[axis=y]. Default false.")
						.prop("max_positions_per_block", "integer", "Most positions to list per block id. Default 64.")
						.build(),
				args -> PRETTY.toJson(bridge.action("scan_blocks", copy(args, "radius", "vertical_radius", "filter",
						"exposed_only", "include_air", "include_properties", "max_positions_per_block")))));

		add(new Tool("list_entities",
				"List the entities the client knows about near the player (mobs, animals, items, projectiles, "
						+ "vehicles, other players), nearest first. Each has its numeric id, type, category, name, "
						+ "position, distance, and health for living entities or the item for dropped items. Also "
						+ "gives a count for each type. By default it lists all entities in render distance.",
				Schema.object()
						.prop("max_distance", "number", "Only entities within this many blocks. Default: render distance.")
						.prop("type", "string", "Only entity types that contain one of these comma-separated terms, "
								+ "for example \"zombie\" or \"cow,sheep,pig\". Optional.")
						.prop("include_players", "boolean", "Also list other players. Default true.")
						.prop("limit", "integer", "Most entities to list. Default 100. The counts include all.")
						.build(),
				args -> PRETTY.toJson(bridge.action("list_entities",
						copy(args, "max_distance", "type", "include_players", "limit")))));

		add(new Tool("list_players",
				"Find other players. 'nearby' lists the players the client can see (in render range), nearest first, "
						+ "with position, distance, health, armor, held items, equipment, game mode and ping. 'online' "
						+ "lists everyone on the tab list, with inRenderRange to show who is near.",
				Schema.object()
						.prop("max_distance", "number", "Only list nearby players within this many blocks. Optional.")
						.build(),
				args -> PRETTY.toJson(bridge.action("list_players", copy(args, "max_distance")))));

		add(new Tool("debug_screen",
				"Read all details from the F3 debug screen, also when the screen is closed: game version, FPS, TPS, "
						+ "memory, system specs (CPU, GPU, display), position (XYZ, block, chunk, facing), player speed, "
						+ "light levels, heightmaps, biome, local difficulty, day count, the block, fluid and entity the "
						+ "crosshair targets (with state and tags), chunk, entity and particle render stats, chunk "
						+ "source stats, spawn counts, sound info, and post effects. Each entry has its id, its F3 status "
						+ "(alwaysOn, inOverlay, never), whether it shows now, and its text lines. renderToggles lists "
						+ "the in-world debug drawings (hitboxes, chunk borders and so on) and if they are on.",
				Schema.object()
						.prop("only_shown", "boolean", "Only the entries that show on the current F3 screen. Default "
								+ "false (all entries).")
						.build(),
				args -> PRETTY.toJson(bridge.action("debug_screen", copy(args, "only_shown")))));

		add(new Tool("read_hud",
				"Read the text that the HUD shows now: title and subtitle, action bar, boss bars (name, progress, "
						+ "color, style), the scoreboard sidebar (title and lines, in the order the player sees them), "
						+ "and the tab list header and footer. Many servers and mods show data only in these places. "
						+ "The title and action bar keep their last text after they fade, so 'shown' tells if they "
						+ "show now.",
				Schema.object().build(),
				args -> PRETTY.toJson(bridge.action("read_hud", new JsonObject()))));

		add(new Tool("packets",
				"The network packet log of the client connection, to debug mods with custom payloads. Actions: read "
						+ "(default; the packets by channel, received (in) and sent (out), with count and sizes in "
						+ "bytes (total, avg, min, max, last), and the newest packets; custom payload channels come "
						+ "first, then the most packets), clear (remove the log and the totals). Without since_id the "
						+ "counts are totals since the game started or the last clear. With since_id only the kept "
						+ "packets after that id count (the log keeps the newest 4096; complete false means some were "
						+ "dropped). Sizes are the packet bytes after decompression, with the packet id. "
						+ "Works in singleplayer and on servers. Use wait_for with packet to "
						+ "wait for a packet.",
				Schema.object()
						.prop("action", "string", "read (default) or clear.")
						.prop("channel", "string", "For read: only channels that contain this text (case-insensitive), "
								+ "such as \"mymod:\". Optional.")
						.prop("channel_regex", "boolean", "For read: channel is a regex (Java syntax, find). Default false.")
						.prop("direction", "string", "For read: in, out or any (default).")
						.prop("custom_only", "boolean", "For read: only custom payload channels. Default false.")
						.prop("since_id", "integer", "For read: only packets with an id greater than this (latestId "
								+ "from an earlier call). Default 0 (the totals).")
						.prop("limit", "integer", "For read: most channels to list. Default 50.")
						.prop("recent", "integer", "For read: how many of the newest packets to list. Default 20.")
						.build(),
				args -> {
					String action = action(args, "read");
					return switch (action) {
						case "read" -> PRETTY.toJson(bridge.action("read_packets", copy(args, "channel", "channel_regex",
								"direction", "custom_only", "since_id", "limit", "recent")));
						case "clear" -> PRETTY.toJson(bridge.action("clear_packets", new JsonObject()));
						default -> throw unknownAction(action, "read or clear");
					};
				}));

		add(new Tool("lookup_registry",
				"Find the ids in a game registry that match a filter, so /give, /summon, /effect, /enchant and "
						+ "/locate commands use correct ids and do not guess. Registries: item, block, entity, biome, "
						+ "enchantment, effect, potion, sound, particle, structure and all others (call with no "
						+ "registry to list them with their sizes). Biomes, enchantments and other data-driven "
						+ "registries need a world. Each entry has its id and, for items, blocks and entities, the "
						+ "name the player sees. Exact matches come first. Data-pack registries recipe, loot_table, "
						+ "advancement, item_modifier and predicate are server data: all of them in singleplayer, "
						+ "only the sent advancements on a multiplayer server. With audit true and a namespace, it "
						+ "checks each item, block and entity of that mod and lists what is missing: translation "
						+ "(raw key shows), item or block model, texture (missing texture), loot table and recipe "
						+ "(the last two only in singleplayer).",
				Schema.object()
						.prop("registry", "string", "The registry: item, block, entity, biome, enchantment, effect, "
								+ "potion, sound, particle, structure, recipe, loot_table, advancement, or any registry id like "
								+ "worldgen/biome. Omit to "
								+ "list the registries.")
						.prop("filter", "string", "Only ids or names that contain one of these comma-separated terms, "
								+ "for example \"diamond\" or \"zombie,skeleton\". Not case sensitive. Optional.")
						.prop("tag", "string", "Only entries in this tag, for example \"minecraft:logs\" or "
								+ "\"#c:ores\". Optional.")
						.prop("include_tags", "boolean", "Add the tags of each entry. Default false.")
						.prop("limit", "integer", "Most entries to return, 1-2000. Default 100.")
						.prop("audit", "boolean", "Audit the assets and data of a mod namespace instead of a lookup. "
								+ "Needs namespace. registry can limit it to item, block or entity. Returns only "
								+ "entries with something missing. Default false.")
						.prop("namespace", "string", "For audit: the mod namespace, for example \"mymod\".")
						.build(),
				args -> PRETTY.toJson(bridge.action("lookup_registry",
						copy(args, "registry", "filter", "tag", "include_tags", "limit", "audit", "namespace")))));

		add(Tool.withContent("screenshot",
				"Take a screenshot of the game view: the world, the HUD and any open screen or menu, as the player "
						+ "sees it. Returns the image. Use this to see what a screen looks like or where things are.",
				Schema.object()
						.prop("max_width", "integer", "Scale the image down to this width or less. Default "
								+ Screenshots.DEFAULT_MAX_WIDTH + ". Use 0 for the full size.")
						.prop("format", "string", "png (default, exact) or jpeg (smaller).")
						.build(),
				args -> {
					JsonObject shot = bridge.action("screenshot", new JsonObject());
					int width = shot.get("width").getAsInt();
					int height = shot.get("height").getAsInt();
					Screenshots.Image image = Screenshots.prepare(shot.get("png").getAsString(),
							Schema.optInt(args, "max_width", Screenshots.DEFAULT_MAX_WIDTH), Schema.optString(args, "format"));
					String screen = optString(shot, "screen");
					String info = "Game view " + width + "x" + height
							+ (image.width() != width ? ", scaled to " + image.width() + "x" + image.height() : "")
							+ ". Screen: " + (screen == null ? "none (in game)" : screen) + "."
							+ (shot.has("guiWidth") ? " GUI size " + shot.get("guiWidth").getAsInt() + "x"
									+ shot.get("guiHeight").getAsInt() + ". To click a point of this image, call "
									+ "screen_input with x, y in image pixels and image_width " + image.width() + "." : "");
					JsonArray content = new JsonArray();
					content.add(textContent(info));
					JsonObject img = new JsonObject();
					img.addProperty("type", "image");
					img.addProperty("data", image.base64());
					img.addProperty("mimeType", image.mimeType());
					content.add(img);
					return content;
				}));

		add(new Tool("press_key",
				"Send a key to the game, like the real keyboard. With no screen open, keys act through their key "
						+ "bindings (w walks, space jumps, e opens the inventory, f3 shows the debug screen). With a "
						+ "screen open, the screen gets the key (escape closes it, tab moves the focus, enter confirms, "
						+ "arrows and backspace edit a text box). Key names: a-z, 0-9, space, escape, enter, tab, "
						+ "backspace, f1-f12, up, down, left, right, page.up, left.shift (or shift), left.control (or "
						+ "ctrl), or options.txt names (key.keyboard.e, key.mouse.left). A key binding name also works "
						+ "and uses the key it is bound to: jump, sneak, sprint, inventory, attack, use, key.forward. "
						+ "Mouse buttons only act in game (attack, use, pick block). press_key sends no text "
						+ "characters, so use screen_input with action type to type. To hold a key, use tap with hold_ms (tap w with "
						+ "hold_ms 1000 walks forward for one second), or press and then release.",
				Schema.object()
						.requiredProp("key", "string", "Key name or key binding, see the description.")
						.prop("action", "string", "tap (press, wait hold_ms, release; default), press (the key stays "
								+ "down until a release) or release.")
						.prop("hold_ms", "integer", "For tap: how long the key stays down, 0-10000 ms. Default 50 "
								+ "(about one game tick).")
						.prop("modifiers", "string", "Modifier keys held with the key: shift, ctrl, alt, super, or "
								+ "more than one such as \"ctrl+shift\". Optional. Example: key a with ctrl selects all "
								+ "text in a text box.")
						.build(),
				args -> PRETTY.toJson(bridge.action("press_key", copy(args, "key", "action", "hold_ms", "modifiers")))));

		add(new Tool("screen_input",
				"Act on the open screen. Pick the target in one of three ways: widget (index from screen read), "
						+ "label (text on the widget; exact match first, then part of the label, not case sensitive), "
						+ "or x and y (a point in GUI pixels, as in screen read; for a point of a screenshot also "
						+ "pass image_width). Points work on every screen, also mod screens and parts that a screen "
						+ "draws itself with no widget (recipe book, advancements, villager trades). Actions: click "
						+ "(default), double_click (for example to open a world in the world list), right_click, set "
						+ "(widget only: text box gets value; checkbox true or false; cycle button is clicked until it "
						+ "shows value; slider value as it shows, such as 90, 50% or 12, or fraction 0-1), type (type "
						+ "text like a keyboard into the target text box, or the focused one, or the only one; also "
						+ "signs and books), scroll (amount wheel steps over the target or the screen center, "
						+ "positive goes down), focus (widget only), and with a point: press, release, drag (to to_x, "
						+ "to_y with the button down), move (hover, so the next screenshot shows the tooltip). Events "
						+ "go through the screen like the real mouse and keyboard, and lists scroll by themselves to "
						+ "show a widget. If a new screen opens, the result has newScreen with its widgets.",
				Schema.object()
						.prop("action", "string", "click (default), double_click, right_click, set, type, scroll, "
								+ "focus, press, release, drag or move.")
						.prop("widget", "integer", "Target: widget index from screen read.")
						.prop("label", "string", "Target: text on the widget.")
						.prop("x", "number", "Target: X of a point.")
						.prop("y", "number", "Target: Y of a point.")
						.prop("image_width", "integer", "With x, y: width of the screenshot the point comes from. "
								+ "Leave out for GUI pixels.")
						.prop("value", "string", "For set: the text, true/false, or the value to show.")
						.prop("fraction", "number", "For set on a slider: position from 0 (left) to 1 (right).")
						.prop("text", "string", "For type: the text.")
						.prop("mode", "string", "For type: type (default; characters one by one, checked as for a "
								+ "player) or set (replace the full value at once).")
						.prop("clear", "boolean", "For type: remove the old text first. Default false.")
						.prop("submit", "boolean", "For type: press enter after the text. Default false.")
						.prop("amount", "number", "For scroll: wheel steps. Positive goes down, negative up. Default 1.")
						.prop("button", "string", "With a point: left (default), right or middle.")
						.prop("to_x", "number", "For drag: X of the end point.")
						.prop("to_y", "number", "For drag: Y of the end point.")
						.prop("steps", "integer", "For drag: number of moves from start to end, 1-200. Default 10.")
						.prop("modifiers", "string", "With a point: modifier keys held, such as shift or "
								+ "\"ctrl+shift\".")
						.build(),
				this::screenInput));

		add(new Tool("look",
				"Turn the player's view at once. Three ways: yaw and/or pitch in degrees (with relative: true they "
						+ "are added to the current view), a point x, y, z (for the center of block 10 64 -3 pass "
						+ "10.5 64.5 -3.5), or an entity id from list_entities. Yaw: 0 south (+Z), 90 west (-X), 180 "
						+ "north (-Z), -90 east (+X). Pitch: -90 up, 0 level, 90 down. Works in singleplayer and "
						+ "multiplayer. Returns the old and new view, the facing direction, and the block or entity "
						+ "under the crosshair.",
				Schema.object()
						.prop("yaw", "number", "Horizontal angle in degrees.")
						.prop("pitch", "number", "Vertical angle in degrees, -90 (up) to 90 (down).")
						.prop("relative", "boolean", "Add yaw and pitch to the current view. Default false.")
						.prop("x", "number", "X of the point to look at.")
						.prop("y", "number", "Y of the point to look at.")
						.prop("z", "number", "Z of the point to look at.")
						.prop("entity", "integer", "Id of the entity to look at (from list_entities).")
						.build(),
				args -> PRETTY.toJson(bridge.action("look", copy(args, "yaw", "pitch", "relative", "x", "y", "z", "entity")))));

		add(new Tool("move",
				"Walk the player by holding the real movement keys, relative to the view direction (use look first "
						+ "to aim). Stops after duration_ms, or when the player has moved distance blocks "
						+ "horizontally, whichever comes first. sprint holds the sprint key (needs forward, and enough "
						+ "food), sneak holds sneak, jump holds jump (jumps again on each landing, swims up in water). "
						+ "No screen can be open. Returns the position before and after, the distance, if the player "
						+ "sprinted, and blocked (true if a wall stopped the player).",
				Schema.object()
						.prop("direction", "string", "forward (default), back, left, right, a mix such as "
								+ "forward+left, or none (stay in place, for sneak or jump only).")
						.prop("duration_ms", "integer", "How long to hold the keys, 0-20000 ms. Default 1000, or "
								+ "20000 when distance is given.")
						.prop("distance", "number", "Stop after this many blocks of horizontal movement. Optional.")
						.prop("sprint", "boolean", "Sprint. Default false.")
						.prop("sneak", "boolean", "Sneak (crouch). Default false.")
						.prop("jump", "boolean", "Hold jump while moving. Default false.")
						.build(),
				args -> PRETTY.toJson(bridge.action("move",
						copy(args, "direction", "duration_ms", "distance", "sprint", "sneak", "jump")))));

		add(new Tool("jump",
				"Make the player jump once with the real jump key, and wait for the landing. Without hold_ms the key "
						+ "is held until the player leaves the ground. With hold_ms the key stays down that long (to "
						+ "swim up, climb a ladder up, or fly up in creative). No screen can be open. To jump while "
						+ "walking, use move with jump: true. Returns the position before and after, the highest Y "
						+ "(peakY), rise, and if the player left the ground and landed.",
				Schema.object()
						.prop("hold_ms", "integer", "How long to hold the jump key, 0-20000 ms. Optional.")
						.prop("wait_ms", "integer", "After the release, the longest wait for the landing, 0-20000 "
								+ "ms. Default 1500.")
						.build(),
				args -> PRETTY.toJson(bridge.action("jump", copy(args, "hold_ms", "wait_ms")))));

		add(new Tool("attack",
				"Left click on what the crosshair targets, the same as the attack mouse button (use look first to "
						+ "aim). An entity: one hit with the main hand item (for a full fight use attack_entity). A "
						+ "block: break it. In creative it breaks at once. In survival, with no hold_ms, the button "
						+ "stays down until the block breaks (at most 20 s). With hold_ms the button stays down that "
						+ "long (in creative, it breaks one more block each 5 ticks). Nothing targeted: a swing. No "
						+ "screen can be open. Returns the target before, the main hand item, attackStrength (1 is a "
						+ "full charge), block (before, after, broken, highestDestroyStage 0-9) or entity (alive, "
						+ "healthBefore, healthAfter), stoppedBy, and the target after.",
				Schema.object()
						.prop("hold_ms", "integer", "Hold the attack button this long, 0-20000 ms. Optional.")
						.build(),
				args -> PRETTY.toJson(bridge.action("attack", copy(args, "hold_ms")))));

		add(new Tool("use",
				"Right click on what the crosshair targets, the same as the use mouse button (use look first to "
						+ "aim, and inventory with action hotbar to pick the item). It places a block, uses a block (door, "
						+ "lever, button, chest, crafting table), uses the held item (bucket, flint and steel, spawn "
						+ "egg, ender pearl), or interacts with an entity (trade, ride, shear, feed). With no hold_ms "
						+ "it is a tap. With hold_ms the button stays down that long, to eat or drink (about 1700 "
						+ "ms), draw a bow, or block with a shield. While it is down and no item is in use, the game "
						+ "uses again each 4 ticks (so it can place more blocks). Stops if a screen opens (such as a "
						+ "chest). Returns the target before, usedItem, mainHand and offHand (before and after), "
						+ "blockChanges at the target block and the block in front of the clicked face, entity "
						+ "(alive, health), the screen and the target after.",
				Schema.object()
						.prop("hold_ms", "integer", "Hold the use button this long, 0-20000 ms. Default: a tap.")
						.build(),
				args -> PRETTY.toJson(bridge.action("use", copy(args, "hold_ms")))));

		add(new Tool("reload_resources",
				"Reload resource packs (like F3+T) and, in singleplayer, data packs (like /reload). Use it for a fast "
						+ "loop when you make a mod: change textures, models, lang, sounds, recipes, loot tables or tags, "
						+ "then reload without a restart. Java code changes need a restart. Waits for the reload to end and "
						+ "returns the pack lists and the time it took. On a multiplayer server, data packs are skipped.",
				Schema.object()
						.prop("resources", "boolean", "Reload resource packs (assets). Default true.")
						.prop("data", "boolean", "Reload data packs (singleplayer only). Default true.")
						.build(),
				args -> PRETTY.toJson(bridge.action("reload_resources", copy(args, "resources", "data")))));

		add(new Tool("reload_chunks",
				"Rebuild all loaded chunks, like F3+A. Use it when block models, textures, colors or render layers "
						+ "look old after reload_resources, or when chunks show wrong. Waits until all chunk sections are "
						+ "built again (allBuilt) or wait_ms ends, and gives the section counts and the time it took.",
				Schema.object()
						.prop("wait_ms", "integer", "Longest wait for the rebuild, 0-25000. 0 returns at once. Default 10000.")
						.build(),
				args -> PRETTY.toJson(bridge.action("reload_chunks", copy(args, "wait_ms")))));

		addPathfindingTools();

		add(new Tool("minecraft_launch_log",
				"Show the last lines of the Gradle/Minecraft launch log. Use this to debug a failed launch.",
				Schema.object()
						.prop("lines", "integer", "Number of lines from the end. Default 80.")
						.build(),
				args -> "Log file: " + launcher.logFile() + "\n" + launcher.tailLog(Schema.optInt(args, "lines", 80))));

		add(new Tool("list_minecraft_logs",
				"List the Minecraft client log files with size and time: latest.log, debug.log, older logs (.log.gz), "
						+ "crash reports, and the launch log. These are read from disk, so this works when the game is "
						+ "stopped or crashed.",
				Schema.object().build(),
				args -> logs.list()));

		add(new Tool("scan_minecraft_log",
				"Search a Minecraft client log for debugging. Works when the game is stopped or crashed. The log is "
						+ "split into entries: a line like \"[12:00:01] [Render thread/WARN] (logger) text\" and the "
						+ "lines after it with no header (stack traces). Filters: text or regex pattern, minimum level, "
						+ "thread, logger, time, exceptions only. Each result line has its line number; > marks the "
						+ "matching entries and the other lines are context. Returns the newest matches unless from_start "
						+ "is true. Use the returned lastLine as since_line to see only new entries later. Set summary=true "
						+ "to get counts per level, the most common warnings and errors, and the exception lines instead.",
				Schema.object()
						.prop("source", "string", "latest (default, the INFO log of the current or last run), debug "
								+ "(also DEBUG lines from Gravel and Fabric), launch (Gradle output), crash (newest crash "
								+ "report), or a file name from list_minecraft_logs such as 2026-11-20-3.log.gz.")
						.prop("pattern", "string", "Only entries whose text (with the stack trace) has this text. Optional.")
						.prop("regex", "boolean", "Use pattern as a Java regex. Default false (plain text).")
						.prop("ignore_case", "boolean", "Ignore upper and lower case in pattern. Default true.")
						.prop("min_level", "string", "Only this level and higher: TRACE, DEBUG, INFO, WARN, ERROR, FATAL. Optional.")
						.prop("thread", "string", "Only threads whose name has this text, for example \"Render thread\" "
								+ "or \"Server thread\". Optional.")
						.prop("logger", "string", "Only loggers whose name has this text, for example \"gravel\" or "
								+ "\"FabricLoader\". Optional.")
						.prop("since_time", "string", "Only entries at this time or later, HH:MM:SS. Optional.")
						.prop("since_line", "integer", "Only entries that start after this line number. Default 0.")
						.prop("exceptions_only", "boolean", "Only entries with an exception or stack trace. Default false.")
						.prop("context", "integer", "Entries to show before and after each match. Default 0.")
						.prop("limit", "integer", "Most matching entries to show. Default 50.")
						.prop("from_start", "boolean", "Show the first matches, not the newest ones. Default false.")
						.prop("max_lines_per_entry", "integer", "Cut each entry (stack trace) to this many lines, 0 for "
								+ "no cut. Default 30.")
						.prop("summary", "boolean", "Give a summary of the log, not the matching entries. Default false.")
						.prop("top", "integer", "With summary: how many groups to list. Default 20.")
						.build(),
				args -> {
					String source = Schema.optString(args, "source");
					if (Schema.optBool(args, "summary", false)) {
						return logs.summary(source, Schema.optInt(args, "top", 20));
					}
					return logs.scan(source, new LogScanner.Query(
							Schema.optString(args, "pattern"),
							Schema.optBool(args, "regex", false),
							Schema.optBool(args, "ignore_case", true),
							Schema.optString(args, "min_level"),
							Schema.optString(args, "thread"),
							Schema.optString(args, "logger"),
							Schema.optString(args, "since_time"),
							Schema.optInt(args, "since_line", 0),
							Schema.optBool(args, "exceptions_only", false),
							Schema.optInt(args, "context", 0),
							Schema.optInt(args, "limit", 50),
							Schema.optBool(args, "from_start", false),
							Schema.optInt(args, "max_lines_per_entry", 30)));
				}));
	}

	/** Tools that use the Baritone mod. Without Baritone in run/mods they give an error that says so. */
	private void addPathfindingTools() {
		String waitDoc = "Wait until Baritone stops, or this many ms, 0-20000. Default 0 (return at once). Use "
				+ "path_status to wait more.";

		add(new Tool("path_to",
				"Walk the player to a place with Baritone pathfinding (needs the Baritone mod in run/mods). Baritone "
						+ "finds a path and walks, jumps, swims, climbs and (if its settings allow) breaks and places "
						+ "blocks. Goal: x, y and z (y is the feet level, one above the ground block), with an "
						+ "optional radius; x and z only (any height); y only (a height); or block (the nearest block "
						+ "of a type, such as crafting_table). Stops any other Baritone task first. Returns at once "
						+ "with the status, or after wait_ms. Long trips take more than one call: use path_status "
						+ "with wait_ms. Status state: running, at_goal, failed, canceled, stopped, finished or idle.",
				Schema.object()
						.prop("x", "integer", "Goal X.")
						.prop("y", "integer", "Goal Y (feet level). Optional with x and z.")
						.prop("z", "integer", "Goal Z.")
						.prop("radius", "integer", "With x, y and z: stop within this many blocks. Optional.")
						.prop("block", "string", "Go to the nearest block of this type, such as minecraft:chest. "
								+ "Instead of x/y/z.")
						.prop("wait_ms", "integer", waitDoc)
						.build(),
				args -> PRETTY.toJson(bridge.action("path_to", copy(args, "x", "y", "z", "radius", "block", "wait_ms")))));

		add(new Tool("path_status",
				"The state of Baritone: running or not, the process (such as Custom Goal or Mine), the goal, the "
						+ "last task Gravel started and its result (at_goal, failed, canceled, stopped, finished), "
						+ "estimated seconds to the goal, the player position and the recent path events. With "
						+ "wait_ms it first waits until Baritone stops (or the time ends).",
				Schema.object()
						.prop("wait_ms", "integer", "Wait until Baritone stops, or this many ms, 0-20000. Default 0.")
						.build(),
				args -> PRETTY.toJson(bridge.action("path_status", copy(args, "wait_ms")))));

		add(new Tool("path_cancel",
				"Stop all Baritone tasks (path, mine, follow, explore and others) and release the keys. Returns the "
						+ "status.",
				Schema.object().build(),
				args -> PRETTY.toJson(bridge.action("path_cancel", new JsonObject()))));

		add(new Tool("mine",
				"Mine blocks with Baritone: it finds the blocks, walks to them and breaks them. Works best with the "
						+ "right tool in the inventory. Stops any other Baritone task first. Runs until count blocks "
						+ "are mined (0 or none: no limit, use path_cancel to stop).",
				Schema.object()
						.requiredProp("blocks", "string", "Block names, comma separated, such as \"diamond_ore, "
								+ "deepslate_diamond_ore\" or \"minecraft:oak_log\".")
						.prop("count", "integer", "Stop after this many blocks. Default 0 (no limit).")
						.prop("wait_ms", "integer", waitDoc)
						.build(),
				args -> PRETTY.toJson(bridge.action("mine", copy(args, "blocks", "count", "wait_ms")))));

		add(new Tool("follow",
				"Follow an entity with Baritone until path_cancel. Give one of: entity (id from list_entities), "
						+ "player (name) or type (such as minecraft:cow; follows all loaded entities of that type). "
						+ "Stops any other Baritone task first.",
				Schema.object()
						.prop("entity", "integer", "Entity id from list_entities.")
						.prop("player", "string", "Player name.")
						.prop("type", "string", "Entity type, such as cow or minecraft:cow.")
						.prop("wait_ms", "integer", waitDoc)
						.build(),
				args -> PRETTY.toJson(bridge.action("follow", copy(args, "entity", "player", "type", "wait_ms")))));

		add(new Tool("attack_entity",
				"Kill a living entity: Baritone follows it, and Gravel turns to it and hits it each time it is in "
						+ "reach, the attack is fully charged and the target can take damage again. It uses the item "
						+ "in the main hand, so select a weapon first for more damage. Give entity (id from "
						+ "list_entities) or type (the nearest one of that type). Stops any other Baritone task "
						+ "first. Runs until the target dies, then stops Baritone. The status has attack: target, "
						+ "hits, targetHealth and result (running, killed, lost, stopped, canceled, left_world, "
						+ "replaced). Use path_status with wait_ms to wait more, path_cancel to stop.",
				Schema.object()
						.prop("entity", "integer", "Entity id from list_entities.")
						.prop("type", "string", "Entity type, such as cow or minecraft:zombie. Attacks the nearest one.")
						.prop("wait_ms", "integer", waitDoc)
						.build(),
				args -> PRETTY.toJson(bridge.action("attack_entity", copy(args, "entity", "type", "wait_ms")))));

		add(new Tool("baritone_command",
				"Run any Baritone command, as if typed with the # prefix in chat: explore, farm, build, sel, "
						+ "waypoints, goto, mine, follow, thisway, surface, help and more. The # is optional. Returns "
						+ "Baritone's output lines and the status (after wait_ms if a task started).",
				Schema.object()
						.requiredProp("command", "string", "The command, such as \"explore\", \"farm 64\" or \"help goto\".")
						.prop("wait_ms", "integer", waitDoc)
						.build(),
				args -> PRETTY.toJson(bridge.action("baritone_command", copy(args, "command", "wait_ms")))));

		add(new Tool("baritone_settings",
				"Read or change Baritone settings. With name and value: set it (saved to Baritone's settings file). "
						+ "With name only: show it. With filter: settings whose names have that text. With nothing: "
						+ "the settings that differ from the default. Useful: allowBreak, allowPlace, allowSprint, "
						+ "allowParkour, allowInventory, freeLook, blockReachDistance, followRadius.",
				Schema.object()
						.prop("name", "string", "Setting name, such as allowBreak. Not case sensitive.")
						.prop("value", "string", "New value, such as true, 4.5 or minecraft:dirt,minecraft:stone.")
						.prop("filter", "string", "Search the setting names for this text.")
						.build(),
				args -> PRETTY.toJson(bridge.action("baritone_settings", copy(args, "name", "value", "filter")))));
	}

	/** The action argument in lower case, or the fallback. */
	private static String action(JsonObject args, String fallback) {
		String action = Schema.optString(args, "action");
		return action == null || action.isBlank() ? fallback : action.trim().toLowerCase(Locale.ROOT);
	}

	private static IllegalArgumentException unknownAction(String action, String allowed) {
		return new IllegalArgumentException("action must be " + allowed + ", not '" + action + "'");
	}

	private static int requireInt(JsonObject args, String name, String action) {
		if (!args.has(name) || args.get(name).isJsonNull()) {
			throw new IllegalArgumentException(action + " needs " + name + ".");
		}
		return args.get(name).getAsInt();
	}

	private static boolean has(JsonObject args, String name) {
		return args.has(name) && !args.get(name).isJsonNull();
	}

	private String sendChat(String message, JsonObject args) throws Exception {
		if (message == null || message.isEmpty()) {
			throw new IllegalArgumentException("send needs message.");
		}
		JsonObject params = new JsonObject();
		params.addProperty("message", message);
		JsonObject sent = bridge.action("send_chat", params);
		long before = sent.get("chatIdBeforeSend").getAsLong();
		JsonObject wait = waitForReply(before, args);
		JsonObject read = new JsonObject();
		read.addProperty("since_id", before);
		read.addProperty("limit", 100);
		JsonObject replies = bridge.action("read_chat", read);
		JsonObject result = new JsonObject();
		result.add("sent", sent.get("sent"));
		if (wait != null) {
			result.add("replyFound", wait.get("matched"));
			result.add("waitedMs", wait.get("elapsedMs"));
		}
		result.add("replies", replies.get("messages"));
		result.add("latestId", replies.get("latestId"));
		return PRETTY.toJson(result);
	}

	/** screen_input actions that only work at a point. */
	private static final Set<String> POINT_ONLY = Set.of("press", "release", "drag", "move");
	/** screen_input actions that only work on a widget. */
	private static final Set<String> WIDGET_ONLY = Set.of("set", "focus");

	/**
	 * Sends screen_input to the mod action that does it: mouse for a point, enter_text for type, and
	 * interact_widget for a widget or label.
	 */
	private String screenInput(JsonObject args) throws Exception {
		String action = action(args, "click");
		boolean hasWidget = has(args, "widget") || has(args, "label");
		boolean hasPoint = has(args, "x") || has(args, "y");
		if (hasWidget && hasPoint) {
			throw new IllegalArgumentException("Pass widget or label, or x and y, not both.");
		}
		if (action.equals("type")) {
			if (hasPoint) {
				throw new IllegalArgumentException("type needs widget or label (a text box), or no target for the "
						+ "focused or only text box.");
			}
			if (!has(args, "text")) {
				throw new IllegalArgumentException("type needs text.");
			}
			if (hasWidget) {
				JsonObject focus = copy(args, "widget", "label");
				focus.addProperty("action", "focus");
				bridge.action("interact_widget", focus);
			}
			return PRETTY.toJson(bridge.action("enter_text", copy(args, "text", "mode", "clear", "submit")));
		}
		boolean known = switch (action) {
			case "click", "double_click", "right_click", "scroll", "set", "focus", "press", "release", "drag", "move" -> true;
			default -> false;
		};
		if (!known) {
			throw unknownAction(action, "click, double_click, right_click, set, type, scroll, focus, press, release, "
					+ "drag or move");
		}
		if (hasPoint || POINT_ONLY.contains(action)) {
			if (hasWidget) {
				throw new IllegalArgumentException(action + " needs x and y, not widget or label.");
			}
			if (WIDGET_ONLY.contains(action)) {
				throw new IllegalArgumentException(action + " needs widget or label, not x and y.");
			}
			JsonObject params = copy(args, "x", "y", "button", "to_x", "to_y", "steps", "amount", "image_width", "modifiers");
			if (action.equals("right_click")) {
				params.addProperty("action", "click");
				params.addProperty("button", "right");
			} else {
				params.addProperty("action", action);
			}
			return PRETTY.toJson(bridge.action("mouse", params));
		}
		JsonObject params = copy(args, "widget", "label", "value", "fraction", "amount");
		params.addProperty("action", action);
		return PRETTY.toJson(bridge.action("interact_widget", params));
	}

	/** Copies the given arguments, if present and not null, into a new params object for the bridge. */
	static JsonObject copy(JsonObject args, String... keys) {
		JsonObject params = new JsonObject();
		for (String key : keys) {
			if (args != null && args.has(key) && !args.get(key).isJsonNull()) {
				params.add(key, args.get(key).deepCopy());
			}
		}
		return params;
	}

	/**
	 * Waits in the game for a chat line after {@code sinceId}: a line that contains wait_for, or any line. Stops
	 * after wait_ms. Returns the wait_for result, or null if wait_ms is 0.
	 */
	private JsonObject waitForReply(long sinceId, JsonObject args) throws Exception {
		int waitMs = Schema.optInt(args, "wait_ms", DEFAULT_REPLY_WAIT_MS);
		if (waitMs < 0 || waitMs > MAX_WAIT_MS) {
			throw new IllegalArgumentException("wait_ms must be 0-" + MAX_WAIT_MS);
		}
		if (waitMs == 0) {
			return null;
		}
		String text = Schema.optString(args, "wait_for");
		JsonObject params = new JsonObject();
		params.addProperty("chat", text == null ? "" : text);
		params.addProperty("chat_regex", Schema.optBool(args, "wait_for_regex", false));
		params.addProperty("since_id", sinceId);
		params.addProperty("timeout_ms", waitMs);
		// Feedback can have more than one line. They come in the same tick or just after.
		params.addProperty("settle_ticks", REPLY_SETTLE_TICKS);
		return bridge.action("wait_for", params);
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
		// Stdin closed: Claude Code ended the session. Close the game this server started.
		workers.shutdownNow();
		launcher.stopIfLaunched();
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
					JsonArray content;
					boolean isError = false;
					try {
						content = tool.handler().call(args);
					} catch (Exception e) {
						isError = true;
						content = new JsonArray();
						content.add(textContent(e.getClass().getSimpleName() + ": " + e.getMessage()));
					}
					JsonObject result = new JsonObject();
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

	static JsonObject textContent(String text) {
		JsonObject c = new JsonObject();
		c.addProperty("type", "text");
		c.addProperty("text", text);
		return c;
	}

	/** A tool that answers with text. */
	@FunctionalInterface
	interface Handler {
		String call(JsonObject args) throws Exception;
	}

	/** A tool that answers with MCP content items, such as text and images. */
	@FunctionalInterface
	interface ContentHandler {
		JsonArray call(JsonObject args) throws Exception;
	}

	/** A tool: its name, description, input schema and handler. */
	static final class Tool {
		private final String name;
		private final String description;
		private final JsonObject inputSchema;
		private final ContentHandler handler;

		/** A tool that answers with text. */
		Tool(String name, String description, JsonObject inputSchema, Handler text) {
			this(args -> {
				JsonArray content = new JsonArray();
				content.add(textContent(text.call(args)));
				return content;
			}, name, description, inputSchema);
		}

		private Tool(ContentHandler handler, String name, String description, JsonObject inputSchema) {
			this.name = name;
			this.description = description;
			this.inputSchema = inputSchema;
			this.handler = handler;
		}

		/** A tool that answers with content items, such as an image. */
		static Tool withContent(String name, String description, JsonObject inputSchema, ContentHandler handler) {
			return new Tool(handler, name, description, inputSchema);
		}

		String name() {
			return name;
		}

		String description() {
			return description;
		}

		JsonObject inputSchema() {
			return inputSchema;
		}

		ContentHandler handler() {
			return handler;
		}
	}
}
