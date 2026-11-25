package net.dec4234.gravel.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LogScannerTest {
	private static final String LOG = String.join("\n",
			"[10:00:00] [main/INFO] (FabricLoader) Loading 2 mods:",
			"\t- fabric-api 1.0",
			"\t- gravel 1.0",
			"[10:00:05] [Render thread/WARN] (Minecraft) Missing texture 12",
			"[10:00:06] [Render thread/WARN] (Minecraft) Missing texture 13",
			"[10:00:07] [Worker-Main-1/ERROR] (Minecraft) Failed to fetch",
			"java.lang.IllegalStateException: boom",
			"\tat a.b.C.run(C.java:1)",
			"\tat a.b.C.main(C.java:2)",
			"Caused by: java.io.IOException: disk",
			"\tat a.b.D.read(D.java:3)",
			"[10:00:08] [Render thread/INFO] (gravel) Bridge listening on 25599",
			"[10:00:09] [Server thread/INFO] (Minecraft) Saving chunks",
			"");

	@TempDir
	Path run;
	LogScanner scanner;

	@BeforeEach
	void setUp() throws Exception {
		Files.createDirectories(run.resolve("logs"));
		Files.writeString(run.resolve("logs/latest.log"), LOG);
		scanner = new LogScanner(run, run.resolve("launch.log"));
	}

	private static LogScanner.Query query() {
		return new LogScanner.Query(null, false, true, null, null, null, null, 0, false, 0, 50, false, 30);
	}

	private static LogScanner.Query withPattern(String p, boolean regex) {
		return new LogScanner.Query(p, regex, true, null, null, null, null, 0, false, 0, 50, false, 30);
	}

	@Test
	void parseGroupsContinuationLines() {
		List<LogScanner.Entry> entries = LogScanner.parse(LOG.lines().toList());
		assertEquals(6, entries.size());
		LogScanner.Entry first = entries.getFirst();
		assertEquals(1, first.line());
		assertEquals("main", first.thread());
		assertEquals("INFO", first.level());
		assertEquals("FabricLoader", first.logger());
		assertEquals(3, first.lines().size());
		LogScanner.Entry error = entries.get(3);
		assertEquals(6, error.line());
		assertEquals("Worker-Main-1", error.thread());
		assertEquals(6, error.lines().size());
	}

	@Test
	void parseFileWithoutHeadersGivesOneEntryPerLine() {
		List<LogScanner.Entry> entries = LogScanner.parse(List.of("---- Minecraft Crash Report ----", "", "Description: x"));
		assertEquals(3, entries.size());
		assertNull(entries.get(2).level());
		assertEquals(3, entries.get(2).line());
	}

	@Test
	void scanWithoutFiltersShowsAll() throws Exception {
		String out = scanner.scan(null, query());
		assertTrue(out.contains("Entries: 6, matches: 6, shown: 6"), out);
		assertTrue(out.contains("lastLine: 13"), out);
		assertTrue(out.contains(">     8  \tat a.b.C.run(C.java:1)"), out);
	}

	@Test
	void mixinProblemsFindsApplyFailuresAndMissingTargets() throws Exception {
		Files.writeString(run.resolve("logs/latest.log"), String.join("\n",
				"[10:00:00] [main/INFO] (mixin) Mixin apply for mod gravel was fine in this INFO line",
				"[10:00:01] [main/WARN] (mixin) @Mixin target net.minecraft.Foo was not found gravel.mixins.json:FooMixin",
				"[10:00:02] [Render thread/ERROR] (Minecraft) Unreported exception thrown!",
				"org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error",
				"\tat a.b.C.run(C.java:1)",
				"Caused by: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: bad",
				"[10:00:03] [Render thread/ERROR] (Minecraft) Other error",
				""));
		var o = scanner.mixinProblems("latest");
		assertEquals(2, o.get("count").getAsInt(), o.toString());
		var first = o.getAsJsonArray("problems").get(0).getAsJsonObject();
		assertEquals(2, first.get("line").getAsInt());
		assertEquals("WARN", first.get("level").getAsString());
		var second = o.getAsJsonArray("problems").get(1).getAsJsonObject();
		assertEquals(3, second.getAsJsonArray("text").size(), second.toString());
	}

	@Test
	void mixinProblemsWithoutLogGivesError() throws Exception {
		Files.delete(run.resolve("logs/latest.log"));
		assertTrue(scanner.mixinProblems("latest").has("error"));
	}

	@Test
	void patternSearchesStackTraces() throws Exception {
		String out = scanner.scan("latest", withPattern("IOEXCEPTION", false));
		assertTrue(out.contains("matches: 1"), out);
		assertTrue(out.contains("Failed to fetch"), out);
		assertFalse(out.contains("Missing texture"), out);
	}

	@Test
	void plainPatternIsNotRegex() throws Exception {
		assertTrue(scanner.scan(null, withPattern("texture 1.", false)).contains("matches: 0"));
		assertTrue(scanner.scan(null, withPattern("texture 1.", true)).contains("matches: 2"));
	}

	@Test
	void badRegexIsClearError() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> scanner.scan(null, withPattern("([", true)));
		assertTrue(e.getMessage().startsWith("Bad regex"), e.getMessage());
	}

	@Test
	void filtersByLevelThreadLoggerAndTime() throws Exception {
		var warn = new LogScanner.Query(null, false, true, "warn", null, null, null, 0, false, 0, 50, false, 30);
		assertTrue(scanner.scan(null, warn).contains("matches: 3"));

		var render = new LogScanner.Query(null, false, true, null, "render", null, null, 0, false, 0, 50, false, 30);
		assertTrue(scanner.scan(null, render).contains("matches: 3"));

		var gravel = new LogScanner.Query(null, false, true, null, null, "GRAVEL", null, 0, false, 0, 50, false, 30);
		String out = scanner.scan(null, gravel);
		assertTrue(out.contains("matches: 1") && out.contains("Bridge listening"), out);

		var late = new LogScanner.Query(null, false, true, null, null, null, "10:00:07", 0, false, 0, 50, false, 30);
		assertTrue(scanner.scan(null, late).contains("matches: 3"));

		var bad = new LogScanner.Query(null, false, true, "loud", null, null, null, 0, false, 0, 50, false, 30);
		assertThrows(IllegalArgumentException.class, () -> scanner.scan(null, bad));
	}

	@Test
	void sinceLineGivesOnlyNewEntries() throws Exception {
		var q = new LogScanner.Query(null, false, true, null, null, null, null, 11, false, 0, 50, false, 30);
		String out = scanner.scan(null, q);
		assertTrue(out.contains("matches: 2"), out);
		assertTrue(out.contains("Bridge listening") && out.contains("Saving chunks"), out);
	}

	@Test
	void exceptionsOnly() throws Exception {
		var q = new LogScanner.Query(null, false, true, null, null, null, null, 0, true, 0, 50, false, 30);
		String out = scanner.scan(null, q);
		assertTrue(out.contains("matches: 1") && out.contains("Failed to fetch"), out);
	}

	@Test
	void limitKeepsNewestOrFirst() throws Exception {
		var newest = new LogScanner.Query(null, false, true, null, null, null, null, 0, false, 0, 1, false, 30);
		String out = scanner.scan(null, newest);
		assertTrue(out.contains("shown: 1 (the newest ones") && out.contains("Saving chunks"), out);

		var first = new LogScanner.Query(null, false, true, null, null, null, null, 0, false, 0, 1, true, 30);
		out = scanner.scan(null, first);
		assertTrue(out.contains("shown: 1 (the first ones") && out.contains("Loading 2 mods"), out);
	}

	@Test
	void contextAndCutLines() throws Exception {
		var q = new LogScanner.Query("Bridge", false, true, null, null, null, null, 0, false, 1, 50, false, 2);
		String out = scanner.scan(null, q);
		assertTrue(out.contains(">    12  [10:00:08]"), out);
		assertTrue(out.contains("      6  [10:00:07]"), out);
		assertTrue(out.contains("     13  [10:00:09]"), out);
		assertTrue(out.contains("... 4 more lines"), out);
	}

	@Test
	void summaryGroupsWarningsAndExceptions() throws Exception {
		String out = scanner.summary(null, 20);
		assertTrue(out.contains("time 10:00:00 to 10:00:09"), out);
		assertTrue(out.contains("INFO=3 WARN=2 ERROR=1"), out);
		assertTrue(out.contains("2x  line 4..5  WARN [Render thread] (Minecraft) Missing texture #"), out);
		assertTrue(out.contains("java.lang.IllegalStateException: boom"), out);
		assertTrue(out.contains("Caused by: java.io.IOException: disk"), out);
	}

	@Test
	void readsGzipLogsAndNewestCrashReport() throws Exception {
		try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(run.resolve("logs/2026-11-20-1.log.gz")))) {
			o.write("[09:00:00] [main/ERROR] (x) old failure\n".getBytes(StandardCharsets.UTF_8));
		}
		assertTrue(scanner.scan("2026-11-20-1.log.gz", query()).contains("old failure"));

		Files.createDirectories(run.resolve("crash-reports"));
		Path older = Files.writeString(run.resolve("crash-reports/crash-a.txt"), "Description: older\n");
		Files.writeString(run.resolve("crash-reports/crash-b.txt"), "Description: newer\n");
		Files.setLastModifiedTime(older, FileTime.fromMillis(0));
		assertTrue(scanner.scan("crash", query()).contains("newer"));
		assertTrue(scanner.scan("crash-a.txt", query()).contains("older"));

		String list = scanner.list();
		assertTrue(list.contains("latest.log") && list.contains("2026-11-20-1.log.gz") && list.contains("crash-b.txt"), list);
	}

	@Test
	void rejectsPathsAndMissingFiles() {
		assertThrows(IllegalArgumentException.class, () -> scanner.resolve("../secret.txt"));
		assertThrows(IllegalArgumentException.class, () -> scanner.resolve("sub/latest.log"));
		assertThrows(IllegalArgumentException.class, () -> scanner.resolve("nope.log"));
		assertThrows(IllegalArgumentException.class, () -> scanner.resolve("launch"));
		assertThrows(IllegalArgumentException.class, () -> scanner.resolve("crash"));
	}
}
