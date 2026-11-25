# gravel

Claude code MCP for Minecraft 26.3 as a Fabric mod.

This MCP is intended for rapid local development of Minecraft mods and server content, it should not be used on public servers.

## Claude Code MCP

Gravel has two parts:

- **The Fabric mod** (`src/`). On client start it opens an HTTP bridge on `127.0.0.1:25599`
  (change with `-Dgravel.bridge.port` or `GRAVEL_BRIDGE_PORT`). Actions are registered in
  `client/bridge/BuiltinActions.java` and run on the client thread.
- **The MCP server** (`mcp-server/`). Claude Code starts it over stdio (see `.mcp.json`).
  It starts Minecraft with `gradlew runClient` and sends tool calls to the bridge.
  When the Claude Code session ends, it closes the game that it started. A game you started yourself stays open.
- **Music** is off in the dev client (`-Dgravel.music=true` or `gradlew runClient -Pmusic=true` turns it on).
  The music volume in `options.txt` does not change.

Build both with `./gradlew build`, then restart Claude Code in this folder and approve the `gravel` server.

To use Gravel in all projects, install it in user scope. The script builds the jar and runs `claude mcp add --scope user`:

- Windows: `powershell -ExecutionPolicy Bypass -File install-mcp.ps1`
- Linux / macOS: `./install-mcp.sh`

Add `-SkipBuild` (or `--skip-build`) if the jar is already built. Restart Claude Code after the install.

Tools:

| Tool | What it does |
|---|---|
| `launch_minecraft` | Starts the dev client (optional `world` opens a save on start). Music is off unless `music: true` |
| `stop_minecraft` | Quits the game |
| `minecraft_status` | In world or not, screen, world name, player position and health |
| `join_world` | Joins a singleplayer world (default `temp`). Creates it if missing (creative, cheats on). `recreate: true` deletes it and makes a new one |
| `join_server` | Joins a multiplayer server by `address` (`host`, `host:port`). Leaves the current world first. Waits for the join. A failed join gives the disconnect screen text (for example "Failed to connect to the server: Connection refused"). Optional `resource_pack`: `prompt`, `enabled` or `disabled` |
| `read_disconnects` | Recent disconnect screens (connection failures, login rejections, kicks) with title, reason and address |
| `chat` | `send` (default with `message`): sends a message, or a command if it starts with `/`, waits for a reply (`wait_ms`, `wait_for`, `wait_for_regex`) and returns the new lines. `read` (default without `message`): received chat, `since_id`, `limit`, `include_action_bar`. `open`: opens the chat screen with `message` in the box |
| `wait_for` | Waits for `ticks` client ticks, or until conditions are true: a `chat` line matches (text or `chat_regex`), a `screen` is open (or `none`), `in_world`, or the player is near `x`/`y`/`z` (`radius`). `timeout_ms` (max 25000), `settle_ticks`. Returns `matched` and `stoppedBy` |
| `inventory` | `read` (default): items in the open container or the player inventory, `include_empty`. `move`: `from`, `to`, `count` with real clicks. `click`: one slot click (`slot`, `mode` PICKUP, QUICK_MOVE, SWAP, THROW, CLONE, QUICK_CRAFT, PICKUP_ALL, `button`). `hotbar`: selects hotbar `slot` 0-8 |
| `screen` | `read` (default): the open menu as data, title and every widget (buttons, sliders, cycle buttons, check boxes, text boxes, tabs, lists and entries, labels) with index, label, value, state and position; `interactive_only`, `include_hidden`. `open`: `name` `inventory`, `pause` or `options`. `close`: like ESC |
| `options` | `list` (default): options by their options.txt names, with values, `filter`. `set` (default with `name` and `value`): changes one option and saves it |
| `chunk_info` | Rendered chunks and sections (the F3 "C:" value), farthest rendered chunk, loaded chunks, render and simulation distance, and if all sections are built |
| `teleport` | Singleplayer only (cheats on): moves the player with `/tp` to `x`,`y`,`z`, optional `yaw`/`pitch`. Values can be numbers, `~` (relative) or `^` (view direction). Returns the command feedback and the position before and after |
| `scan_blocks` | Blocks in a box around the player (`radius`, `vertical_radius`, max 16), grouped by id with counts and positions (nearest first). Also the block under the feet and the crosshair target. `filter`, `exposed_only`, `include_air`, `include_properties`, `max_positions_per_block` |
| `list_entities` | Entities near the player, nearest first, with id, type, name, position, distance and health (or the item for dropped items), and a count for each type. `max_distance`, `type`, `include_players`, `limit` |
| `list_players` | Other players: `nearby` (in render range, with position, health, armor, equipment, game mode and ping) and `online` (the tab list). Optional `max_distance` |
| `read_hud` | The text the HUD shows: title and subtitle, action bar, boss bars, scoreboard sidebar, and tab list header and footer. `shown` tells if the title and action bar show now |
| `lookup_registry` | Finds ids in a game registry (`registry`: `item`, `block`, `entity`, `biome`, `enchantment`, `effect`, `sound`, any registry id) that match a `filter`, for correct `/give` and `/summon` commands. Gives the player-visible name for items, blocks and entities. `tag` (only entries in a tag), `include_tags`, `limit`. With no `registry` it lists the registries |
| `debug_screen` | All F3 debug screen data, also when F3 is closed: version, FPS, TPS, memory, system specs, position, light, biome, crosshair target, render stats and more. `renderToggles` shows the debug drawings (hitboxes, chunk borders). `only_shown` gives only the entries on the current F3 screen |
| `screenshot` | Image of the game view (world, HUD and open screen). `max_width` scales it down (default 1280), `format` png or jpeg |
| `press_key` | Sends a key like the real keyboard: `w`, `space`, `escape`, `f3`, `key.mouse.left`, or a key binding (`jump`, `inventory`). `tap` with `hold_ms`, or `press` and `release`, holds a key. Optional `modifiers` (`ctrl+shift`) |
| `screen_input` | Acts on the open screen. Target: `widget` index, `label`, or a point `x`,`y` (GUI pixels, or screenshot pixels with `image_width`), so it also works on mod screens and parts drawn with no widget. Actions: `click`, `double_click`, `right_click`, `set` (text, check box, cycle button value, slider `value` or `fraction`), `type` (`text`, `mode`, `clear`, `submit`), `scroll` (`amount`), `focus`, and at a point `press`, `release`, `drag` (to `to_x`,`to_y`), `move` (hover). `button`, `modifiers`. Returns the new screen if one opens |
| `look` | Turns the view: `yaw`/`pitch` (with `relative: true` they add to the view), a point `x`,`y`,`z`, or an `entity` id. Returns the crosshair target |
| `move` | Walks with the real movement keys: `direction` (`forward`, `back`, `left`, `right`, `forward+left`, `none`), `duration_ms` and/or `distance`, `sprint`, `sneak`, `jump`. Returns positions, distance and `blocked` |
| `jump` | Jumps once and waits for the landing. `hold_ms` holds the key (swim up, climb, fly up) |
| `attack` | Left click on the crosshair target: hits an entity, or breaks a block (in survival it holds until the block breaks). `hold_ms` holds the button. Returns the block or entity before and after |
| `use` | Right click on the crosshair target: places a block, uses a block (door, chest), uses the held item, or interacts with an entity. `hold_ms` holds the button (eat, draw a bow). Returns item, block and entity changes |
| `reload_resources` | Reloads resource packs (F3+T) and, in singleplayer, data packs (/reload). Use it for a fast loop when you make a mod. `resources: false` or `data: false` skips one |
| `reload_chunks` | Rebuilds all loaded chunks (F3+A) and waits until they are built again (`wait_ms`, default 10000). `allBuilt` tells if the rebuild ended in time |
| `path_to` | Baritone pathfinding to `x`,`y`,`z` (optional `radius`), `x`,`z`, `y`, or the nearest `block`. Returns at once or after `wait_ms`, with the status |
| `path_status` | Baritone state (`running`, `at_goal`, `failed`, `canceled` and more), goal, estimated time, position and path events. `wait_ms` waits for the end |
| `path_cancel` | Stops all Baritone tasks |
| `mine` | Baritone mines `blocks` (comma separated), optional `count` |
| `follow` | Baritone follows an `entity` id, a `player` or a `type` |
| `attack_entity` | Kills an `entity` id or the nearest of a `type`: Baritone follows it and Gravel hits it when it is in reach and the attack is charged. Uses the item in the main hand. `path_status` shows `attack.hits`, `targetHealth` and `result` |
| `baritone_command` | Runs any Baritone `#` command (explore, farm, build, ...) and returns its output |
| `baritone_settings` | Reads or changes Baritone settings (`allowBreak`, `allowPlace`, ...) |
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

## Pathfinding (optional)

The path tools need [Baritone](https://github.com/cabaletta/baritone) (LGPL-3.0) **v1.20.0**. Gravel does not include it.
Put [`baritone-api-fabric-1.20.0.jar`](https://github.com/cabaletta/baritone/releases/tag/v1.20.0) in `run/mods`.
Without it, only the path tools fail. While Baritone moves the player, `move`, `jump`, `attack` and `use` fail: use `path_cancel` first.
