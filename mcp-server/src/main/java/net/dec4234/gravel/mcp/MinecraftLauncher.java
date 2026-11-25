package net.dec4234.gravel.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Starts and stops the dev Minecraft client through `gradlew runClient`. The client can run in the Gravel project, or
 * in the project of a different mod: then Gravel is added to that game with -Dfabric.addMods, through a Gradle init
 * script, so the mod project needs no changes.
 */
final class MinecraftLauncher {
	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

	/** What to launch. Null and false fields use the defaults. */
	record Options(String world, boolean music, Path projectDir, boolean rebuild) {
	}

	/** The singleplayer world or the server to open on start. Both null: the title screen. */
	record Target(String world, String server) {
		static final Target NONE = new Target(null, null);

		boolean isNone() {
			return world == null && server == null;
		}

		String describe() {
			return world != null ? "world '" + world + "'" : "server '" + server + "'";
		}
	}

	private final Path gravelDir;
	private final Path logFile;
	private final BridgeClient bridge;
	private Process process;
	private Process buildProcess;
	/** The project of the last launch. Used when a launch gives no project_dir. */
	private Path activeProject;
	/** The last world or server the game was in, so that rebuild can join it again. */
	private Target lastTarget = Target.NONE;

	MinecraftLauncher(Path gravelDir, BridgeClient bridge) {
		this.gravelDir = gravelDir.toAbsolutePath().normalize();
		this.activeProject = this.gravelDir;
		this.bridge = bridge;
		this.logFile = this.gravelDir.resolve("build").resolve("gravel-mcp").resolve("launch.log");
	}

	Path logFile() {
		return logFile;
	}

	/** The project that runs (or last ran) the game. */
	synchronized Path projectDir() {
		return activeProject;
	}

	/** The game folder (logs, saves, crash reports) of the active project. */
	Path runDir() {
		return projectDir().resolve("run");
	}

	synchronized boolean isProcessAlive() {
		return process != null && process.isAlive();
	}

	synchronized void rememberWorld(String world) {
		lastTarget = new Target(world, null);
	}

	synchronized void rememberServer(String address) {
		lastTarget = new Target(null, address);
	}

	/**
	 * Starts the game and blocks until the mod bridge answers, the process exits, or the timeout ends.
	 * @return a message for the user
	 */
	String launch(String world, Duration timeout) throws IOException, InterruptedException {
		return launch(new Options(world, false, null, false), timeout);
	}

	/** Like {@link #launch(String, Duration)}. With music false, the game plays no music (the Gravel default). */
	String launch(String world, boolean music, Duration timeout) throws IOException, InterruptedException {
		return launch(new Options(world, music, null, false), timeout);
	}

	/**
	 * Starts the game in the given project (default: the project of the last launch, else Gravel). With rebuild, it
	 * first stops the game, builds the project, and then joins the last world or server again (unless world is given).
	 */
	String launch(Options options, Duration timeout) throws IOException, InterruptedException {
		Instant deadline = Instant.now().plus(timeout);
		Path project = options.projectDir() == null ? projectDir() : options.projectDir().toAbsolutePath().normalize();
		if (!options.rebuild() && bridge.isUp()) {
			return alreadyRunning(project);
		}
		checkProject(project);
		Target target = isBlank(options.world()) ? Target.NONE : new Target(options.world().trim(), null);
		StringBuilder msg = new StringBuilder();
		boolean appendLog = false;

		if (options.rebuild()) {
			if (target.isNone()) {
				target = currentTarget();
			}
			if (bridge.isUp() || isProcessAlive()) {
				msg.append(stop()).append('\n');
			}
			Files.createDirectories(logFile.getParent());
			int code = runGradle(project, buildTasks(project), deadline);
			if (code != 0) {
				return msg + (code == TIMED_OUT ? "The build did not end before the timeout and was stopped."
						: "The build failed (code " + code + "), so the game was not started.") + "\n" + buildErrors();
			}
			msg.append("Built ").append(project).append(".\n");
			appendLog = true;
		}

		if (bridge.isUp()) {
			return msg + alreadyRunning(project);
		}
		List<String> args = new ArrayList<>();
		args.add("runClient");
		if (isGravel(project)) {
			args.add("-Pmusic=" + options.music());
			if (target.world() != null) {
				args.add("-PquickPlayWorld=" + target.world());
			} else if (target.server() != null) {
				args.add("-PquickPlayServer=" + target.server());
			}
		} else if (!isProcessAlive()) {
			// Outside the lock: this can build the Gravel jar first.
			args.add("--init-script");
			args.add(writeInitScript(project, options.music(), target, deadline).toString());
		}

		Process p;
		synchronized (this) {
			if (bridge.isUp()) {
				return msg + alreadyRunning(project);
			}
			if (isProcessAlive()) {
				p = process;
			} else {
				Files.createDirectories(logFile.getParent());
				p = startGradle(project, args, appendLog);
				process = p;
				activeProject = project;
				if (!target.isNone()) {
					lastTarget = target;
				}
			}
		}

		String opening = target.isNone() ? "" : " Opening " + target.describe() + ".";
		while (Instant.now().isBefore(deadline)) {
			if (bridge.isUp()) {
				return msg + "Minecraft started from " + project + " and the Gravel bridge is ready." + opening;
			}
			if (!p.isAlive()) {
				return msg + "Minecraft process exited (code " + p.exitValue() + ") before the bridge came up.\n"
						+ "Last log lines:\n" + tailLog(40);
			}
			Thread.sleep(2000);
		}
		return msg + "Timed out after " + timeout.toSeconds() + "s waiting for the bridge. The game may still be starting"
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
			kill(p);
			return "Minecraft did not stop in 30s, so the process was killed.";
		}
		return "The launcher process stopped, but the bridge still answers. Minecraft may have been started outside this MCP server.";
	}

	/**
	 * Stops the game if this server started it and it still runs. A game started some other way (for example from
	 * the IDE) stays open. Called when the MCP session ends.
	 */
	void stopIfLaunched() {
		synchronized (this) {
			if (buildProcess != null && buildProcess.isAlive()) {
				kill(buildProcess);
			}
			if (process == null || !process.isAlive()) {
				return;
			}
		}
		try {
			System.err.println("[gravel-mcp] session ended, stopping Minecraft: " + stop());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	String tailLog(int lines) {
		List<String> all = readLog();
		if (all == null) {
			return Files.exists(logFile) ? "(could not read log)" : "(no launch log yet)";
		}
		return String.join("\n", all.subList(Math.max(0, all.size() - Math.max(0, lines)), all.size()));
	}

	// ---------------------------------------------------------------- helpers

	private static final int TIMED_OUT = -1;

	private synchronized String alreadyRunning(Path project) {
		if (isProcessAlive() && !project.equals(activeProject)) {
			return "Minecraft is already running from " + activeProject + ". Call stop_minecraft first, or use rebuild.";
		}
		return "Minecraft is already running and the Gravel bridge is reachable.";
	}

	boolean isGravel(Path project) {
		return project.toAbsolutePath().normalize().equals(gravelDir);
	}

	private static void checkProject(Path project) {
		if (!Files.isDirectory(project)) {
			throw new IllegalArgumentException("project_dir is not a folder: " + project);
		}
		if (!Files.exists(project.resolve(WINDOWS ? "gradlew.bat" : "gradlew"))) {
			throw new IllegalArgumentException("project_dir has no Gradle wrapper (gradlew): " + project);
		}
	}

	/** The tasks that build a project. In Gravel only the root project: the MCP server jar is in use. */
	List<String> buildTasks(Path project) {
		return List.of(isGravel(project) ? ":assemble" : "assemble");
	}

	/** The world or server the game is in now, else the last one this server saw. */
	private Target currentTarget() throws InterruptedException {
		if (bridge.isUp()) {
			try {
				JsonObject status = bridge.action("status", null);
				String world = text(status.get("world"));
				JsonElement conn = status.get("serverConnection");
				String server = conn != null && conn.isJsonObject() ? text(conn.getAsJsonObject().get("server")) : null;
				if (world != null) {
					rememberWorld(world);
				} else if (server != null) {
					rememberServer(server);
				}
			} catch (IOException e) {
				// use the remembered target
			}
		}
		synchronized (this) {
			return lastTarget;
		}
	}

	/**
	 * Writes the Gradle init script that adds Gravel (and Baritone, if Gravel has it in run/mods) to the runClient
	 * task of a different mod project, and sets the music and quick play options.
	 */
	private Path writeInitScript(Path project, boolean music, Target target, Instant deadline)
			throws IOException, InterruptedException {
		List<Path> mods = new ArrayList<>();
		mods.add(gravelJar(deadline));
		Path gravelMods = gravelDir.resolve("run").resolve("mods");
		if (Files.isDirectory(gravelMods)) {
			try (Stream<Path> files = Files.list(gravelMods)) {
				files.filter(f -> {
					String n = f.getFileName().toString().toLowerCase();
					return n.startsWith("baritone") && n.endsWith(".jar");
				}).sorted().forEach(mods::add);
			}
		}
		String addMods = String.join(File.pathSeparator, mods.stream().map(Path::toString).toList());

		List<String> jvmArgs = List.of("-Dfabric.addMods=" + addMods, "-Dgravel.music=" + music);
		List<String> gameArgs = target.world() != null ? List.of("--quickPlaySingleplayer", target.world())
				: target.server() != null ? List.of("--quickPlayMultiplayer", target.server()) : List.of();

		StringBuilder s = new StringBuilder()
				.append("// Written by the Gravel MCP server for ").append(project.getFileName()).append(".\n")
				.append("// Adds Gravel to runClient. The mod project itself does not change.\n")
				.append("allprojects {\n")
				.append("\ttasks.matching { it.name == 'runClient' }.configureEach {\n")
				.append("\t\tjvmArgs(").append(groovyList(jvmArgs)).append(")\n");
		if (!gameArgs.isEmpty()) {
			s.append("\t\targs(").append(groovyList(gameArgs)).append(")\n");
		}
		s.append("\t}\n}\n");

		Path script = logFile.getParent().resolve("gravel-init.gradle");
		Files.createDirectories(script.getParent());
		Files.writeString(script, s.toString());
		return script;
	}

	static String groovyList(List<String> values) {
		return String.join(", ", values.stream()
				.map(v -> "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'")
				.toList());
	}

	/** The Gravel mod jar. Builds it first if it is not there. */
	private Path gravelJar(Instant deadline) throws IOException, InterruptedException {
		Path jar = findGravelJar();
		if (jar == null) {
			int code = runGradle(gravelDir, List.of(":jar"), deadline);
			jar = findGravelJar();
			if (code != 0 || jar == null) {
				throw new IOException("Could not build the Gravel jar in " + gravelDir + " (code " + code + ").\n"
						+ tailLog(30));
			}
		}
		return jar;
	}

	Path findGravelJar() throws IOException {
		Path libs = gravelDir.resolve("build").resolve("libs");
		if (!Files.isDirectory(libs)) {
			return null;
		}
		try (Stream<Path> files = Files.list(libs)) {
			return files.filter(f -> {
				String n = f.getFileName().toString();
				return n.startsWith("gravel") && n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-dev.jar");
			}).max(Comparator.comparing(f -> f.toFile().lastModified())).orElse(null);
		}
	}

	private List<String> gradleCommand(Path project, List<String> args) {
		List<String> cmd = new ArrayList<>();
		if (WINDOWS) {
			cmd.add("cmd.exe");
			cmd.add("/c");
			cmd.add(project.resolve("gradlew.bat").toString());
		} else {
			cmd.add(project.resolve("gradlew").toString());
		}
		cmd.addAll(args);
		cmd.add("--console=plain");
		return cmd;
	}

	private Process startGradle(Path project, List<String> args, boolean appendLog) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(gradleCommand(project, args))
				.directory(project.toFile())
				.redirectErrorStream(true)
				// Never inherit stdout: it is the MCP protocol channel.
				.redirectOutput(appendLog ? ProcessBuilder.Redirect.appendTo(logFile.toFile())
						: ProcessBuilder.Redirect.to(logFile.toFile()));
		Process p = pb.start();
		p.getOutputStream().close();
		return p;
	}

	/** Runs a Gradle build to the end. Its output goes to the launch log. @return the exit code, or TIMED_OUT */
	private int runGradle(Path project, List<String> args, Instant deadline)
			throws IOException, InterruptedException {
		Process p = startGradle(project, args, false);
		synchronized (this) {
			buildProcess = p;
		}
		long millis = Math.max(1000, Duration.between(Instant.now(), deadline).toMillis());
		if (!p.waitFor(millis, TimeUnit.MILLISECONDS)) {
			kill(p);
			return TIMED_OUT;
		}
		return p.exitValue();
	}

	/** The compiler errors and the end of the build output. */
	private String buildErrors() {
		List<String> all = readLog();
		if (all == null) {
			return tailLog(40);
		}
		List<String> errors = all.stream().filter(l -> l.contains("error:")).limit(40).toList();
		String tail = String.join("\n", all.subList(Math.max(0, all.size() - 25), all.size()));
		return errors.isEmpty() ? "Last log lines:\n" + tail
				: "Errors:\n" + String.join("\n", errors) + "\n\nLast log lines:\n" + tail;
	}

	private List<String> readLog() {
		try {
			if (!Files.exists(logFile)) {
				return null;
			}
			// Decode leniently: Gradle output on Windows is not always valid UTF-8.
			return new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8).lines().toList();
		} catch (IOException e) {
			return null;
		}
	}

	private static void kill(Process p) {
		p.descendants().forEach(ProcessHandle::destroyForcibly);
		p.destroyForcibly();
	}

	private static String text(JsonElement e) {
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
