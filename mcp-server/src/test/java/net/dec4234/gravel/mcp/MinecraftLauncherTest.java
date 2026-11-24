package net.dec4234.gravel.mcp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

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
}
