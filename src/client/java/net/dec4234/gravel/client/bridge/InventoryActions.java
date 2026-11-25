package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dec4234.gravel.client.mixin.AbstractContainerScreenInvoker;
import net.dec4234.gravel.client.mixin.CreativeModeInventoryScreenInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Hotbar and inventory actions. Slot clicks go through the open screen, the same path as a real
 * mouse click, so the server and the creative inventory see normal player input.
 *
 * <p>Slot numbers are menu slot indexes. For the player inventory (survival and the creative
 * "Survival Inventory" tab) they are: 0 crafting result, 1-4 crafting grid, 5-8 armor
 * (head to feet), 9-35 main inventory, 36-44 hotbar, 45 offhand.
 */
public final class InventoryActions {
	/** The slot id that vanilla uses for a click outside the window (drops the carried item). */
	private static final int OUTSIDE = -999;

	private InventoryActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("select_hotbar_slot", InventoryActions::selectHotbarSlot);
		registry.register("open_inventory", InventoryActions::openInventory);
		registry.register("read_inventory", InventoryActions::readInventory);
		registry.register("click_slot", InventoryActions::clickSlot);
		registry.register("move_item", InventoryActions::moveItem);
	}

	private static JsonObject selectHotbarSlot(Minecraft mc, JsonObject params) {
		LocalPlayer player = requirePlayer(mc);
		if (!params.has("slot")) {
			throw new IllegalArgumentException("Parameter 'slot' is required (0-8)");
		}
		int slot = params.get("slot").getAsInt();
		if (slot < 0 || slot >= Inventory.getSelectionSize()) {
			throw new IllegalArgumentException("Hotbar slot must be 0-" + (Inventory.getSelectionSize() - 1) + ", got " + slot);
		}
		// Same as pressing a number key. The game sends the new slot to the server on its next tick.
		player.getInventory().setSelectedSlot(slot);
		JsonObject o = new JsonObject();
		o.addProperty("selectedSlot", slot);
		o.add("item", stackJson(player.getInventory().getSelectedItem()));
		return o;
	}

	private static JsonObject openInventory(Minecraft mc, JsonObject params) {
		LocalPlayer player = requirePlayer(mc);
		// Same steps as the inventory key (E).
		if (mc.gameMode != null && mc.gameMode.isServerControlledInventory()) {
			player.sendOpenInventory();
		} else {
			mc.getTutorial().onOpenInventory();
			// In creative mode, InventoryScreen opens the creative inventory instead.
			mc.gui.setScreen(new InventoryScreen(player));
		}
		showCreativeInventoryTab(mc.gui.screen());

		JsonObject o = new JsonObject();
		Screen screen = mc.gui.screen();
		o.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
		o.addProperty("creative", screen instanceof CreativeModeInventoryScreen);
		return o;
	}

	private static JsonObject readInventory(Minecraft mc, JsonObject params) {
		LocalPlayer player = requirePlayer(mc);
		boolean includeEmpty = params.has("include_empty") && params.get("include_empty").getAsBoolean();
		boolean components = params.has("include_components") && params.get("include_components").getAsBoolean();
		boolean tooltip = params.has("include_tooltip") && params.get("include_tooltip").getAsBoolean();

		Screen screen = mc.gui.screen();
		AbstractContainerMenu menu;
		if (screen instanceof AbstractContainerScreen<?> containerScreen) {
			showCreativeInventoryTab(screen);
			menu = containerScreen.getMenu();
		} else {
			// No container open: show the player inventory with the same slot numbers the inventory screen uses.
			menu = player.inventoryMenu;
		}

		JsonObject o = new JsonObject();
		o.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
		o.addProperty("menu", menu.getClass().getSimpleName());
		o.addProperty("containerOpen", screen instanceof AbstractContainerScreen<?>);
		o.addProperty("slotCount", menu.slots.size());
		o.addProperty("selectedHotbarSlot", player.getInventory().getSelectedSlot());
		o.add("carried", stackJson(mc, menu.getCarried(), components, tooltip));
		JsonArray slots = new JsonArray();
		// Use the list position, not Slot.index: the creative inventory's slot copies do not set their index.
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			ItemStack stack = slot.getItem();
			if (stack.isEmpty() && !includeEmpty) {
				continue;
			}
			JsonObject s = stackJson(mc, stack, components, tooltip);
			s.addProperty("slot", i);
			s.addProperty("playerInventory", slot.container instanceof Inventory);
			s.addProperty("containerSlot", slot.getContainerSlot());
			slots.add(s);
		}
		o.add("slots", slots);
		return o;
	}

	private static JsonObject clickSlot(Minecraft mc, JsonObject params) {
		requirePlayer(mc);
		AbstractContainerScreen<?> screen = requireContainerScreen(mc);
		if (!params.has("slot")) {
			throw new IllegalArgumentException("Parameter 'slot' is required (use -999 to click outside the window)");
		}
		int slotId = params.get("slot").getAsInt();
		int button = params.has("button") ? params.get("button").getAsInt() : 0;
		ContainerInput input = parseInput(params.has("action") ? params.get("action").getAsString() : "PICKUP");

		click(screen, slotId, button, input);

		JsonObject o = new JsonObject();
		o.addProperty("slot", slotId);
		o.addProperty("button", button);
		o.addProperty("action", input.name());
		if (slotId != OUTSIDE) {
			o.add("slotNow", stackJson(screen.getMenu().getSlot(slotId).getItem()));
		}
		o.add("carried", stackJson(screen.getMenu().getCarried()));
		return o;
	}

	private static JsonObject moveItem(Minecraft mc, JsonObject params) {
		requirePlayer(mc);
		AbstractContainerScreen<?> screen = requireContainerScreen(mc);
		AbstractContainerMenu menu = screen.getMenu();
		if (!params.has("from") || !params.has("to")) {
			throw new IllegalArgumentException("Parameters 'from' and 'to' are required");
		}
		int from = params.get("from").getAsInt();
		int to = params.get("to").getAsInt();
		checkSlot(menu, from);
		checkSlot(menu, to);
		if (from == to) {
			throw new IllegalArgumentException("'from' and 'to' are the same slot");
		}
		if (!menu.getCarried().isEmpty()) {
			throw new IllegalStateException("The cursor already holds " + describe(menu.getCarried())
					+ ". Put it down first (inventory click).");
		}
		ItemStack source = menu.getSlot(from).getItem();
		if (source.isEmpty()) {
			throw new IllegalArgumentException("Slot " + from + " is empty");
		}
		int count = params.has("count") ? params.get("count").getAsInt() : source.getCount();
		if (count < 1) {
			throw new IllegalArgumentException("'count' must be 1 or more");
		}

		// Pick up the whole stack, put down all of it or one item per right click, then put back the rest.
		click(screen, from, 0, ContainerInput.PICKUP);
		if (count >= menu.getCarried().getCount()) {
			click(screen, to, 0, ContainerInput.PICKUP);
		} else {
			for (int i = 0; i < count && !menu.getCarried().isEmpty(); i++) {
				click(screen, to, 1, ContainerInput.PICKUP);
			}
		}
		if (!menu.getCarried().isEmpty()) {
			click(screen, from, 0, ContainerInput.PICKUP);
		}

		JsonObject o = new JsonObject();
		o.add("from", stackJson(menu.getSlot(from).getItem()));
		o.add("to", stackJson(menu.getSlot(to).getItem()));
		o.add("carried", stackJson(menu.getCarried()));
		return o;
	}

	// ---------------------------------------------------------------- helpers

	private static void click(AbstractContainerScreen<?> screen, int slotId, int button, ContainerInput input) {
		Slot slot = null;
		if (slotId != OUTSIDE) {
			checkSlot(screen.getMenu(), slotId);
			slot = screen.getMenu().getSlot(slotId);
		}
		((AbstractContainerScreenInvoker) screen).gravel$slotClicked(slot, slotId, button, input);
	}

	private static void checkSlot(AbstractContainerMenu menu, int slotId) {
		if (slotId < 0 || slotId >= menu.slots.size()) {
			throw new IllegalArgumentException("Slot " + slotId + " is not in this menu (0-" + (menu.slots.size() - 1) + ")");
		}
	}

	private static ContainerInput parseInput(String name) {
		try {
			return ContainerInput.valueOf(name.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown action '" + name + "'. Use one of: PICKUP, QUICK_MOVE, SWAP, "
					+ "CLONE, THROW, QUICK_CRAFT, PICKUP_ALL");
		}
	}

	/**
	 * The creative inventory shows other slots on each tab. Show the "Survival Inventory" tab so the
	 * slot numbers are the same as in survival.
	 */
	private static void showCreativeInventoryTab(Screen screen) {
		if (screen instanceof CreativeModeInventoryScreen creative && !creative.isInventoryOpen()) {
			for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
				if (tab.getType() == CreativeModeTab.Type.INVENTORY) {
					((CreativeModeInventoryScreenInvoker) creative).gravel$selectTab(tab);
					return;
				}
			}
		}
	}

	private static AbstractContainerScreen<?> requireContainerScreen(Minecraft mc) {
		if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
			throw new IllegalStateException("No inventory or container screen is open. Use screen with action open and name inventory first.");
		}
		showCreativeInventoryTab(screen);
		return screen;
	}

	static JsonObject stackJson(ItemStack stack) {
		JsonObject o = new JsonObject();
		if (stack.isEmpty()) {
			o.addProperty("item", (String) null);
			o.addProperty("count", 0);
			return o;
		}
		o.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		o.addProperty("count", stack.getCount());
		o.addProperty("name", stack.getHoverName().getString());
		return o;
	}

	/**
	 * Like {@link #stackJson(ItemStack)}, with more details on request: all data components (with the ids
	 * that differ from the item's defaults), and the tooltip lines as the game shows them, with the lines
	 * that mods add.
	 */
	static JsonObject stackJson(Minecraft mc, ItemStack stack, boolean components, boolean tooltip) {
		JsonObject o = stackJson(stack);
		if (stack.isEmpty()) {
			return o;
		}
		if (components) {
			o.add("components", ServerData.componentsJson(stack.getComponents(), mc.level.registryAccess()));
			List<List<String>> patch = ServerData.patchIds(stack.getComponentsPatch());
			o.add("changedComponents", stringArray(patch.get(0)));
			o.add("removedComponents", stringArray(patch.get(1)));
		}
		if (tooltip) {
			JsonArray lines = new JsonArray();
			TooltipFlag flag = mc.options.advancedItemTooltips ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL;
			for (Component line : stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, flag)) {
				lines.add(line.getString());
			}
			o.add("tooltip", lines);
		}
		return o;
	}

	private static JsonArray stringArray(List<String> values) {
		JsonArray a = new JsonArray();
		values.forEach(a::add);
		return a;
	}

	private static String describe(ItemStack stack) {
		return stack.getCount() + "x " + BuiltInRegistries.ITEM.getKey(stack.getItem());
	}

	static LocalPlayer requirePlayer(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			throw new IllegalStateException("Not in a world. Join a world first.");
		}
		return mc.player;
	}
}
