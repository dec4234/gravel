#!/usr/bin/env sh
# Installs the Gravel MCP server for Claude Code in user scope (all projects).
# Usage: ./install-mcp.sh [--skip-build]
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$PROJECT_DIR/mcp-server/build/libs/gravel-mcp.jar"

for cmd in java claude; do
	if ! command -v "$cmd" >/dev/null 2>&1; then
		echo "'$cmd' is not on PATH. Install it, then run this script again." >&2
		exit 1
	fi
done

if [ "$1" != "--skip-build" ]; then
	echo "Building the MCP server jar..."
	"$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" :mcp-server:jar
fi

if [ ! -f "$JAR" ]; then
	echo "Jar not found: $JAR. Run without --skip-build." >&2
	exit 1
fi

# Remove an old user-scope entry, if there is one.
claude mcp remove --scope user gravel >/dev/null 2>&1 || true

echo "Adding the gravel MCP server in user scope..."
claude mcp add --scope user gravel -e "GRAVEL_PROJECT_DIR=$PROJECT_DIR" -- java -jar "$JAR"

echo
echo "Done. Restart Claude Code to load the gravel server."
echo "Project dir: $PROJECT_DIR"
