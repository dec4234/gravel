package net.dec4234.gravel.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Starts and stops the dev Minecraft client through `gradlew runClient`. */
final class MinecraftLauncher {
	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

	private final Path projectDir;
	private final Path logFile;
	private final BridgeClient bridge;
	private Process process;

	MinecraftLauncher(Path projectDir, BridgeClient bridge) {
		this.projectDir = projectDir;
		this.bridge = bridge;
		this.logFile = projectDir.resolve("build").resolve("gravel-mcp").resolve("launch.log");
	}

	Path logFile() {
		return logFile;
	}

	synchronized boolean isProcessAlive() {
		return process != null && process.isAlive();
	}

	/**
	 * Starts the game and blocks until the mod bridge answers, the process exits, or the timeout ends.
	 * @return a message for the user
	 */
	String launch(String world, Duration timeout) throws IOException, InterruptedException {
		Process p;
		synchronized (this) {
			if (bridge.isUp()) {
				return "Minecraft is already running and the Gravel bridge is reachable.";
			}
			if (isProcessAlive()) {
				p = process;
			} else {
				List<String> cmd = new ArrayList<>();
				if (WINDOWS) {
					cmd.add("cmd.exe");
					cmd.add("/c");
					cmd.add(projectDir.resolve("gradlew.bat").toString());
				} else {
					cmd.add(projectDir.resolve("gradlew").toString());
				}
				cmd.add("runClient");
				cmd.add("--console=plain");
				if (world != null && !world.isBlank()) {
					cmd.add("-PquickPlayWorld=" + world);
				}

				Files.createDirectories(logFile.getParent());
				ProcessBuilder pb = new ProcessBuilder(cmd)
						.directory(projectDir.toFile())
						.redirectErrorStream(true)
						// Never inherit stdout: it is the MCP protocol channel.
						.redirectOutput(logFile.toFile());
				p = pb.start();
				p.getOutputStream().close();
				process = p;
			}
		}

		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			if (bridge.isUp()) {
				return "Minecraft started and the Gravel bridge is ready."
						+ (world != null && !world.isBlank() ? " Opening world '" + world + "'." : "");
			}
			if (!p.isAlive()) {
				return "Minecraft process exited (code " + p.exitValue() + ") before the bridge came up.\n"
						+ "Last log lines:\n" + tailLog(40);
			}
			Thread.sleep(2000);
		}
		return "Timed out after " + timeout.toSeconds() + "s waiting for the bridge. The game may still be starting"
				+ " (the first run downloads assets). Call minecraft_status to check again.\nLast log lines:\n" + tailLog(20);
	}

	/** Asks the game to quit, then kills the process tree if it does not stop. */
	String stop() throws InterruptedException {
		boolean asked = false;
		if (bridge.isUp()) {
			try {
				bridge.action("shutdown", null);
				asked = true;
			} catch (IOException e) {
				// fall through to the forced stop
			}
		}

		Process p;
		synchronized (this) {
			p = process;
		}

		Instant deadline = Instant.now().plusSeconds(30);
		while (Instant.now().isBefore(deadline)) {
			boolean processDone = p == null || !p.isAlive();
			if (processDone && !bridge.isUp()) {
				return asked ? "Minecraft stopped." : "Minecraft is not running.";
			}
			Thread.sleep(1000);
		}

		if (p != null && p.isAlive()) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
			return "Minecraft did not stop in 30s, so the process was killed.";
		}
		return "The launcher process stopped, but the bridge still answers. Minecraft may have been started outside this MCP server.";
	}

	String tailLog(int lines) {
		try {
			if (!Files.exists(logFile)) {
				return "(no launch log yet)";
			}
			// Decode leniently: Gradle output on Windows is not always valid UTF-8.
			List<String> all = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8).lines().toList();
			return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
		} catch (IOException e) {
			return "(could not read log: " + e.getMessage() + ")";
		}
	}
}
