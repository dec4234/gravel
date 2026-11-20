package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatLogTest {
	@BeforeEach
	void reset() {
		ChatLog.clear();
	}

	private static List<String> texts(JsonObject result) {
		List<String> out = new ArrayList<>();
		result.getAsJsonArray("messages").forEach(e -> out.add(e.getAsJsonObject().get("text").getAsString()));
		return out;
	}

	@Test
	void readReturnsLinesOldestFirstWithLatestId() {
		ChatLog.add("system", null, "a");
		ChatLog.add("chat", "Steve", "b");

		JsonObject result = ChatLog.read(0, 50, false);

		assertEquals(List.of("a", "b"), texts(result));
		assertEquals(ChatLog.latestId(), result.get("latestId").getAsLong());
		JsonArray lines = result.getAsJsonArray("messages");
		assertFalse(lines.get(0).getAsJsonObject().has("sender"));
		assertEquals("Steve", lines.get(1).getAsJsonObject().get("sender").getAsString());
		assertEquals("chat", lines.get(1).getAsJsonObject().get("type").getAsString());
		assertTrue(lines.get(0).getAsJsonObject().get("id").getAsLong()
				< lines.get(1).getAsJsonObject().get("id").getAsLong());
	}

	@Test
	void readOnlyReturnsLinesAfterSinceId() {
		ChatLog.add("system", null, "old");
		long seen = ChatLog.latestId();
		ChatLog.add("system", null, "new");

		assertEquals(List.of("new"), texts(ChatLog.read(seen, 50, false)));
		assertEquals(List.of(), texts(ChatLog.read(ChatLog.latestId(), 50, false)));
	}

	@Test
	void limitKeepsTheNewestLines() {
		for (int i = 1; i <= 5; i++) {
			ChatLog.add("system", null, "m" + i);
		}
		assertEquals(List.of("m4", "m5"), texts(ChatLog.read(0, 2, false)));
	}

	@Test
	void actionBarIsHiddenUnlessAsked() {
		ChatLog.add("system", null, "sys");
		ChatLog.add("actionbar", null, "bar");

		assertEquals(List.of("sys"), texts(ChatLog.read(0, 50, false)));
		assertEquals(List.of("sys", "bar"), texts(ChatLog.read(0, 50, true)));
	}

	@Test
	void actionBarLinesDoNotUseUpTheLimit() {
		ChatLog.add("system", null, "sys");
		ChatLog.add("actionbar", null, "bar1");
		ChatLog.add("actionbar", null, "bar2");

		assertEquals(List.of("sys"), texts(ChatLog.read(0, 1, false)));
	}

	@Test
	void keepsAtMostOneThousandLines() {
		for (int i = 1; i <= 1005; i++) {
			ChatLog.add("system", null, "m" + i);
		}
		List<String> all = texts(ChatLog.read(0, 5000, false));
		assertEquals(1000, all.size());
		assertEquals("m6", all.getFirst());
		assertEquals("m1005", all.getLast());
	}
}
