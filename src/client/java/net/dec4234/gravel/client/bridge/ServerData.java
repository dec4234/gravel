package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Reads world data the client does not fully know: block entity NBT and entity NBT. In singleplayer the
 * data comes from the integrated server (the real data). On a multiplayer server only the client copy is
 * available, which is often only part of the data. Each result tells its source.
 */
final class ServerData {
	/** The longest wait for the server thread. */
	private static final long SERVER_WAIT_MS = 5000;

	private ServerData() {
	}

	/** Where NBT came from. */
	static String source(Minecraft mc) {
		return mc.getSingleplayerServer() != null ? "server" : "client";
	}

	/**
	 * Runs work on the integrated server thread with the server copy of the player's dimension, and waits
	 * for the result. Returns null if not in singleplayer.
	 */
	static <T> T onServerLevel(Minecraft mc, Function<ServerLevel, T> work) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.level == null) {
			return null;
		}
		var dimension = mc.level.dimension();
		try {
			return server.submit(() -> {
				ServerLevel level = server.getLevel(dimension);
				return level == null ? null : work.apply(level);
			}).get(SERVER_WAIT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new IllegalStateException("The integrated server did not answer in " + SERVER_WAIT_MS + " ms");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while reading server data", e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("Could not read server data: " + e.getCause(), e.getCause());
		}
	}

	// ---------------------------------------------------------------- block entities

	/** The full NBT (with id and position) of the block entities at these positions, as SNBT. Missing ones are left out. */
	static Map<BlockPos, String> blockEntityNbt(Minecraft mc, List<BlockPos> positions) {
		Map<BlockPos, String> fromServer = onServerLevel(mc, level -> readBlockEntities(level, positions));
		return fromServer != null ? fromServer : readBlockEntities(mc.level, positions);
	}

	private static Map<BlockPos, String> readBlockEntities(Level level, List<BlockPos> positions) {
		Map<BlockPos, String> out = new LinkedHashMap<>();
		for (BlockPos pos : positions) {
			BlockEntity be = level.getBlockEntity(pos);
			if (be != null) {
				out.put(pos, be.saveWithFullMetadata(level.registryAccess()).toString());
			}
		}
		return out;
	}

	static String blockEntityType(BlockEntity be) {
		Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
		return id == null ? null : id.toString();
	}

	// ---------------------------------------------------------------- entities

	/** The NBT of these entities as SNBT, by UUID. Entities the server does not have are left out. */
	static Map<UUID, String> entityNbt(Minecraft mc, List<Entity> entities) {
		List<UUID> ids = new ArrayList<>();
		for (Entity e : entities) {
			ids.add(e.getUUID());
		}
		Map<UUID, String> fromServer = onServerLevel(mc, level -> {
			Map<UUID, String> out = new LinkedHashMap<>();
			for (UUID id : ids) {
				Entity e = level.getEntity(id);
				if (e != null) {
					out.put(id, saveEntity(e, level.registryAccess()).toString());
				}
			}
			return out;
		});
		if (fromServer != null) {
			return fromServer;
		}
		Map<UUID, String> out = new LinkedHashMap<>();
		for (Entity e : entities) {
			out.put(e.getUUID(), saveEntity(e, mc.level.registryAccess()).toString());
		}
		return out;
	}

	private static CompoundTag saveEntity(Entity e, HolderLookup.Provider registries) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		e.saveWithoutId(out);
		out.putString("id", SurroundingsActions.entityTypeId(e));
		return out.buildResult();
	}

	// ---------------------------------------------------------------- data components

	/**
	 * All data components of a map as JSON, by component id. Each value uses the component's codec, the same
	 * form as in commands and saves. Components without a codec (transient ones) show as text.
	 */
	static JsonObject componentsJson(DataComponentMap components, HolderLookup.Provider registries) {
		RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
		JsonObject o = new JsonObject();
		for (TypedDataComponent<?> component : components) {
			o.add(componentId(component.type()), encode(component, ops));
		}
		return o;
	}

	private static JsonElement encode(TypedDataComponent<?> component, RegistryOps<JsonElement> ops) {
		if (component.type().isTransient()) {
			return new JsonPrimitive("(not saved) " + component.value());
		}
		DataResult<JsonElement> result = component.encodeValue(ops);
		return result.result().orElseGet(() -> new JsonPrimitive("(could not encode: "
				+ result.error().map(DataResult.Error::message).orElse("unknown") + ") " + component.value()));
	}

	static String componentId(DataComponentType<?> type) {
		Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
		return id == null ? String.valueOf(type) : id.toString();
	}

	/** The ids of the components that the patch adds or changes, and of those it removes. */
	static List<List<String>> patchIds(DataComponentPatch patch) {
		List<String> changed = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		DataComponentPatch.SplitResult split = patch.split();
		for (DataComponentType<?> type : split.added().keySet()) {
			changed.add(componentId(type));
		}
		for (DataComponentType<?> type : split.removed()) {
			removed.add(componentId(type));
		}
		return List.of(changed, removed);
	}
}
