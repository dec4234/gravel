package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Asset and data audit for one mod namespace. For each item, block and entity type it tells what is missing:
 * the translation (the name shows as a raw key), the model, the texture (the purple and black texture), the
 * loot table and the recipe.
 */
final class RegistryAudit {
	private static final Identifier MISSING_TEXTURE = MissingTextureAtlasSprite.getLocation();

	private RegistryAudit() {
	}

	static JsonObject audit(Minecraft mc, String namespace, String registryName, String filter, int limit) {
		if (namespace == null || namespace.isBlank()) {
			throw new IllegalArgumentException("audit needs a namespace, for example \"mymod\".");
		}
		String ns = namespace.trim().toLowerCase(Locale.ROOT).replaceFirst(":$", "");
		boolean items = true;
		boolean blocks = true;
		boolean entities = true;
		if (registryName != null && !registryName.isBlank()) {
			Identifier id = RegistryActions.registryId(registryName);
			String path = id == null ? "" : id.getPath();
			items = path.equals("item");
			blocks = path.equals("block");
			entities = path.equals("entity_type");
			if (!items && !blocks && !entities) {
				throw new IllegalArgumentException("audit works on item, block and entity. Omit registry to audit all three.");
			}
		}

		Set<Identifier> recipeOutputs = DataPackRegistries.recipeOutputs(mc);
		Set<Identifier> lootTables = DataPackRegistries.lootTables(mc);
		Language language = Language.getInstance();

		List<JsonObject> problems = new ArrayList<>();
		JsonObject checked = new JsonObject();
		if (items) {
			int n = 0;
			for (Item item : BuiltInRegistries.ITEM) {
				Identifier id = BuiltInRegistries.ITEM.getKey(item);
				if (!inScope(id, ns, filter)) {
					continue;
				}
				n++;
				JsonArray missing = new JsonArray();
				checkTranslation(language, item.getDescriptionId(), missing);
				checkItemModel(mc, item, missing);
				if (recipeOutputs != null && !recipeOutputs.contains(id)) {
					missing.add(issue("recipe", "no recipe makes this item (this can be correct, for example for ores "
							+ "and spawn eggs)"));
				}
				addIfMissing(problems, "item", id, missing);
			}
			checked.addProperty("item", n);
		}
		if (blocks) {
			int n = 0;
			for (Block block : BuiltInRegistries.BLOCK) {
				Identifier id = BuiltInRegistries.BLOCK.getKey(block);
				if (!inScope(id, ns, filter)) {
					continue;
				}
				n++;
				JsonArray missing = new JsonArray();
				checkTranslation(language, block.getDescriptionId(), missing);
				checkBlockModel(mc, id, block, missing);
				checkLootTable(lootTables, block.getLootTable(), missing);
				addIfMissing(problems, "block", id, missing);
			}
			checked.addProperty("block", n);
		}
		if (entities) {
			int n = 0;
			for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
				Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
				if (!inScope(id, ns, filter)) {
					continue;
				}
				n++;
				JsonArray missing = new JsonArray();
				checkTranslation(language, type.getDescriptionId(), missing);
				checkLootTable(lootTables, type.getDefaultLootTable(), missing);
				addIfMissing(problems, "entity_type", id, missing);
			}
			checked.addProperty("entity_type", n);
		}

		JsonArray entries = new JsonArray();
		problems.subList(0, Math.min(limit, problems.size())).forEach(entries::add);
		JsonObject o = new JsonObject();
		o.addProperty("namespace", ns);
		o.addProperty("language", mc.getLanguageManager().getSelected());
		o.addProperty("serverData", recipeOutputs != null);
		if (recipeOutputs == null) {
			o.addProperty("note", "Recipes and loot tables were not checked: they are server data. Audit in a "
					+ "singleplayer world to check them too.");
		}
		o.add("checked", checked);
		o.addProperty("withProblems", problems.size());
		o.addProperty("returned", entries.size());
		o.addProperty("truncated", problems.size() > entries.size());
		o.add("entries", entries);
		return o;
	}

	private static boolean inScope(Identifier id, String namespace, String filter) {
		return id != null && id.getNamespace().equals(namespace) && BlockGroups.matches(id.toString(), filter);
	}

	private static void addIfMissing(List<JsonObject> problems, String registry, Identifier id, JsonArray missing) {
		if (missing.isEmpty()) {
			return;
		}
		JsonObject e = new JsonObject();
		e.addProperty("registry", registry);
		e.addProperty("id", id.toString());
		e.add("missing", missing);
		problems.add(e);
	}

	private static JsonObject issue(String check, String detail) {
		JsonObject o = new JsonObject();
		o.addProperty("check", check);
		o.addProperty("detail", detail);
		return o;
	}

	private static void checkTranslation(Language language, String key, JsonArray missing) {
		if (!language.has(key)) {
			missing.add(issue("translation", "no lang entry for \"" + key + "\", so the name shows as the raw key"));
		}
	}

	/** Item model: the client item file (assets/NS/items/PATH.json) and the textures it uses. */
	private static void checkItemModel(Minecraft mc, Item item, JsonArray missing) {
		ItemStack stack = item.getDefaultInstance();
		Identifier modelId = stack.get(DataComponents.ITEM_MODEL);
		if (modelId == null) {
			return;
		}
		if (mc.getModelManager().getItemModel(modelId) instanceof MissingItemModel) {
			missing.add(issue("item_model", "no client item for " + modelId + " (expected assets/"
					+ modelId.getNamespace() + "/items/" + modelId.getPath() + ".json)"));
			return;
		}
		try {
			ItemStackRenderState state = new ItemStackRenderState();
			mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, null, 0);
			Material.Baked particle = state.isEmpty() ? null : state.pickParticleMaterial(RandomSource.create(0));
			if (particle != null && isMissing(particle.sprite())) {
				missing.add(issue("texture", "the item model for " + modelId + " uses the missing texture"));
			}
		} catch (RuntimeException ignored) {
			// Some special item models need a world or an owner. The model itself is present.
		}
	}

	/** Block model: a model for each block state (assets/NS/blockstates/PATH.json) and the textures on its faces. */
	private static void checkBlockModel(Minecraft mc, Identifier id, Block block, JsonArray missing) {
		BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
		BlockStateModel missingModel = models.missingModel();
		List<BlockState> states = block.getStateDefinition().getPossibleStates();
		int noModel = 0;
		int noTexture = 0;
		String firstNoModel = null;
		String firstNoTexture = null;
		for (BlockState state : states) {
			if (state.getRenderShape() == RenderShape.INVISIBLE) {
				continue;
			}
			BlockStateModel model = models.get(state);
			if (model == null || model == missingModel) {
				noModel++;
				firstNoModel = firstNoModel == null ? state.toString() : firstNoModel;
			} else if (usesMissingTexture(model)) {
				noTexture++;
				firstNoTexture = firstNoTexture == null ? state.toString() : firstNoTexture;
			}
		}
		if (noModel > 0) {
			missing.add(issue("block_model", noModel + " of " + states.size() + " block states have no model (first: "
					+ firstNoModel + "). Check assets/" + id.getNamespace() + "/blockstates/" + id.getPath() + ".json"));
		}
		if (noTexture > 0) {
			missing.add(issue("texture", noTexture + " of " + states.size() + " block states use the missing "
					+ "texture (first: " + firstNoTexture + ")"));
		}
	}

	private static boolean usesMissingTexture(BlockStateModel model) {
		if (isMissing(model.particleMaterial().sprite())) {
			return true;
		}
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(0), parts);
		for (BlockStateModelPart part : parts) {
			if (isMissing(part.particleMaterial().sprite())) {
				return true;
			}
			for (Direction d : Direction.values()) {
				if (anyMissing(part.getQuads(d))) {
					return true;
				}
			}
			if (anyMissing(part.getQuads(null))) {
				return true;
			}
		}
		return false;
	}

	private static boolean anyMissing(List<BakedQuad> quads) {
		for (BakedQuad quad : quads) {
			if (isMissing(quad.materialInfo().sprite())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isMissing(TextureAtlasSprite sprite) {
		return sprite != null && MISSING_TEXTURE.equals(sprite.contents().name());
	}

	/** A block or entity that says it has a loot table, but the data packs do not have it. */
	private static void checkLootTable(Set<Identifier> lootTables, Optional<ResourceKey<LootTable>> table, JsonArray missing) {
		if (lootTables == null || table.isEmpty()) {
			return;
		}
		Identifier id = table.get().identifier();
		if (!lootTables.contains(id)) {
			missing.add(issue("loot_table", "no loot table " + id + " (expected data/" + id.getNamespace()
					+ "/loot_table/" + id.getPath() + ".json), so it drops nothing"));
		}
	}
}
