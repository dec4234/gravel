package net.dec4234.gravel.client.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegistryActionsTest {
	@Test
	void registryIdUsesAliases() {
		assertEquals("minecraft:item", RegistryActions.registryId("item").toString());
		assertEquals("minecraft:entity_type", RegistryActions.registryId("Entity").toString());
		assertEquals("minecraft:worldgen/biome", RegistryActions.registryId("biome").toString());
		assertEquals("minecraft:worldgen/biome", RegistryActions.registryId("minecraft:biome").toString());
		assertEquals("minecraft:mob_effect", RegistryActions.registryId(" effect ").toString());
		assertEquals("minecraft:enchantment", RegistryActions.registryId("enchantment").toString());
		assertEquals("mymod:things", RegistryActions.registryId("mymod:things").toString());
	}

	@Test
	void rankPutsExactThenPrefixFirst() {
		assertEquals(0, RegistryActions.rank("minecraft:diamond", "diamond"));
		assertEquals(0, RegistryActions.rank("minecraft:diamond", "minecraft:diamond"));
		assertEquals(1, RegistryActions.rank("minecraft:diamond_sword", "diamond"));
		assertEquals(2, RegistryActions.rank("minecraft:deepslate_diamond_ore", "diamond"));
		assertEquals(0, RegistryActions.rank("minecraft:zombie", "skeleton, zombie"));
		assertEquals(2, RegistryActions.rank("minecraft:stone", null));
	}
}
