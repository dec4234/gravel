package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** Actions that let the MCP server see the world around the player: blocks, entities and other players. */
public final class SurroundingsActions {
	static final int MAX_BLOCK_RADIUS = 16;
	static final int DEFAULT_BLOCK_RADIUS = 4;
	/** The most block entities that scan_blocks reads NBT for. */
	static final int MAX_BLOCK_ENTITIES = 256;

	private SurroundingsActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("scan_blocks", SurroundingsActions::scanBlocks);
		registry.register("list_entities", SurroundingsActions::listEntities);
		registry.register("list_players", SurroundingsActions::listPlayers);
	}

	// ---------------------------------------------------------------- blocks

	private static JsonObject scanBlocks(Minecraft mc, JsonObject params) {
		LocalPlayer player = InventoryActions.requirePlayer(mc);
		ClientLevel level = mc.level;

		int radius = BlockGroups.clamp(intParam(params, "radius", DEFAULT_BLOCK_RADIUS), 0, MAX_BLOCK_RADIUS);
		int vertical = BlockGroups.clamp(intParam(params, "vertical_radius", radius), 0, MAX_BLOCK_RADIUS);
		boolean includeAir = boolParam(params, "include_air", false);
		boolean exposedOnly = boolParam(params, "exposed_only", false);
		boolean properties = boolParam(params, "include_properties", false);
		int maxPositions = Math.max(0, intParam(params, "max_positions_per_block", 64));
		String filter = stringParam(params, "filter");
		boolean blockEntities = boolParam(params, "include_block_entity", false);
		List<BlockPos> blockEntityPositions = new ArrayList<>();
		int blockEntityTotal = 0;

		BlockPos center = player.blockPosition();
		int minY = Math.max(level.getMinY(), center.getY() - vertical);
		int maxY = Math.min(level.getMaxY(), center.getY() + vertical);

		BlockGroups groups = new BlockGroups(center.getX(), center.getY(), center.getZ(), maxPositions);
		int unloaded = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
			for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
				if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
					unloaded += maxY - minY + 1;
					continue;
				}
				for (int y = minY; y <= maxY; y++) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (state.isAir() && !includeAir) {
						continue;
					}
					if (exposedOnly && !isExposed(level, pos)) {
						continue;
					}
					String name = blockName(state, properties);
					if (BlockGroups.matches(name, filter)) {
						groups.add(name, x, y, z);
						if (blockEntities && state.hasBlockEntity()) {
							blockEntityTotal++;
							blockEntityPositions.add(pos.immutable());
						}
					}
				}
			}
		}

		JsonObject o = new JsonObject();
		o.add("center", blockPosJson(center));
		JsonObject bounds = new JsonObject();
		bounds.add("min", xyz(center.getX() - radius, minY, center.getZ() - radius));
		bounds.add("max", xyz(center.getX() + radius, maxY, center.getZ() + radius));
		o.add("bounds", bounds);
		o.addProperty("blockCount", groups.total());
		o.addProperty("kinds", groups.kinds());
		o.addProperty("unloadedPositions", unloaded);
		o.add("standingOn", blockAt(level, center.below(), true));
		o.add("lookingAt", lookTarget(mc, player));
		o.add("blocks", groups.toJson());
		if (blockEntities) {
			blockEntityPositions.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
			List<BlockPos> read = blockEntityPositions.subList(0, Math.min(MAX_BLOCK_ENTITIES, blockEntityPositions.size()));
			o.add("blockEntities", blockEntitiesJson(mc, level, read));
			o.addProperty("blockEntityTotal", blockEntityTotal);
			o.addProperty("blockEntitySource", ServerData.source(mc));
		}
		return o;
	}

	/** The block entities at these positions (nearest first) with their full NBT as SNBT. */
	private static JsonArray blockEntitiesJson(Minecraft mc, ClientLevel level, List<BlockPos> positions) {
		Map<BlockPos, String> nbt = ServerData.blockEntityNbt(mc, positions);
		JsonArray list = new JsonArray();
		for (BlockPos p : positions) {
			JsonObject e = blockPosJson(p);
			e.addProperty("block", blockName(level.getBlockState(p), false));
			BlockEntity be = level.getBlockEntity(p);
			if (be != null) {
				e.addProperty("type", ServerData.blockEntityType(be));
			}
			e.addProperty("nbt", nbt.get(p));
			list.add(e);
		}
		return list;
	}

	/** True if at least one of the 6 neighbors does not fully hide this block (air, glass, water, ...). */
	private static boolean isExposed(ClientLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (!level.getBlockState(pos.relative(d)).canOcclude()) {
				return true;
			}
		}
		return false;
	}

	static String blockName(BlockState state, boolean withProperties) {
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		if (!withProperties || state.getProperties().isEmpty()) {
			return id;
		}
		String props = state.getValues()
				.map(v -> v.property().getName() + "=" + v.valueName())
				.collect(Collectors.joining(","));
		return id + "[" + props + "]";
	}

	private static JsonObject blockAt(ClientLevel level, BlockPos pos, boolean withProperties) {
		JsonObject o = blockPosJson(pos);
		o.addProperty("block", blockName(level.getBlockState(pos), withProperties));
		return o;
	}

	/** The block or entity under the crosshair, like the F3 "Targeted Block" line. */
	static JsonObject lookTarget(Minecraft mc, LocalPlayer player) {
		HitResult hit = mc.hitResult;
		JsonObject o = new JsonObject();
		if (hit == null || hit.getType() == HitResult.Type.MISS) {
			o.addProperty("type", "miss");
			return o;
		}
		if (hit instanceof BlockHitResult blockHit) {
			o.addProperty("type", "block");
			o.add("block", blockAt(mc.level, blockHit.getBlockPos(), true));
			o.addProperty("face", blockHit.getDirection().getName());
		} else if (hit instanceof EntityHitResult entityHit) {
			o.addProperty("type", "entity");
			o.add("entity", entityJson(entityHit.getEntity(), player));
		}
		o.addProperty("distance", round(hit.getLocation().distanceTo(player.getEyePosition())));
		return o;
	}

	// ---------------------------------------------------------------- entities

	private static JsonObject listEntities(Minecraft mc, JsonObject params) {
		LocalPlayer player = InventoryActions.requirePlayer(mc);
		double renderBlocks = mc.options.getEffectiveRenderDistance() * 16.0;
		double maxDistance = params.has("max_distance") && !params.get("max_distance").isJsonNull()
				? params.get("max_distance").getAsDouble() : renderBlocks;
		String filter = stringParam(params, "type");
		boolean includePlayers = boolParam(params, "include_players", true);
		int limit = Math.max(0, intParam(params, "limit", 100));
		boolean includeNbt = boolParam(params, "include_nbt", false);

		List<Entity> found = new ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == player || !e.isAlive()) {
				continue;
			}
			if (!includePlayers && e instanceof Player) {
				continue;
			}
			if (e.distanceTo(player) > maxDistance) {
				continue;
			}
			if (!BlockGroups.matches(entityTypeId(e), filter)) {
				continue;
			}
			found.add(e);
		}
		found.sort(Comparator.comparingDouble(e -> e.distanceTo(player)));

		Map<String, Integer> counts = new TreeMap<>();
		for (Entity e : found) {
			counts.merge(entityTypeId(e), 1, Integer::sum);
		}
		JsonObject countsJson = new JsonObject();
		counts.forEach(countsJson::addProperty);

		List<Entity> shown = found.subList(0, Math.min(limit, found.size()));
		Map<UUID, String> nbt = includeNbt ? ServerData.entityNbt(mc, shown) : Map.of();
		JsonArray list = new JsonArray();
		for (Entity e : shown) {
			JsonObject json = entityJson(e, player);
			if (includeNbt) {
				json.addProperty("nbt", nbt.get(e.getUUID()));
			}
			list.add(json);
		}

		JsonObject o = new JsonObject();
		o.addProperty("maxDistance", maxDistance);
		o.addProperty("renderDistanceBlocks", renderBlocks);
		o.addProperty("total", found.size());
		o.addProperty("returned", list.size());
		o.add("countsByType", countsJson);
		if (includeNbt) {
			o.addProperty("nbtSource", ServerData.source(mc));
		}
		o.add("entities", list);
		return o;
	}

	static String entityTypeId(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}

	private static JsonObject entityJson(Entity e, LocalPlayer player) {
		JsonObject o = new JsonObject();
		o.addProperty("id", e.getId());
		o.addProperty("type", entityTypeId(e));
		o.addProperty("category", e.getType().getCategory().name());
		o.addProperty("name", e.getName().getString());
		if (e.hasCustomName() && e.getCustomName() != null) {
			o.addProperty("customName", e.getCustomName().getString());
		}
		o.addProperty("x", round(e.getX()));
		o.addProperty("y", round(e.getY()));
		o.addProperty("z", round(e.getZ()));
		o.addProperty("distance", round(e.distanceTo(player)));
		if (e instanceof Player) {
			o.addProperty("isPlayer", true);
		}
		if (e instanceof LivingEntity living) {
			o.addProperty("health", living.getHealth());
			o.addProperty("maxHealth", living.getMaxHealth());
		}
		if (e instanceof ItemEntity item) {
			o.add("item", InventoryActions.stackJson(item.getItem()));
		}
		if (e.getVehicle() != null) {
			o.addProperty("vehicleId", e.getVehicle().getId());
		}
		return o;
	}

	// ---------------------------------------------------------------- players

	private static JsonObject listPlayers(Minecraft mc, JsonObject params) {
		LocalPlayer self = InventoryActions.requirePlayer(mc);
		ClientPacketListener connection = mc.getConnection();
		double maxDistance = params.has("max_distance") && !params.get("max_distance").isJsonNull()
				? params.get("max_distance").getAsDouble() : Double.MAX_VALUE;

		List<AbstractClientPlayer> nearby = new ArrayList<>();
		for (AbstractClientPlayer p : mc.level.players()) {
			if (p != self && p.distanceTo(self) <= maxDistance) {
				nearby.add(p);
			}
		}
		nearby.sort(Comparator.comparingDouble(p -> p.distanceTo(self)));

		Set<UUID> inRange = new HashSet<>();
		JsonArray players = new JsonArray();
		for (AbstractClientPlayer p : nearby) {
			inRange.add(p.getUUID());
			players.add(playerJson(p, self, connection == null ? null : connection.getPlayerInfo(p.getUUID())));
		}

		// Everyone on the tab list, also players too far away for the client to see.
		JsonArray online = new JsonArray();
		if (connection != null) {
			for (PlayerInfo info : connection.getOnlinePlayers()) {
				UUID id = info.getProfile().id();
				if (id.equals(self.getUUID())) {
					continue;
				}
				JsonObject t = new JsonObject();
				t.addProperty("name", info.getProfile().name());
				t.addProperty("uuid", id.toString());
				t.addProperty("gameMode", info.getGameMode() == null ? null : info.getGameMode().getName());
				t.addProperty("latencyMs", info.getLatency());
				t.addProperty("inRenderRange", inRange.contains(id));
				online.add(t);
			}
		}

		JsonObject o = new JsonObject();
		o.addProperty("self", self.getName().getString());
		o.addProperty("nearbyCount", players.size());
		o.add("nearby", players);
		o.addProperty("onlineCount", online.size());
		o.add("online", online);
		return o;
	}

	private static JsonObject playerJson(AbstractClientPlayer p, LocalPlayer self, PlayerInfo info) {
		JsonObject o = new JsonObject();
		o.addProperty("name", p.getName().getString());
		o.addProperty("uuid", p.getUUID().toString());
		o.addProperty("entityId", p.getId());
		o.addProperty("x", round(p.getX()));
		o.addProperty("y", round(p.getY()));
		o.addProperty("z", round(p.getZ()));
		o.addProperty("distance", round(p.distanceTo(self)));
		o.addProperty("yaw", round(p.getYRot()));
		o.addProperty("pitch", round(p.getXRot()));
		o.addProperty("health", p.getHealth());
		o.addProperty("maxHealth", p.getMaxHealth());
		o.addProperty("armor", p.getArmorValue());
		o.addProperty("sneaking", p.isCrouching());
		o.addProperty("sprinting", p.isSprinting());
		o.addProperty("swimming", p.isSwimming());
		o.addProperty("invisible", p.isInvisible());
		o.addProperty("onGround", p.onGround());
		o.add("mainHand", InventoryActions.stackJson(p.getItemBySlot(EquipmentSlot.MAINHAND)));
		o.add("offHand", InventoryActions.stackJson(p.getItemBySlot(EquipmentSlot.OFFHAND)));
		JsonObject armor = new JsonObject();
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			armor.add(slot.getName(), InventoryActions.stackJson(p.getItemBySlot(slot)));
		}
		o.add("equipment", armor);
		if (p.getVehicle() != null) {
			o.addProperty("riding", entityTypeId(p.getVehicle()));
		}
		if (info != null) {
			o.addProperty("gameMode", info.getGameMode() == null ? null : info.getGameMode().getName());
			o.addProperty("latencyMs", info.getLatency());
		}
		return o;
	}

	// ---------------------------------------------------------------- helpers

	private static JsonArray xyz(int x, int y, int z) {
		JsonArray a = new JsonArray();
		a.add(x);
		a.add(y);
		a.add(z);
		return a;
	}

	private static JsonObject blockPosJson(BlockPos pos) {
		JsonObject o = new JsonObject();
		o.addProperty("x", pos.getX());
		o.addProperty("y", pos.getY());
		o.addProperty("z", pos.getZ());
		return o;
	}

	static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
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
