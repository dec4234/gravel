package net.dec4234.gravel.client.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameRuleNamesTest {
	@Test
	void legacyNamesMapToRegistryIds() {
		assertEquals(new GameRuleNames.Resolved("minecraft:advance_time", false), GameRuleNames.resolve("doDaylightCycle"));
		assertEquals(new GameRuleNames.Resolved("minecraft:advance_time", false), GameRuleNames.resolve(" dodaylightcycle "));
		assertEquals("minecraft:random_tick_speed", GameRuleNames.resolve("randomTickSpeed").id());
		assertEquals("minecraft:spawn_mobs", GameRuleNames.resolve("doMobSpawning").id());
	}

	@Test
	void newIdsAndModIdsPassThrough() {
		assertEquals("minecraft:advance_time", GameRuleNames.resolve("advance_time").id());
		assertEquals("minecraft:advance_time", GameRuleNames.resolve("minecraft:advance_time").id());
		assertEquals("mymod:fast_machines", GameRuleNames.resolve("mymod:fast_machines").id());
	}

	@Test
	void invertedRulesSwapBooleanValues() {
		GameRuleNames.Resolved raids = GameRuleNames.resolve("disableRaids");
		assertEquals(new GameRuleNames.Resolved("minecraft:raids", true), raids);
		assertEquals("false", GameRuleNames.value(raids, "true"));
		assertEquals("true", GameRuleNames.value(raids, "FALSE"));
		assertEquals("true", GameRuleNames.value(GameRuleNames.resolve("keepInventory"), "true"));
	}
}
