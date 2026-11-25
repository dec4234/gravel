package net.dec4234.gravel.mcp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftLauncherTest {
	@TempDir
	Path project;

	@Test
	void logFileIsUnderBuildDir() throws Exception {
		MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(FakeBridge.unusedPort()));
		assertEquals(project.resolve("build/gravel-mcp/launch.log"), launcher.logFile());
	}

	@Test
	void tailLogWithoutLogFile() throws Exception {
		MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(FakeBridge.unusedPort()));
		assertEquals("(no launch log yet)", launcher.tailLog(10));
	}

	@Test
	void tailLogReturnsLastLines() throws Exception {
		MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(FakeBridge.unusedPort()));
		Files.createDirectories(launcher.logFile().getParent());
		Files.writeString(launcher.logFile(), "one\ntwo\nthree\nfour\n");

		assertEquals("three\nfour", launcher.tailLog(2));
		assertEquals("one\ntwo\nthree\nfour", launcher.tailLog(100));
		assertEquals("", launcher.tailLog(0));
	}

	@Test
	void launchDoesNothingWhenBridgeIsAlreadyUp() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(fake.port()));
			String msg = launcher.launch("w", Duration.ofSeconds(1));
			assertTrue(msg.contains("already running"), msg);
			assertFalse(launcher.isProcessAlive());
		}
	}

	@Test
	void stopWhenNothingRuns() throws Exception {
		MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(FakeBridge.unusedPort()));
		assertEquals("Minecraft is not running.", launcher.stop());
	}

	@Test
	void stopAsksGameToShutDown() throws Exception {
		FakeBridge fake = new FakeBridge();
		// After the shutdown action, the fake bridge goes away, like the real game does.
		fake.on("shutdown", params -> {
			new Thread(fake::close).start();
			return FakeBridge.Reply.ok(new JsonObject());
		});
		MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(fake.port()));

		assertEquals("Minecraft stopped.", launcher.stop());
		assertEquals("shutdown", fake.calls.getFirst().action());
	}

	@Test
	void stopIfLaunchedLeavesOtherGamesOpen() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			// The bridge answers, but this launcher did not start the game, so it must not ask it to quit.
			MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(fake.port()));
			launcher.stopIfLaunched();
			assertTrue(fake.calls.isEmpty());
		}
	}

	/** A Gradle wrapper that prints the lines and exits with the code. */
	private static Path fakeProject(Path dir, int exitCode, String... lines) throws Exception {
		Files.createDirectories(dir);
		StringBuilder bat = new StringBuilder("@echo off\r\n");
		StringBuilder sh = new StringBuilder("#!/bin/sh\n");
		for (String line : lines) {
			bat.append("echo ").append(line).append("\r\n");
			sh.append("echo '").append(line).append("'\n");
		}
		bat.append("exit /b ").append(exitCode).append("\r\n");
		sh.append("exit ").append(exitCode).append("\n");
		Files.writeString(dir.resolve("gradlew.bat"), bat);
		Path unix = dir.resolve("gradlew");
		Files.writeString(unix, sh);
		unix.toFile().setExecutable(true);
		return dir;
	}

	@Test
	void groovyListEscapesQuotesAndBackslashes() {
		assertEquals("'a', 'C:\\\\x\\'s'", MinecraftLauncher.groovyList(List.of("a", "C:\\x's")));
	}

	@Test
	void buildTasksOnlyRootInGravel() throws Exception {
		MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(FakeBridge.unusedPort()));
		assertEquals(List.of(":assemble"), launcher.buildTasks(project));
		assertEquals(List.of("assemble"), launcher.buildTasks(project.resolve("other")));
	}

	@Test
	void findGravelJarSkipsSourcesJar() throws Exception {
		MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(FakeBridge.unusedPort()));
		assertNull(launcher.findGravelJar());
		Path libs = Files.createDirectories(project.resolve("build/libs"));
		Files.writeString(libs.resolve("gravel-1.0.0-sources.jar"), "");
		Files.writeString(libs.resolve("gravel-1.0.0.jar"), "");
		assertEquals(libs.resolve("gravel-1.0.0.jar"), launcher.findGravelJar());
	}

	@Test
	void launchRejectsFolderWithoutGradleWrapper() throws Exception {
		MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(FakeBridge.unusedPort()));
		Path empty = Files.createDirectories(project.resolve("empty"));
		var options = new MinecraftLauncher.Options(null, false, empty, false);
		assertThrows(IllegalArgumentException.class, () -> launcher.launch(options, Duration.ofSeconds(5)));
	}

	@Test
	void rebuildStopsWhenBuildFails() throws Exception {
		Path mod = fakeProject(project.resolve("mod"), 1, "Foo.java:3: error: cannot find symbol", "BUILD FAILED");
		MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(FakeBridge.unusedPort()));
		String msg = launcher.launch(new MinecraftLauncher.Options(null, false, mod, true), Duration.ofSeconds(60));
		assertTrue(msg.contains("build failed"), msg);
		assertTrue(msg.contains("Foo.java:3: error: cannot find symbol"), msg);
		assertFalse(launcher.isProcessAlive());
	}

	@Test
	void otherProjectGetsGravelThroughInitScript() throws Exception {
		Files.createDirectories(project.resolve("build/libs"));
		Path jar = project.resolve("build/libs/gravel-1.0.0.jar");
		Files.writeString(jar, "");
		Path mod = fakeProject(project.resolve("mod"), 0, "started");
		MinecraftLauncher launcher = new MinecraftLauncher(project, new BridgeClient(FakeBridge.unusedPort()));

		String msg = launcher.launch(new MinecraftLauncher.Options("My World", false, mod, false), Duration.ofSeconds(60));

		assertTrue(msg.contains("exited (code 0)"), msg);
		assertEquals(mod, launcher.projectDir());
		assertEquals(mod.resolve("run"), launcher.runDir());
		String script = Files.readString(launcher.logFile().getParent().resolve("gravel-init.gradle"));
		assertTrue(script.contains("-Dfabric.addMods=" + jar.toString().replace("\\", "\\\\")), script);
		assertTrue(script.contains("-Dgravel.music=false"), script);
		assertTrue(script.contains("'--quickPlaySingleplayer', 'My World'"), script);
	}
}
