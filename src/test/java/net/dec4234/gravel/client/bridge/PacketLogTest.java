package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacketLogTest {
	@BeforeEach
	void reset() {
		PacketLog.clear();
	}

	private static List<String> channels(JsonObject result) {
		List<String> out = new ArrayList<>();
		result.getAsJsonArray("channels").forEach(e -> {
			JsonObject o = e.getAsJsonObject();
			out.add(o.get("direction").getAsString() + " " + o.get("channel").getAsString());
		});
		return out;
	}

	private static JsonObject channel(JsonObject result, String direction, String name) {
		for (var e : result.getAsJsonArray("channels")) {
			JsonObject o = e.getAsJsonObject();
			if (o.get("direction").getAsString().equals(direction) && o.get("channel").getAsString().equals(name)) {
				return o;
			}
		}
		return fail("no channel " + direction + " " + name);
	}

	@Test
	void readGroupsByChannelWithCountsAndSizes() {
		PacketLog.add("in", "mymod:sync", true, "play", 10);
		PacketLog.add("in", "mymod:sync", true, "play", 30);
		PacketLog.add("out", "mymod:sync", true, "play", 5);
		PacketLog.add("in", "minecraft:set_time", false, "play", 12);

		JsonObject result = PacketLog.read(0, PacketLog.Filter.ALL, 50, 20);

		JsonObject sync = channel(result, "in", "mymod:sync");
		assertEquals(2, sync.get("count").getAsInt());
		assertEquals(40, sync.get("totalBytes").getAsInt());
		assertEquals(20, sync.get("avgBytes").getAsInt());
		assertEquals(10, sync.get("minBytes").getAsInt());
		assertEquals(30, sync.get("maxBytes").getAsInt());
		assertEquals(30, sync.get("lastBytes").getAsInt());
		assertEquals(4, result.get("totalPackets").getAsInt());
		assertEquals(57, result.get("totalBytes").getAsInt());
		assertEquals(3, result.get("channelCount").getAsInt());
		assertTrue(result.get("complete").getAsBoolean());
		assertEquals(4, result.getAsJsonArray("recent").size());
	}

	@Test
	void customChannelsComeFirstThenMostPackets() {
		for (int i = 0; i < 5; i++) {
			PacketLog.add("in", "minecraft:move_entity_pos", false, "play", 9);
		}
		PacketLog.add("in", "minecraft:set_time", false, "play", 12);
		PacketLog.add("in", "mymod:a", true, "play", 1);
		PacketLog.add("in", "mymod:b", true, "play", 1);
		PacketLog.add("in", "mymod:b", true, "play", 1);

		assertEquals(List.of("in mymod:b", "in mymod:a", "in minecraft:move_entity_pos", "in minecraft:set_time"),
				channels(PacketLog.read(0, PacketLog.Filter.ALL, 50, 0)));
		JsonObject limited = PacketLog.read(0, PacketLog.Filter.ALL, 1, 0);
		assertEquals(List.of("in mymod:b"), channels(limited));
		assertEquals(4, limited.get("channelCount").getAsInt());
	}

	@Test
	void unknownSizesAreCountedButNotInTheBytes() {
		PacketLog.add("in", "minecraft:set_time", false, "play", -1);
		PacketLog.add("in", "minecraft:set_time", false, "play", 8);

		JsonObject time = channel(PacketLog.read(0, PacketLog.Filter.ALL, 50, 20), "in", "minecraft:set_time");
		assertEquals(2, time.get("count").getAsInt());
		assertEquals(1, time.get("sizedCount").getAsInt());
		assertEquals(8, time.get("totalBytes").getAsInt());

		PacketLog.add("in", "minecraft:keep_alive", false, "play", -1);
		JsonObject keepAlive = channel(PacketLog.read(0, PacketLog.Filter.ALL, 50, 20), "in", "minecraft:keep_alive");
		assertFalse(keepAlive.has("totalBytes"));
		JsonArray recent = PacketLog.read(0, PacketLog.Filter.ALL, 50, 1).getAsJsonArray("recent");
		assertFalse(recent.get(0).getAsJsonObject().has("bytes"));
	}

	@Test
	void filterByChannelDirectionAndCustom() {
		PacketLog.add("in", "mymod:sync", true, "play", 1);
		PacketLog.add("out", "mymod:click", true, "play", 1);
		PacketLog.add("in", "minecraft:set_time", false, "play", 1);

		assertEquals(List.of("in mymod:sync"), channels(PacketLog.read(0,
				new PacketLog.Filter(WaitActions.textMatcher("channel", "MYMOD:", false), "in", false), 50, 20)));
		assertEquals(List.of("out mymod:click", "in mymod:sync"), channels(PacketLog.read(0,
				new PacketLog.Filter(null, null, true), 50, 20)));
		assertEquals(List.of("in minecraft:set_time"), channels(PacketLog.read(0,
				new PacketLog.Filter(WaitActions.textMatcher("channel", "^minecraft:", true), null, false), 50, 20)));
	}

	@Test
	void sinceIdCountsOnlyNewerPackets() {
		PacketLog.add("in", "mymod:sync", true, "play", 1);
		long seen = PacketLog.latestId();
		PacketLog.add("in", "mymod:sync", true, "play", 2);

		JsonObject result = PacketLog.read(seen, PacketLog.Filter.ALL, 50, 20);
		assertEquals(1, channel(result, "in", "mymod:sync").get("count").getAsInt());
		assertEquals(1, result.getAsJsonArray("recent").size());
		assertTrue(result.get("complete").getAsBoolean());
	}

	@Test
	void sinceIdIsNotCompleteWhenPacketsWereDropped() {
		PacketLog.add("in", "mymod:first", true, "play", 1);
		long seen = PacketLog.latestId();
		for (int i = 0; i <= PacketLog.CAPACITY; i++) {
			PacketLog.add("in", "minecraft:set_time", false, "play", 1);
		}
		assertFalse(PacketLog.read(seen, PacketLog.Filter.ALL, 50, 0).get("complete").getAsBoolean());
		// The totals keep counting after the oldest packets are dropped.
		assertEquals(PacketLog.CAPACITY + 1, channel(PacketLog.read(0, PacketLog.Filter.ALL, 50, 0),
				"in", "minecraft:set_time").get("count").getAsInt());
	}

	@Test
	void clearRemovesTotalsButIdsKeepCounting() {
		PacketLog.add("in", "mymod:sync", true, "play", 1);
		long before = PacketLog.latestId();
		PacketLog.clear();
		assertEquals(0, PacketLog.read(0, PacketLog.Filter.ALL, 50, 20).get("channelCount").getAsInt());
		assertEquals(before, PacketLog.latestId());
		PacketLog.add("in", "mymod:sync", true, "play", 1);
		assertEquals(before + 1, PacketLog.latestId());
	}

	@Test
	void findReturnsOldestMatchAfterId() {
		PacketLog.add("in", "mymod:sync", true, "play", 1);
		long after = PacketLog.latestId();
		PacketLog.add("out", "mymod:sync", true, "play", 2);
		PacketLog.add("in", "mymod:sync", true, "configuration", 3);
		PacketLog.add("in", "mymod:sync", true, "play", 4);

		PacketLog.Filter in = new PacketLog.Filter(WaitActions.textMatcher("packet", "mymod:sync", false), "in", false);
		JsonObject hit = PacketLog.find(after, in);
		assertEquals(3, hit.get("bytes").getAsInt());
		assertEquals("configuration", hit.get("phase").getAsString());
		assertEquals(2, PacketLog.find(after, PacketLog.Filter.ALL).get("bytes").getAsInt());
		assertNull(PacketLog.find(PacketLog.latestId(), PacketLog.Filter.ALL));
	}

	@Test
	void directionAcceptsInOutOrAny() {
		assertEquals("in", PacketLog.Filter.direction(" IN "));
		assertEquals("out", PacketLog.Filter.direction("out"));
		assertNull(PacketLog.Filter.direction("any"));
		assertNull(PacketLog.Filter.direction(null));
		assertThrows(IllegalArgumentException.class, () -> PacketLog.Filter.direction("up"));
	}
}
