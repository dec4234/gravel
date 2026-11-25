package net.dec4234.gravel.client.bridge;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a game rule name into its registry id. Game rules got snake_case registry ids (for example
 * {@code minecraft:advance_time}), but most people still know the old camelCase names (for example
 * {@code doDaylightCycle}). The table is the same as the vanilla data fixer {@code GameRuleRegistryFix}.
 * Some old rules have the opposite meaning of the new ones ({@code disableRaids} is {@code raids}), so
 * their values must be inverted.
 */
final class GameRuleNames {
	/** A resolved rule name: the registry id, and if a boolean value must be inverted. */
	record Resolved(String id, boolean inverted) {
	}

	private static final Map<String, String> LEGACY = new HashMap<>();
	private static final Map<String, String> INVERTED = new HashMap<>();

	static {
		legacy("announceAdvancements", "show_advancement_messages");
		legacy("allowEnteringNetherUsingPortals", "allow_entering_nether_using_portals");
		legacy("blockExplosionDropDecay", "block_explosion_drop_decay");
		legacy("commandBlockOutput", "command_block_output");
		legacy("enableCommandBlocks", "command_blocks_work");
		legacy("commandBlocksEnabled", "command_blocks_work");
		legacy("commandModificationBlockLimit", "max_block_modifications");
		legacy("doDaylightCycle", "advance_time");
		legacy("doEntityDrops", "entity_drops");
		legacy("doImmediateRespawn", "immediate_respawn");
		legacy("doInsomnia", "spawn_phantoms");
		legacy("doLimitedCrafting", "limited_crafting");
		legacy("doMobLoot", "mob_drops");
		legacy("doMobSpawning", "spawn_mobs");
		legacy("doPatrolSpawning", "spawn_patrols");
		legacy("doTileDrops", "block_drops");
		legacy("doTraderSpawning", "spawn_wandering_traders");
		legacy("doVinesSpread", "spread_vines");
		legacy("doWardenSpawning", "spawn_wardens");
		legacy("doWeatherCycle", "advance_weather");
		legacy("drowningDamage", "drowning_damage");
		legacy("enderPearlsVanishOnDeath", "ender_pearls_vanish_on_death");
		legacy("fallDamage", "fall_damage");
		legacy("fireDamage", "fire_damage");
		legacy("forgiveDeadPlayers", "forgive_dead_players");
		legacy("freezeDamage", "freeze_damage");
		legacy("globalSoundEvents", "global_sound_events");
		legacy("keepInventory", "keep_inventory");
		legacy("lavaSourceConversion", "lava_source_conversion");
		legacy("locatorBar", "locator_bar");
		legacy("logAdminCommands", "log_admin_commands");
		legacy("maxCommandChainLength", "max_command_sequence_length");
		legacy("maxCommandForkCount", "max_command_forks");
		legacy("maxEntityCramming", "max_entity_cramming");
		legacy("minecartMaxSpeed", "max_minecart_speed");
		legacy("mobExplosionDropDecay", "mob_explosion_drop_decay");
		legacy("mobGriefing", "mob_griefing");
		legacy("naturalRegeneration", "natural_health_regeneration");
		legacy("playersNetherPortalCreativeDelay", "players_nether_portal_creative_delay");
		legacy("playersNetherPortalDefaultDelay", "players_nether_portal_default_delay");
		legacy("playersSleepingPercentage", "players_sleeping_percentage");
		legacy("projectilesCanBreakBlocks", "projectiles_can_break_blocks");
		legacy("pvp", "pvp");
		legacy("randomTickSpeed", "random_tick_speed");
		legacy("reducedDebugInfo", "reduced_debug_info");
		legacy("sendCommandFeedback", "send_command_feedback");
		legacy("showDeathMessages", "show_death_messages");
		legacy("snowAccumulationHeight", "max_snow_accumulation_height");
		legacy("spawnMonsters", "spawn_monsters");
		legacy("spawnRadius", "respawn_radius");
		legacy("spawnerBlocksEnabled", "spawner_blocks_work");
		legacy("spectatorsGenerateChunks", "spectators_generate_chunks");
		legacy("tntExplodes", "tnt_explodes");
		legacy("tntExplosionDropDecay", "tnt_explosion_drop_decay");
		legacy("universalAnger", "universal_anger");
		legacy("waterSourceConversion", "water_source_conversion");
		INVERTED.put("disableraids", "minecraft:raids");
		INVERTED.put("disableelytramovementcheck", "minecraft:elytra_movement_check");
		INVERTED.put("disableplayermovementcheck", "minecraft:player_movement_check");
	}

	private GameRuleNames() {
	}

	private static void legacy(String oldName, String newPath) {
		LEGACY.put(oldName.toLowerCase(Locale.ROOT), "minecraft:" + newPath);
	}

	/**
	 * The registry id for a rule name. Accepts old camelCase names (any case), new ids with or without the
	 * namespace, and ids of rules that mods add.
	 */
	static Resolved resolve(String name) {
		String trimmed = name.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (LEGACY.containsKey(lower)) {
			return new Resolved(LEGACY.get(lower), false);
		}
		if (INVERTED.containsKey(lower)) {
			return new Resolved(INVERTED.get(lower), true);
		}
		return new Resolved(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed, false);
	}

	/** The value to set: "true" and "false" swap for an inverted rule. */
	static String value(Resolved rule, String value) {
		if (!rule.inverted()) {
			return value;
		}
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "true" -> "false";
			case "false" -> "true";
			default -> value;
		};
	}
}
