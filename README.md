# gravel

Claude code MCP for Minecraft 26.3 as a Fabric mod.

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## Claude Code MCP

Gravel has two parts:

- **The Fabric mod** (`src/`). On client start it opens an HTTP bridge on `127.0.0.1:25599`
  (change with `-Dgravel.bridge.port` or `GRAVEL_BRIDGE_PORT`). Actions are registered in
  `client/bridge/BuiltinActions.java` and run on the client thread.
- **The MCP server** (`mcp-server/`). Claude Code starts it over stdio (see `.mcp.json`).
  It starts Minecraft with `gradlew runClient` and sends tool calls to the bridge.

Build both with `./gradlew build`, then restart Claude Code in this folder and approve the `gravel` server.

Tools:

| Tool | What it does |
|---|---|
| `launch_minecraft` | Starts the dev client (optional `world` opens a save on start) |
| `stop_minecraft` | Quits the game |
| `minecraft_status` | In world or not, screen, world name, player position and health |
| `join_world` | Joins a singleplayer world (default `temp`). Creates it if missing (creative, cheats on). `recreate: true` deletes it and makes a new one |
| `join_server` | Joins a multiplayer server by `address` (`host`, `host:port`). Leaves the current world first. Waits for the join. A failed join gives the disconnect screen text (for example "Failed to connect to the server: Connection refused"). Optional `resource_pack`: `prompt`, `enabled` or `disabled` |
| `read_disconnects` | Recent disconnect screens (connection failures, login rejections, kicks) with title, reason and address |
| `open_chat` | Opens the chat screen, with optional text in the box |
| `send_chat` | Sends a message, or a command if it starts with `/`. Returns the replies |
| `read_chat` | Reads received chat. Use `since_id` to get only new lines |
| `select_hotbar_slot` | Selects hotbar slot 0-8, like a number key |
| `open_inventory` | Opens the inventory, like E (creative opens on the Survival Inventory tab) |
| `read_inventory` | Lists the items in the open container, or the player inventory, and the cursor item |
| `move_item` | Moves a stack, or part of it, from one slot to another with real clicks |
| `click_slot` | One slot click: pickup, shift-click, swap with hotbar, throw, and more |
| `open_pause_menu` | Opens the pause (ESC) menu |
| `open_options` | Opens the Options menu |
| `close_screen` | Closes the open screen, like ESC |
| `list_options` | Lists options by their options.txt names, with values |
| `set_option` | Changes one option and saves it |
| `screenshot` | Image of the game view (world, HUD and open screen). `max_width` scales it down (default 1280), `format` png or jpeg |
| `press_key` | Sends a key like the real keyboard: `w`, `space`, `escape`, `f3`, `key.mouse.left`, or a key binding (`jump`, `inventory`). `tap` with `hold_ms`, or `press` and `release`, holds a key. Optional `modifiers` (`ctrl+shift`) |
| `enter_text` | Types into a text box of the open screen (world name, server address, search, anvil, sign, book). Without `text` it lists the text boxes. `field` picks a box, `mode: set` replaces the value, `clear` and `submit` (press enter) |
| `read_screen` | Reads the open menu as data: title and every widget (buttons, sliders, cycle buttons, check boxes, text boxes, tabs, lists and entries, labels) with index, label, value, state and position. `interactive_only`, `include_hidden` |
| `interact_widget` | Uses a widget by `widget` index or `label`: `click`, `double_click`, `right_click`, `set` (text, check box, cycle button value, slider `value` or `fraction`), `scroll` (`amount`), `focus`. Lists scroll to the widget. Returns the new screen if one opens |
| `minecraft_launch_log` | Last lines of the launch log |
| `list_minecraft_logs` | Lists the client logs (`latest.log`, `debug.log`, old `.log.gz` files), crash reports and the launch log |
| `scan_minecraft_log` | Searches a log by text or regex, level, thread, logger and time, with context and stack traces. `since_line` gets only new entries. `summary: true` counts levels and groups the warnings, errors and exceptions. Works when the game is stopped or crashed |

To add an action: register it in `BuiltinActions` (use `registerAsync` if the result comes later, as for
`screenshot`), then add a tool in `GravelMcpServer.registerTools()`
that calls `bridge.action("<name>", args)`.

### Multiplayer accounts

The dev client starts with an offline account (`Player123`), so it can only join servers with
`online-mode=false`. `minecraft_status` shows the account in `session.authMode`. If an online-mode server
rejects the client, the disconnect has a `hint` that says so. Online accounts are not supported yet:
see `client/bridge/SessionInfo.java` for where to add them.
