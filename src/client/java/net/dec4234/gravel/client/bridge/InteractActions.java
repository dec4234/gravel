package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dec4234.gravel.client.mixin.MinecraftInvoker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.concurrent.CompletableFuture;

/**
 * Left and right click on what the crosshair targets: attack (hit an entity, break a block) and use (use the held
 * item, place a block, open a door or chest, interact with an entity). The first click goes through the same code
 * as a mouse click. To hold the button, use holds the real key binding and attack sets a flag that
 * {@code MinecraftMixin} reads, because the game only continues to break a block while the mouse is grabbed.
 */
public final class InteractActions {
	/** Ticks to wait after the button is released, so the server can send back the result (health, block). */
	private static final int SETTLE_TICKS = 3;

	/** True while the attack action holds the attack button. Only the client thread changes it. */
	private static volatile boolean attackHolding;

	private InteractActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.registerAsync("attack", InteractActions::attack);
		registry.registerAsync("use", InteractActions::use);
	}

	/** Read by MinecraftMixin each tick: true makes the game continue to break the target block. */
	public static boolean attackHeld(Minecraft mc) {
		return attackHolding && mc.gui.screen() == null;
	}

	// ---------------------------------------------------------------- attack

	private static CompletableFuture<JsonObject> attack(Minecraft mc, JsonObject params) {
		LocalPlayer player = MovementActions.requireControl(mc);
		Integer holdMs = holdMs(params);
		HitResult hit = mc.hitResult;

		JsonObject result = new JsonObject();
		result.add("target", SurroundingsActions.lookTarget(mc, player));
		result.add("mainHand", InventoryActions.stackJson(player.getMainHandItem()));
		result.addProperty("attackStrength", SurroundingsActions.round(player.getAttackStrengthScale(0.5f)));

		BlockPos pos = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
		BlockState blockBefore = pos == null ? null : mc.level.getBlockState(pos);
		Entity entity = hit instanceof EntityHitResult e ? e.getEntity() : null;
		Float healthBefore = entity instanceof LivingEntity living ? living.getHealth() : null;

		((MinecraftInvoker) mc).gravel$startAttack();
		// With no hold_ms, a block that did not break at once is held until it breaks. Else it is one click.
		boolean untilBroken = holdMs == null && pos != null && mc.level.getBlockState(pos) == blockBefore;
		int limitMs = holdMs != null ? holdMs : untilBroken ? MovementActions.MAX_MOVE_MS : 0;
		attackHolding = limitMs > 0;
		int[] stage = {-1};
		int[] settle = {-1};
		long t0 = System.nanoTime();
		return MovementActions.everyTick(mc, () -> attackHolding = false, () -> {
			long elapsed = (System.nanoTime() - t0) / 1_000_000;
			LocalPlayer now = mc.player;
			if (settle[0] < 0) {
				if (now == player && mc.gameMode != null && mc.gameMode.isDestroying()) {
					stage[0] = Math.max(stage[0], mc.gameMode.getDestroyStage());
				}
				String stop = stopReason(mc, player, elapsed, limitMs);
				if (stop == null && untilBroken && mc.level.getBlockState(pos) != blockBefore) {
					stop = "broken";
				}
				if (stop == null) {
					return null;
				}
				attackHolding = false;
				result.addProperty("stoppedBy", stop);
				result.addProperty("heldMs", elapsed);
				if (stop.equals("left_world") || stop.equals("screen_opened")) {
					return finish(mc, result);
				}
			}
			if (++settle[0] < SETTLE_TICKS) {
				return null;
			}
			if (pos != null) {
				BlockState after = mc.level.getBlockState(pos);
				JsonObject block = blockJson(pos, blockBefore);
				block.addProperty("after", SurroundingsActions.blockName(after, true));
				block.addProperty("broken", after != blockBefore && after.isAir());
				block.addProperty("changed", after != blockBefore);
				block.addProperty("highestDestroyStage", stage[0]);
				result.add("block", block);
			}
			if (entity != null) {
				result.add("entity", entityJson(entity, healthBefore));
			}
			return finish(mc, result);
		});
	}

	// ---------------------------------------------------------------- use

	private static CompletableFuture<JsonObject> use(Minecraft mc, JsonObject params) {
		LocalPlayer player = MovementActions.requireControl(mc);
		Integer holdMs = holdMs(params);
		HitResult hit = mc.hitResult;
		ClientLevel level = mc.level;

		JsonObject result = new JsonObject();
		result.add("target", SurroundingsActions.lookTarget(mc, player));
		ItemStack mainBefore = player.getMainHandItem().copy();
		ItemStack offBefore = player.getOffhandItem().copy();

		BlockPos pos = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
		BlockPos front = pos == null ? null : pos.relative(((BlockHitResult) hit).getDirection());
		BlockState posBefore = pos == null ? null : level.getBlockState(pos);
		BlockState frontBefore = front == null ? null : level.getBlockState(front);
		Entity entity = hit instanceof EntityHitResult e ? e.getEntity() : null;
		Float healthBefore = entity instanceof LivingEntity living ? living.getHealth() : null;

		KeyMapping key = mc.options.keyUse;
		((MinecraftInvoker) mc).gravel$startUseItem();
		// Hold the use key so the game continues to use the item (eat, draw a bow, block with a shield). A tap
		// releases it after one tick. While the key is down and no item is in use, the game uses again every 4 ticks.
		MovementActions.hold(key, true);
		int limitMs = holdMs == null ? 0 : holdMs;
		boolean[] held = {true};
		boolean[] usedItem = {player.isUsingItem()};
		String[] usingItem = {usedItem[0] ? itemId(player.getUseItem()) : null};
		int[] settle = {-1};
		long t0 = System.nanoTime();
		return MovementActions.everyTick(mc, () -> MovementActions.hold(key, false), () -> {
			long elapsed = (System.nanoTime() - t0) / 1_000_000;
			LocalPlayer now = mc.player;
			if (held[0]) {
				if (now == player && now.isUsingItem()) {
					usedItem[0] = true;
					usingItem[0] = itemId(now.getUseItem());
				}
				String stop = stopReason(mc, player, elapsed, limitMs);
				if (stop == null) {
					return null;
				}
				MovementActions.hold(key, false);
				held[0] = false;
				result.addProperty("stoppedBy", stop);
				result.addProperty("heldMs", elapsed);
				if (stop.equals("left_world") || stop.equals("screen_opened")) {
					return finish(mc, result);
				}
			}
			if (++settle[0] < SETTLE_TICKS) {
				return null;
			}
			result.addProperty("usedItem", usedItem[0]);
			if (usingItem[0] != null) {
				result.addProperty("usingItem", usingItem[0]);
			}
			result.addProperty("stillUsing", player.isUsingItem());
			result.add("mainHand", handJson(mainBefore, player.getItemInHand(InteractionHand.MAIN_HAND)));
			result.add("offHand", handJson(offBefore, player.getItemInHand(InteractionHand.OFF_HAND)));
			if (pos != null) {
				JsonArray changed = new JsonArray();
				addIfChanged(changed, level, pos, posBefore, "target");
				addIfChanged(changed, level, front, frontBefore, "front");
				result.add("blockChanges", changed);
			}
			if (entity != null) {
				result.add("entity", entityJson(entity, healthBefore));
			}
			return finish(mc, result);
		});
	}

	// ---------------------------------------------------------------- helpers

	private static Integer holdMs(JsonObject params) {
		if (!params.has("hold_ms") || params.get("hold_ms").isJsonNull()) {
			return null;
		}
		int ms = params.get("hold_ms").getAsInt();
		if (ms < 0 || ms > MovementActions.MAX_MOVE_MS) {
			throw new IllegalArgumentException("hold_ms must be 0-" + MovementActions.MAX_MOVE_MS);
		}
		return ms;
	}

	/** Why the button must be released now, or null to keep it down. */
	private static String stopReason(Minecraft mc, LocalPlayer player, long elapsedMs, int limitMs) {
		if (mc.player != player) {
			return "left_world";
		}
		if (mc.gui.screen() != null) {
			return "screen_opened";
		}
		return elapsedMs >= limitMs ? "time" : null;
	}

	private static JsonObject finish(Minecraft mc, JsonObject result) {
		LocalPlayer now = mc.player;
		if (now != null) {
			result.add("targetAfter", SurroundingsActions.lookTarget(mc, now));
		}
		Screen screen = mc.gui.screen();
		result.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
		return result;
	}

	private static JsonObject blockJson(BlockPos pos, BlockState before) {
		JsonObject o = new JsonObject();
		o.addProperty("x", pos.getX());
		o.addProperty("y", pos.getY());
		o.addProperty("z", pos.getZ());
		o.addProperty("before", SurroundingsActions.blockName(before, true));
		return o;
	}

	private static void addIfChanged(JsonArray out, ClientLevel level, BlockPos pos, BlockState before, String which) {
		BlockState after = level.getBlockState(pos);
		if (after != before) {
			JsonObject o = blockJson(pos, before);
			o.addProperty("which", which);
			o.addProperty("after", SurroundingsActions.blockName(after, true));
			out.add(o);
		}
	}

	private static JsonObject entityJson(Entity entity, Float healthBefore) {
		JsonObject o = new JsonObject();
		o.addProperty("id", entity.getId());
		o.addProperty("type", SurroundingsActions.entityTypeId(entity));
		o.addProperty("alive", entity.isAlive());
		if (healthBefore != null && entity instanceof LivingEntity living) {
			o.addProperty("healthBefore", healthBefore);
			o.addProperty("healthAfter", living.getHealth());
		}
		return o;
	}

	private static JsonObject handJson(ItemStack before, ItemStack after) {
		JsonObject o = InventoryActions.stackJson(after);
		o.add("before", InventoryActions.stackJson(before));
		o.addProperty("changed", !ItemStack.matches(before, after));
		return o;
	}

	private static String itemId(ItemStack stack) {
		JsonObject o = InventoryActions.stackJson(stack);
		return o.get("item").isJsonNull() ? null : o.get("item").getAsString();
	}
}
