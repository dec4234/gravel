package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaitActionsTest {
	@BeforeEach
	void reset() {
		ChatLog.clear();
	}

	@Test
	void chatMatcherIsCaseInsensitiveTextOrRegex() {
		assertTrue(WaitActions.chatMatcher("set the time", false).test("Set the time to 1000"));
		assertFalse(WaitActions.chatMatcher("weather", false).test("Set the time to 1000"));
		assertTrue(WaitActions.chatMatcher("", false).test("anything"));
		assertTrue(WaitActions.chatMatcher("time to \\d+", true).test("Set the time to 1000"));
		assertFalse(WaitActions.chatMatcher("^time", true).test("Set the time to 1000"));
		assertThrows(IllegalArgumentException.class, () -> WaitActions.chatMatcher("(", true));
	}

	@Test
	void screenMatchesByNameWithOptionalSuffixOrNone() {
		assertTrue(WaitActions.screenMatches("InventoryScreen", "InventoryScreen"));
		assertTrue(WaitActions.screenMatches("inventory", "InventoryScreen"));
		assertFalse(WaitActions.screenMatches("inventory", null));
		assertTrue(WaitActions.screenMatches("none", null));
		assertFalse(WaitActions.screenMatches("none", "PauseScreen"));
	}

	@Test
	void distanceUsesOnlyGivenAxes() {
		assertEquals(5.0, WaitActions.distance(3, 100, 4, 0.0, null, 0.0), 1e-9);
		assertEquals(2.0, WaitActions.distance(3, 62, 4, null, 64.0, null), 1e-9);
	}

	@Test
	void parseNeedsTicksOrCondition() {
		assertThrows(IllegalArgumentException.class, () -> WaitActions.Conditions.parse(new JsonObject(), 0, 0));
		JsonObject ticks = new JsonObject();
		ticks.addProperty("ticks", 20);
		WaitActions.Conditions c = WaitActions.Conditions.parse(ticks, 0, 0);
		assertFalse(c.hasCondition());
		// A ticks-only wait gets the longest time limit, so the time limit does not end it early.
		assertEquals(WaitActions.MAX_TIMEOUT_MS, c.timeoutMs());
	}

	@Test
	void parseDefaultsSinceIdToLatestChatLine() {
		JsonObject params = new JsonObject();
		params.addProperty("chat", "hi");
		WaitActions.Conditions c = WaitActions.Conditions.parse(params, 17, 0);
		assertEquals(17, c.sinceId());
		assertEquals(WaitActions.DEFAULT_TIMEOUT_MS, c.timeoutMs());
		params.addProperty("since_id", 3);
		assertEquals(3, WaitActions.Conditions.parse(params, 17, 0).sinceId());
	}

	@Test
	void parsePacketDefaultsToReceivedPacketsAfterTheLatestId() {
		JsonObject params = new JsonObject();
		params.addProperty("packet", "mymod:sync");
		WaitActions.Conditions c = WaitActions.Conditions.parse(params, 0, 9);
		assertTrue(c.hasCondition());
		assertTrue(c.hasPacket());
		assertEquals(9, c.packetSinceId());
		assertTrue(c.packet().test("in", "mymod:sync", true));
		assertFalse(c.packet().test("out", "mymod:sync", true));
		assertFalse(c.packet().test("in", "mymod:other", true));

		params.addProperty("packet_direction", "any");
		params.addProperty("packet_since_id", 2);
		c = WaitActions.Conditions.parse(params, 0, 9);
		assertEquals(2, c.packetSinceId());
		assertTrue(c.packet().test("out", "mymod:sync", true));

		params.addProperty("packet_direction", "sideways");
		assertThrows(IllegalArgumentException.class, () -> WaitActions.Conditions.parse(params, 0, 9));
	}

	@Test
	void parseRejectsBadPacketRegexWithItsName() {
		JsonObject params = new JsonObject();
		params.addProperty("packet", "(");
		params.addProperty("packet_regex", true);
		var e = assertThrows(IllegalArgumentException.class, () -> WaitActions.Conditions.parse(params, 0, 0));
		assertTrue(e.getMessage().startsWith("packet "), e.getMessage());
	}

	@Test
	void parseRejectsOutOfRangeValues() {
		for (String[] bad : new String[][]{{"ticks", "-1"}, {"ticks", "501"}, {"timeout_ms", "25001"},
				{"settle_ticks", "41"}, {"radius", "-1"}}) {
			JsonObject params = new JsonObject();
			params.addProperty("in_world", true);
			params.addProperty(bad[0], Double.parseDouble(bad[1]));
			assertThrows(IllegalArgumentException.class, () -> WaitActions.Conditions.parse(params, 0, 0), bad[0]);
		}
	}

	@Test
	void chatLogFindReturnsOldestMatchAfterId() {
		ChatLog.add("system", null, "Teleported Player to 1, 2, 3");
		long after = ChatLog.latestId();
		ChatLog.add("actionbar", null, "Gave 1 stone");
		ChatLog.add("system", null, "Gave 64 stone");
		ChatLog.add("system", null, "Gave 2 dirt");

		JsonObject hit = ChatLog.find(after, WaitActions.chatMatcher("gave", false), false);
		assertEquals("Gave 64 stone", hit.get("text").getAsString());
		assertEquals("Gave 1 stone", ChatLog.find(after, null, true).get("text").getAsString());
		assertNull(ChatLog.find(after, WaitActions.chatMatcher("teleported", false), false));
	}
}
