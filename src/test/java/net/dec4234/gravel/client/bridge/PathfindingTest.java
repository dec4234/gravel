package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathfindingTest {
	@Test
	void namesSplitCommasAndSpaces() {
		assertEquals(List.of("diamond_ore", "minecraft:iron_ore"),
				Pathfinding.names(new JsonPrimitive(" Diamond_Ore, minecraft:iron_ore "), "blocks"));
	}

	@Test
	void namesReadArraysAndRemoveDuplicates() {
		JsonArray a = new JsonArray();
		a.add("oak_log");
		a.add("birch_log, oak_log");
		assertEquals(List.of("oak_log", "birch_log"), Pathfinding.names(a, "blocks"));
	}

	@Test
	void emptyNamesAreAnError() {
		assertThrows(IllegalArgumentException.class, () -> Pathfinding.names(new JsonPrimitive(" , "), "blocks"));
		assertThrows(IllegalArgumentException.class, () -> Pathfinding.names(null, "blocks"));
	}

	@Test
	void waitMsDefaultsToZeroAndHasLimits() {
		JsonObject p = new JsonObject();
		assertEquals(0, Pathfinding.waitMs(p));
		p.addProperty("wait_ms", 5000);
		assertEquals(5000, Pathfinding.waitMs(p));
		p.addProperty("wait_ms", Pathfinding.MAX_WAIT_MS + 1);
		assertThrows(IllegalArgumentException.class, () -> Pathfinding.waitMs(p));
		p.addProperty("wait_ms", -1);
		assertThrows(IllegalArgumentException.class, () -> Pathfinding.waitMs(p));
	}
}
