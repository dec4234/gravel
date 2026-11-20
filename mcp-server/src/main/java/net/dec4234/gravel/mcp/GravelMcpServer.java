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

		new GravelMcpServer(protocolOut, projectDir, port)
				.serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
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
							+ ". Screen: " + (screen == null ? "none (in game)" : screen) + ".";
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
						+ "characters, so use enter_text to type. To hold a key, use tap with hold_ms (tap w with "
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

		add(new Tool("enter_text",
				"Type text into a text box of the open screen: world name, server address, search boxes, anvil name, "
						+ "sign, book, command block and others (for chat, use send_chat or open_chat). The text goes "
						+ "to the focused text box. If the screen has only one text box, it gets the focus. If it has "
						+ "more, pass field. Call without text to list the text boxes (field index, label, value, "
						+ "focused, position). Mode type sends the characters like a keyboard, so the box checks them "
						+ "as for a player. Mode set replaces the full value at once.",
				Schema.object()
						.prop("text", "string", "Text to enter. Leave out to only list the text boxes.")
						.prop("field", "integer", "Index of the text box from the list. Default: the focused box, "
								+ "or the only box.")
						.prop("mode", "string", "type (default) or set.")
						.prop("clear", "boolean", "With mode type: remove the old text first. Default false.")
						.prop("submit", "boolean", "Press enter after the text. Default false.")
						.build(),
				args -> PRETTY.toJson(bridge.action("enter_text", copy(args, "text", "field", "mode", "clear", "submit")))));

		add(new Tool("read_screen",
				"Read the open screen (menu) as data: its class, title, size, and every widget in it. Widget types: "
						+ "button, cycle_button (value), checkbox (checked), slider (fraction 0-1, label shows the value), "
						+ "text_box (value), tab (selected), list (entries, scroll), list_entry (selected), text (a "
						+ "label), container. Each widget has its index (widget), label, parent, interactive, active, "
						+ "visible, inView (not scrolled out), focused, position and size in GUI pixels, and tooltip. "
						+ "Use the index or the label with interact_widget. Works for all menus: title screen, world "
						+ "and server lists, create world, options, pause menu, and mod menus. For item slots use "
						+ "read_inventory.",
				Schema.object()
						.prop("interactive_only", "boolean", "Only widgets that can be used now (no labels, containers or "
								+ "greyed out widgets). Default false.")
						.prop("include_hidden", "boolean", "Also list hidden widgets. Default false.")
						.build(),
				args -> PRETTY.toJson(bridge.action("read_screen", copy(args, "interactive_only", "include_hidden")))));

		add(new Tool("interact_widget",
				"Use a widget of the open screen, by its index from read_screen (widget) or its label text (label, "
						+ "exact match first, then part of the label). Actions: click (default; a real left click "
						+ "through the screen, so buttons press, tabs open, list entries are selected), double_click "
						+ "(for example to open a world in the world list), right_click, set (text box: the text; "
						+ "checkbox: true or false; cycle button: the value to show, clicked until it shows; slider: "
						+ "value as the slider shows it, such as 90, 50% or 12, or fraction 0-1), scroll (amount wheel "
						+ "steps over the widget or the screen center, positive goes down), focus. Lists scroll by "
						+ "themselves to show the widget. If the action opens a new screen, the result has newScreen "
						+ "with its widgets.",
				Schema.object()
						.prop("widget", "integer", "Widget index from read_screen.")
						.prop("label", "string", "Text on the widget, instead of widget. Not case sensitive.")
						.prop("action", "string", "click (default), double_click, right_click, set, scroll or focus.")
						.prop("value", "string", "For set: the text, true/false, or the value to show.")
						.prop("fraction", "number", "For set on a slider: position from 0 (left) to 1 (right).")
						.prop("amount", "number", "For scroll: wheel steps. Positive goes down, negative up. Default 1.")
						.build(),
				args -> PRETTY.toJson(bridge.action("interact_widget",
						copy(args, "widget", "label", "action", "value", "fraction", "amount")))));

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
