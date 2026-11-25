package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry lookup: finds the ids in a game registry (items, blocks, entity types, biomes, enchantments and all
 * others) that match a filter, so commands like /give and /summon can use correct ids.
 */
public final class RegistryActions {
	static final int DEFAULT_LIMIT = 100;
	static final int MAX_LIMIT = 2000;

	/** Short names for the registries that are used most. Other registries use their own id. */
	static final Map<String, String> ALIASES = new LinkedHashMap<>();

	static {
		ALIASES.put("entity", "entity_type");
		ALIASES.put("mob", "entity_type");
		ALIASES.put("biome", "worldgen/biome");
		ALIASES.put("structure", "worldgen/structure");
		ALIASES.put("feature", "worldgen/configured_feature");
		ALIASES.put("effect", "mob_effect");
		ALIASES.put("sound", "sound_event");
		ALIASES.put("particle", "particle_type");
		ALIASES.put("dimension", "dimension_type");
	}

	private RegistryActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("lookup_registry", RegistryActions::lookupRegistry);
	}

	/** Gives the registry id for a name like "item", "entity", "biome" or "minecraft:worldgen/biome". */
	static Identifier registryId(String name) {
		String n = name.trim().toLowerCase(Locale.ROOT);
		if (n.startsWith("minecraft:")) {
			n = n.substring("minecraft:".length());
		}
		n = ALIASES.getOrDefault(n, n);
		return Identifier.tryParse(n);
	}

	/**
	 * How well an id matches the filter terms: 0 for an exact path match, 1 if the path starts with a term, 2 for
	 * other matches. Lower is better.
	 */
	static int rank(String id, String filter) {
		if (filter == null || filter.isBlank()) {
			return 2;
		}
		String path = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
		int best = 2;
		for (String term : filter.toLowerCase(Locale.ROOT).split(",")) {
			String t = term.trim();
			if (t.isEmpty()) {
				continue;
			}
			if (path.equals(t) || id.equalsIgnoreCase(t)) {
				return 0;
			}
			if (path.startsWith(t)) {
				best = 1;
			}
		}
		return best;
	}

	private static JsonObject lookupRegistry(Minecraft mc, JsonObject params) {
		String registryName = stringParam(params, "registry");
		String filter = stringParam(params, "filter");
		String tag = stringParam(params, "tag");
		int limit = BlockGroups.clamp(intParam(params, "limit", DEFAULT_LIMIT), 1, MAX_LIMIT);
		boolean includeTags = boolParam(params, "include_tags", false);

		Map<Identifier, Registry<?>> all = registries(mc);
		if (registryName == null || registryName.isBlank()) {
			return listRegistries(mc, all, filter);
		}

		Identifier id = registryId(registryName);
		Registry<?> registry = id == null ? null : all.get(id);
		if (registry == null) {
			String hint = mc.level == null ? " Some registries (biomes, enchantments) need a world. Join a world first." : "";
			throw new IllegalArgumentException("Unknown registry: " + registryName + ". Call without registry to list "
					+ "them, or use item, block, entity, biome, enchantment, effect." + hint);
		}
		return lookup(registry, filter, tag, limit, includeTags);
	}

	/** All registries the client knows: the built-in ones, and in a world also the ones the server sent (biomes and more). */
	private static Map<Identifier, Registry<?>> registries(Minecraft mc) {
		Map<Identifier, Registry<?>> out = new LinkedHashMap<>();
		for (Registry<?> r : BuiltInRegistries.REGISTRY) {
			out.put(r.key().identifier(), r);
		}
		if (mc.level != null) {
			RegistryAccess access = mc.level.registryAccess();
			access.registries().forEach(e -> out.putIfAbsent(e.key().identifier(), e.value()));
		}
		return out;
	}

	private static JsonObject listRegistries(Minecraft mc, Map<Identifier, Registry<?>> all, String filter) {
		List<Identifier> ids = new ArrayList<>(all.keySet());
		ids.sort(Comparator.comparing(Identifier::toString));
		JsonArray list = new JsonArray();
		for (Identifier id : ids) {
			if (BlockGroups.matches(id.toString(), filter)) {
				JsonObject o = new JsonObject();
				o.addProperty("registry", id.getNamespace().equals("minecraft") ? id.getPath() : id.toString());
				o.addProperty("size", all.get(id).size());
				list.add(o);
			}
		}
		JsonObject aliases = new JsonObject();
		ALIASES.forEach(aliases::addProperty);
		JsonObject o = new JsonObject();
		o.addProperty("inWorld", mc.level != null);
		o.add("registries", list);
		o.add("aliases", aliases);
		return o;
	}

	private static <T> JsonObject lookup(Registry<T> registry, String filter, String tag, int limit, boolean includeTags) {
		ResourceKey<? extends Registry<T>> key = registry.key();
		List<Holder<T>> members = new ArrayList<>();
		if (tag != null && !tag.isBlank()) {
			Identifier tagId = Identifier.tryParse(tag.trim().replaceFirst("^#", ""));
			if (tagId == null) {
				throw new IllegalArgumentException("Bad tag id: " + tag);
			}
			TagKey<T> tagKey = TagKey.create(key, tagId);
			if (registry.get(tagKey).isEmpty()) {
				throw new IllegalArgumentException("Unknown tag #" + tagId + " in registry " + key.identifier());
			}
			registry.getTagOrEmpty(tagKey).forEach(members::add);
		} else {
			registry.keySet().forEach(id -> registry.get(id).ifPresent(members::add));
		}

		record Match<T>(String id, String name, Holder<T> holder, int rank) {
		}
		List<Match<T>> matches = new ArrayList<>();
		for (Holder<T> holder : members) {
			Optional<ResourceKey<T>> k = holder.unwrapKey();
			if (k.isEmpty()) {
				continue;
			}
			String id = k.get().identifier().toString();
			String name = displayName(holder.value());
			if (BlockGroups.matches(id, filter) || (name != null && BlockGroups.matches(name, filter))) {
				matches.add(new Match<>(id, name, holder, rank(id, filter)));
			}
		}
		matches.sort(Comparator.<Match<T>>comparingInt(Match::rank).thenComparing(Match::id));

		JsonArray entries = new JsonArray();
		for (Match<T> m : matches.subList(0, Math.min(limit, matches.size()))) {
			JsonObject e = new JsonObject();
			e.addProperty("id", m.id());
			if (m.name() != null) {
				e.addProperty("name", m.name());
			}
			if (includeTags) {
				JsonArray tags = new JsonArray();
				m.holder().tags().map(t -> "#" + t.location()).sorted().forEach(tags::add);
				e.add("tags", tags);
			}
			entries.add(e);
		}

		JsonObject o = new JsonObject();
		o.addProperty("registry", key.identifier().toString());
		if (tag != null && !tag.isBlank()) {
			o.addProperty("tag", tag);
		}
		o.addProperty("registrySize", registry.size());
		o.addProperty("matched", matches.size());
		o.addProperty("returned", entries.size());
		o.addProperty("truncated", matches.size() > entries.size());
		o.add("entries", entries);
		return o;
	}

	/** The name a player sees (in the game language) for items, blocks and entity types. Null for others. */
	private static String displayName(Object value) {
		try {
			if (value instanceof Item item) {
				return item.getName(item.getDefaultInstance()).getString();
			}
			if (value instanceof Block block) {
				return block.getName().getString();
			}
			if (value instanceof EntityType<?> type) {
				return type.getDescription().getString();
			}
		} catch (RuntimeException ignored) {
			// Some entries can not make a name outside a world. The id is enough.
		}
		return null;
	}

	private static int intParam(JsonObject params, String name, int fallback) {
		return params != null && params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsInt() : fallback;
	}

	private static boolean boolParam(JsonObject params, String name, boolean fallback) {
		return params != null && params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsBoolean() : fallback;
	}

	private static String stringParam(JsonObject params, String name) {
		return params != null && params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsString() : null;
	}
}
