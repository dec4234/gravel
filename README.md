# gravel

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
| `open_chat` | Opens the chat screen, with optional text in the box |
| `send_chat` | Sends a message, or a command if it starts with `/`. Returns the replies |
| `read_chat` | Reads received chat. Use `since_id` to get only new lines |
| `minecraft_launch_log` | Last lines of the launch log |

To add an action: register it in `BuiltinActions`, then add a tool in `GravelMcpServer.registerTools()`
that calls `bridge.action("<name>", args)`.
