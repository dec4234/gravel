package net.dec4234.gravel.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Reads the Minecraft client logs from disk (run/logs, run/crash-reports and the launch log), so they can be
 * searched also when the game is stopped or crashed.
 */
final class LogScanner {
	/** A Minecraft log line: [11:26:14] [Render thread/WARN] (logger) message. The logger part is optional. */
	private static final Pattern HEADER = Pattern.compile(
			"^\\[(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)] \\[([^\\]]*)/([A-Z]+)](?: \\(([^)]*)\\))?:? ?(.*)$");
	/** A line that names an exception: "java.lang.Foo: msg", "Caused by: a.b.FooException" and so on. */
	private static final Pattern EXCEPTION = Pattern.compile(
			"^\\s*(?:Caused by: |Suppressed: )?((?:[\\w$]+\\.)+[\\w$]*(?:Exception|Error|Throwable))\\b");
	private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");

	private final Path runDir;
	private final Path launchLog;

	LogScanner(Path runDir, Path launchLog) {
		this.runDir = runDir;
		this.launchLog = launchLog;
	}

	Path logsDir() {
		return runDir.resolve("logs");
	}

	Path crashDir() {
		return runDir.resolve("crash-reports");
	}

	/** One log event: a header line and the lines after it that have no header (stack traces, lists). */
	record Entry(int line, String time, String thread, String level, String logger, List<String> lines) {
		String text() {
			return String.join("\n", lines);
		}

		int levelRank() {
			return level == null ? -1 : LEVELS.indexOf(level);
		}
	}

	// ---------------------------------------------------------------- list

	String list() throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("Logs folder: ").append(logsDir()).append('\n');
		appendFiles(sb, logsDir(), p -> p.toString().endsWith(".log") || p.toString().endsWith(".log.gz"));
		sb.append("\nCrash reports: ").append(crashDir()).append('\n');
		appendFiles(sb, crashDir(), p -> p.toString().endsWith(".txt"));
		sb.append("\nLaunch log (Gradle output): ").append(launchLog)
				.append(Files.exists(launchLog) ? " (" + Files.size(launchLog) + " bytes)" : " (none yet)").append('\n');
		sb.append("\nSource names for scan_minecraft_log: latest, debug, launch, crash (newest crash report), "
				+ "or a file name from the lists above.");
		return sb.toString();
	}

	private static void appendFiles(StringBuilder sb, Path dir, java.util.function.Predicate<Path> keep) throws IOException {
		List<Path> files = filesNewestFirst(dir, keep);
		if (files.isEmpty()) {
			sb.append("  (none)\n");
		}
		for (Path p : files) {
			sb.append("  ").append(p.getFileName())
					.append("  ").append(Files.size(p)).append(" bytes")
					.append("  modified ").append(Files.getLastModifiedTime(p).toInstant().toString()).append('\n');
		}
	}

	private static List<Path> filesNewestFirst(Path dir, java.util.function.Predicate<Path> keep) throws IOException {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(Files::isRegularFile).filter(keep)
					.sorted(Comparator.comparing(LogScanner::modified).reversed())
					.toList();
		}
	}

	private static Instant modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toInstant();
		} catch (IOException e) {
			return Instant.EPOCH;
		}
	}

	// ---------------------------------------------------------------- read

	/** Finds the file for a source name. Only names inside the logs and crash folders are allowed. */
	Path resolve(String source) throws IOException {
		String s = source == null || source.isBlank() ? "latest" : source.trim();
		switch (s.toLowerCase(Locale.ROOT)) {
			case "latest" -> {
				return mustExist(logsDir().resolve("latest.log"));
			}
			case "debug" -> {
				return mustExist(logsDir().resolve("debug.log"));
			}
			case "launch" -> {
				return mustExist(launchLog);
			}
			case "crash" -> {
				List<Path> crashes = filesNewestFirst(crashDir(), p -> p.toString().endsWith(".txt"));
				if (crashes.isEmpty()) {
					throw new IllegalArgumentException("No crash reports in " + crashDir());
				}
				return crashes.getFirst();
			}
			default -> {
				if (s.contains("/") || s.contains("\\") || s.contains("..")) {
					throw new IllegalArgumentException("Give a file name only, not a path: " + s);
				}
				for (Path dir : List.of(logsDir(), crashDir())) {
					Path p = dir.resolve(s);
					if (Files.isRegularFile(p)) {
						return p;
					}
				}
				throw new IllegalArgumentException("No log named '" + s + "'. Use list_minecraft_logs to see the files.");
			}
		}
	}

	private static Path mustExist(Path p) {
		if (!Files.isRegularFile(p)) {
			throw new IllegalArgumentException("Log file does not exist (yet): " + p);
		}
		return p;
	}

	/** Reads a log file as text. Opens .gz files. Bad UTF-8 bytes do not stop the read. */
	static List<String> readLines(Path file) throws IOException {
		byte[] bytes;
		if (file.getFileName().toString().endsWith(".gz")) {
			try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
				bytes = in.readAllBytes();
			}
		} else {
			bytes = Files.readAllBytes(file);
		}
		return new String(bytes, StandardCharsets.UTF_8).lines().toList();
	}

	/**
	 * Groups lines into entries. A line with no header belongs to the entry above it. In a file with no
	 * headers (such as a crash report) each line is its own entry.
	 */
	static List<Entry> parse(List<String> lines) {
		List<Entry> entries = new ArrayList<>();
		Entry current = null;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			Matcher m = HEADER.matcher(line);
			if (m.matches()) {
				List<String> body = new ArrayList<>();
				body.add(line);
				current = new Entry(i + 1, m.group(1), m.group(2), m.group(3), m.group(4), body);
				entries.add(current);
			} else if (current != null && current.level() != null) {
				current.lines().add(line);
			} else {
				entries.add(new Entry(i + 1, null, null, null, null, new ArrayList<>(List.of(line))));
			}
		}
		return entries;
	}

	// ---------------------------------------------------------------- scan

	/** The filters for one scan. Null or blank means "no filter". */
	record Query(String pattern, boolean regex, boolean ignoreCase, String minLevel, String thread, String logger,
				 String sinceTime, int sinceLine, boolean exceptionsOnly, int context, int limit, boolean fromStart,
				 int maxLinesPerEntry) {
	}

	String scan(String source, Query q) throws IOException {
		Path file = resolve(source);
		List<Entry> entries = parse(readLines(file));
		Matcher textMatcher = compile(q).map(p -> p.matcher("")).orElse(null);
		int minRank = levelRank(q.minLevel());

		List<Integer> hits = new ArrayList<>();
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			if (e.line() <= q.sinceLine()) {
				continue;
			}
			if (minRank >= 0 && e.levelRank() < minRank) {
				continue;
			}
			if (!containsIgnoreCase(e.thread(), q.thread()) || !containsIgnoreCase(e.logger(), q.logger())) {
				continue;
			}
			if (notBlank(q.sinceTime()) && (e.time() == null || e.time().compareTo(q.sinceTime().trim()) < 0)) {
				continue;
			}
			if (q.exceptionsOnly() && !hasException(e)) {
				continue;
			}
			if (textMatcher != null && !textMatcher.reset(e.text()).find()) {
				continue;
			}
			hits.add(i);
		}

		int limit = Math.max(1, q.limit());
		List<Integer> shown = hits.size() <= limit ? hits
				: q.fromStart() ? hits.subList(0, limit) : hits.subList(hits.size() - limit, hits.size());

		int lastLine = entries.isEmpty() ? 0 : entries.getLast().line() + entries.getLast().lines().size() - 1;
		StringBuilder sb = new StringBuilder();
		sb.append("File: ").append(file).append('\n');
		sb.append("Entries: ").append(entries.size()).append(", matches: ").append(hits.size())
				.append(", shown: ").append(shown.size());
		if (shown.size() < hits.size()) {
			sb.append(q.fromStart() ? " (the first ones; raise limit or use since_line to see more)"
					: " (the newest ones; raise limit or set from_start to see older ones)");
		}
		sb.append(", lastLine: ").append(lastLine).append(" (pass as since_line to get only new entries)\n");

		int context = Math.max(0, q.context());
		int printedUpTo = -1;
		for (int idx : shown) {
			int from = Math.max(Math.max(0, idx - context), printedUpTo + 1);
			int to = Math.min(entries.size() - 1, idx + context);
			if (printedUpTo >= 0 && from > printedUpTo + 1) {
				sb.append("--\n");
			} else if (printedUpTo < 0) {
				sb.append('\n');
			}
			for (int i = from; i <= to; i++) {
				appendEntry(sb, entries.get(i), i == idx ? ">" : " ", q.maxLinesPerEntry());
			}
			printedUpTo = Math.max(printedUpTo, to);
		}
		return sb.toString();
	}

	private static void appendEntry(StringBuilder sb, Entry e, String mark, int maxLines) {
		List<String> lines = e.lines();
		int keep = maxLines <= 0 ? lines.size() : Math.min(lines.size(), maxLines);
		for (int i = 0; i < keep; i++) {
			sb.append(mark).append(String.format("%6d", e.line() + i)).append("  ").append(lines.get(i)).append('\n');
		}
		if (keep < lines.size()) {
			sb.append(mark).append("        ... ").append(lines.size() - keep)
					.append(" more lines (raise max_lines_per_entry to see them)\n");
		}
	}

	// ---------------------------------------------------------------- summary

	/** Counts per level, the most common warnings and errors, and the exceptions in a log. */
	String summary(String source, int top) throws IOException {
		Path file = resolve(source);
		List<Entry> entries = parse(readLines(file));
		Map<String, Integer> levels = new LinkedHashMap<>();
		for (String l : LEVELS) {
			levels.put(l, 0);
		}
		Map<String, Group> problems = new LinkedHashMap<>();
		Map<String, Group> exceptions = new LinkedHashMap<>();
		String firstTime = null;
		String lastTime = null;

		for (Entry e : entries) {
			if (e.level() != null) {
				levels.merge(e.level(), 1, Integer::sum);
			}
			if (e.time() != null) {
				if (firstTime == null) {
					firstTime = e.time();
				}
				lastTime = e.time();
			}
			if (e.levelRank() >= LEVELS.indexOf("WARN")) {
				String key = e.level() + " [" + e.thread() + "] " + (e.logger() == null ? "" : "(" + e.logger() + ") ")
						+ normalize(messageOf(e));
				problems.computeIfAbsent(key, k -> new Group(e.line())).add(e.line());
			}
			for (String line : e.lines()) {
				Matcher m = EXCEPTION.matcher(line);
				if (m.find()) {
					String key = line.strip();
					key = key.length() > 200 ? key.substring(0, 200) + "..." : key;
					exceptions.computeIfAbsent(key, k -> new Group(e.line())).add(e.line());
				}
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append("File: ").append(file).append('\n');
		sb.append("Entries: ").append(entries.size());
		if (firstTime != null) {
			sb.append(", time ").append(firstTime).append(" to ").append(lastTime);
		}
		sb.append('\n');
		sb.append("Levels: ");
		levels.forEach((k, v) -> {
			if (v > 0) {
				sb.append(k).append('=').append(v).append(' ');
			}
		});
		sb.append('\n');
		appendGroups(sb, "Warnings and errors (same text grouped, numbers shown as #)", problems, top);
		appendGroups(sb, "Exception lines", exceptions, top);
		return sb.toString();
	}

	private static final class Group {
		final int firstLine;
		int lastLine;
		int count;

		Group(int firstLine) {
			this.firstLine = firstLine;
		}

		void add(int line) {
			if (lastLine != line) {
				count++;
			}
			lastLine = line;
		}
	}

	private static void appendGroups(StringBuilder sb, String title, Map<String, Group> groups, int top) {
		sb.append('\n').append(title).append(": ").append(groups.size()).append(" different\n");
		if (groups.isEmpty()) {
			sb.append("  (none)\n");
			return;
		}
		groups.entrySet().stream()
				.sorted(Comparator.comparingInt((Map.Entry<String, Group> en) -> en.getValue().count).reversed())
				.limit(Math.max(1, top))
				.forEach(en -> {
					Group g = en.getValue();
					sb.append(String.format("  %4dx  line %d", g.count, g.firstLine));
					if (g.lastLine != g.firstLine) {
						sb.append("..").append(g.lastLine);
					}
					sb.append("  ").append(en.getKey()).append('\n');
				});
		if (groups.size() > top) {
			sb.append("  ... ").append(groups.size() - top).append(" more\n");
		}
	}

	/** The message text of the first line, without the header. */
	static String messageOf(Entry e) {
		String first = e.lines().getFirst();
		Matcher m = HEADER.matcher(first);
		return m.matches() ? m.group(5) : first;
	}

	/** Replaces numbers with # so that messages that differ only in numbers group together. */
	static String normalize(String message) {
		String s = message.replaceAll("\\d+", "#");
		return s.length() > 200 ? s.substring(0, 200) + "..." : s;
	}

	// ---------------------------------------------------------------- helpers

	private static java.util.Optional<Pattern> compile(Query q) {
		if (!notBlank(q.pattern())) {
			return java.util.Optional.empty();
		}
		int flags = q.ignoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
		try {
			return java.util.Optional.of(q.regex()
					? Pattern.compile(q.pattern(), flags | Pattern.MULTILINE)
					: Pattern.compile(Pattern.quote(q.pattern()), flags));
		} catch (PatternSyntaxException e) {
			throw new IllegalArgumentException("Bad regex: " + e.getDescription() + " in " + q.pattern());
		}
	}

	static int levelRank(String level) {
		if (!notBlank(level)) {
			return -1;
		}
		int rank = LEVELS.indexOf(level.trim().toUpperCase(Locale.ROOT));
		if (rank < 0) {
			throw new IllegalArgumentException("Unknown level '" + level + "'. Use one of " + LEVELS);
		}
		return rank;
	}

	private static boolean hasException(Entry e) {
		for (String line : e.lines()) {
			if (EXCEPTION.matcher(line).find() || line.startsWith("\tat ")) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsIgnoreCase(String value, String filter) {
		if (!notBlank(filter)) {
			return true;
		}
		return value != null && value.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}
}
