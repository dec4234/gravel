package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockGroupsTest {
	@Test
	void groupsMostCommonFirstAndNearestPositionsFirst() {
		BlockGroups g = new BlockGroups(0, 64, 0, 10);
		g.add("minecraft:stone", 3, 64, 0);
		g.add("minecraft:stone", 1, 64, 0);
		g.add("minecraft:stone", 0, 62, 0);
		g.add("minecraft:dirt", 0, 63, 0);

		assertEquals(2, g.kinds());
		assertEquals(4, g.total());
		JsonArray out = g.toJson();
		JsonObject stone = out.get(0).getAsJsonObject();
		assertEquals("minecraft:stone", stone.get("block").getAsString());
		assertEquals(3, stone.get("count").getAsInt());
		assertFalse(stone.get("truncated").getAsBoolean());
		JsonArray first = stone.getAsJsonArray("positions").get(0).getAsJsonArray();
		assertEquals(1, first.get(0).getAsInt());
		assertEquals(64, first.get(1).getAsInt());
		assertEquals("minecraft:dirt", out.get(1).getAsJsonObject().get("block").getAsString());
	}

	@Test
	void truncatesPositionsButKeepsFullCount() {
		BlockGroups g = new BlockGroups(0, 0, 0, 2);
		for (int x = 0; x < 5; x++) {
			g.add("minecraft:sand", x, 0, 0);
		}
		JsonObject sand = g.toJson().get(0).getAsJsonObject();
		assertEquals(5, sand.get("count").getAsInt());
		assertEquals(2, sand.getAsJsonArray("positions").size());
		assertTrue(sand.get("truncated").getAsBoolean());
	}

	@Test
	void matchesAnyCommaSeparatedTermIgnoringCase() {
		assertTrue(BlockGroups.matches("minecraft:iron_ore", null));
		assertTrue(BlockGroups.matches("minecraft:iron_ore", " "));
		assertTrue(BlockGroups.matches("minecraft:iron_ore", "ORE"));
		assertTrue(BlockGroups.matches("minecraft:oak_log", "leaves, log"));
		assertFalse(BlockGroups.matches("minecraft:stone", "ore,log"));
		assertFalse(BlockGroups.matches("minecraft:stone", ",,"));
	}

	@Test
	void clampKeepsValueInRange() {
		assertEquals(0, BlockGroups.clamp(-3, 0, 16));
		assertEquals(16, BlockGroups.clamp(99, 0, 16));
		assertEquals(5, BlockGroups.clamp(5, 0, 16));
	}
}
