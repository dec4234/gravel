package net.dec4234.gravel.client.bridge;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.util.context.ContextMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data-pack content that the client does not keep as a registry: recipes, loot tables, item modifiers, predicates
 * and advancements. In singleplayer they come from the integrated server. On a multiplayer server only the
 * advancements that the server sent are known.
 */
final class DataPackRegistries {
	/** One data-pack entry: its id, an optional name the player sees and an optional type. */
	record Entry(String id, String name, String type) {
	}

	/** The entries of one data-pack registry and where they came from. */
	record Result(List<Entry> entries, String source, boolean complete) {
	}

	static final Map<Identifier, String> KINDS = new LinkedHashMap<>();

	static {
		KINDS.put(Registries.RECIPE.identifier(), "recipe");
		KINDS.put(Registries.LOOT_TABLE.identifier(), "loot_table");
		KINDS.put(Registries.ADVANCEMENT.identifier(), "advancement");
		KINDS.put(Registries.ITEM_MODIFIER.identifier(), "item_modifier");
		KINDS.put(Registries.PREDICATE.identifier(), "predicate");
	}

	private DataPackRegistries() {
	}

	static boolean isDataPackRegistry(Identifier id) {
		return id != null && KINDS.containsKey(id);
	}

	/** True if the full server data (singleplayer) is available. */
	static boolean hasServerData(Minecraft mc) {
		return mc.getSingleplayerServer() != null;
	}

	/** The entries of a data-pack registry, or an error that says why they are not available. */
	static Result entries(Minecraft mc, Identifier registry) {
		MinecraftServer server = mc.getSingleplayerServer();
		ResourceKey<? extends Registry<?>> key = ResourceKey.createRegistryKey(registry);
		List<Entry> out = new ArrayList<>();
		if (server != null) {
			if (key.equals(Registries.RECIPE)) {
				for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
					Identifier type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
					out.add(new Entry(holder.id().identifier().toString(), null, type == null ? null : shortId(type)));
				}
			} else if (key.equals(Registries.ADVANCEMENT)) {
				for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
					out.add(advancement(holder));
				}
			} else {
				server.reloadableRegistries().lookup().lookupOrThrow(key).listElementIds()
						.forEach(k -> out.add(new Entry(k.identifier().toString(), null, null)));
			}
			return new Result(out, "integrated_server", true);
		}

		ClientPacketListener connection = mc.getConnection();
		if (key.equals(Registries.ADVANCEMENT) && connection != null) {
			for (AdvancementNode node : connection.getAdvancements().tree().nodes()) {
				out.add(advancement(node.holder()));
			}
			return new Result(out, "server_sent", false);
		}
		String why = connection == null ? "Join a singleplayer world first."
				: "A multiplayer server does not send " + KINDS.get(registry) + " data to the client. Use a singleplayer world.";
		throw new IllegalArgumentException("The " + KINDS.get(registry) + " registry is server data. " + why);
	}

	/** The ids of all items that some recipe makes. Null without server data. */
	static Set<Identifier> recipeOutputs(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) {
			return null;
		}
		ContextMap context = SlotDisplayContext.fromLevel(server.overworld());
		Set<Identifier> outputs = new HashSet<>();
		for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
			Recipe<?> recipe = holder.value();
			try {
				for (RecipeDisplay display : recipe.display()) {
					for (ItemStack stack : display.result().resolveForStacks(context)) {
						if (!stack.isEmpty()) {
							outputs.add(BuiltInRegistries.ITEM.getKey(stack.getItem()));
						}
					}
				}
			} catch (RuntimeException ignored) {
				// A recipe with a bad display does not stop the audit.
			}
		}
		return outputs;
	}

	/** The ids of all loot tables. Null without server data. */
	static Set<Identifier> lootTables(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) {
			return null;
		}
		Set<Identifier> ids = new HashSet<>();
		server.reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).listElementIds()
				.forEach(k -> ids.add(k.identifier()));
		return ids;
	}

	/** Size of each data-pack registry that is available now, for the registry list. */
	static Map<Identifier, Integer> sizes(Minecraft mc) {
		Map<Identifier, Integer> sizes = new HashMap<>();
		for (Identifier id : KINDS.keySet()) {
			try {
				sizes.put(id, entries(mc, id).entries().size());
			} catch (RuntimeException ignored) {
				// Not available here.
			}
		}
		return sizes;
	}

	private static Entry advancement(AdvancementHolder holder) {
		String name = holder.value().display().map(d -> d.title().getString()).orElse(null);
		return new Entry(holder.id().toString(), name, null);
	}

	private static String shortId(Identifier id) {
		return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
	}
}
