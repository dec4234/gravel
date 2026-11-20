package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.sdl.SDLKeyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Game input: key presses (keyboard keys and key bindings) and text typed into the text boxes of the open screen.
 * The input goes through the same handlers that the real keyboard uses.
 */
public final class InputActions {
	/** Longest time a key can stay down in one call. The bridge waits for the release. */
	static final int MAX_HOLD_MS = 10_000;
	private static final int DEFAULT_TAP_MS = 50;

	/** Short names for keys whose options.txt names are long. */
	private static final Map<String, String> ALIASES = Map.ofEntries(
			Map.entry("esc", "escape"),
			Map.entry("return", "enter"),
			Map.entry("shift", "left.shift"),
			Map.entry("lshift", "left.shift"),
			Map.entry("rshift", "right.shift"),
			Map.entry("ctrl", "left.control"),
			Map.entry("control", "left.control"),
			Map.entry("lctrl", "left.control"),
			Map.entry("rctrl", "right.control"),
			Map.entry("alt", "left.alt"),
			Map.entry("lalt", "left.alt"),
			Map.entry("ralt", "right.alt"),
			Map.entry("super", "left.win"),
			Map.entry("win", "left.win"),
			Map.entry("arrow_up", "up"),
			Map.entry("arrow_down", "down"),
			Map.entry("arrow_left", "left"),
			Map.entry("arrow_right", "right"),
			Map.entry("pgup", "page.up"),
			Map.entry("pgdn", "page.down"),
			Map.entry("del", "delete"),
			Map.entry("lmb", "key.mouse.left"),
			Map.entry("rmb", "key.mouse.right"),
			Map.entry("mmb", "key.mouse.middle"));

	private InputActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.registerAsync("press_key", InputActions::pressKey);
		registry.register("enter_text", InputActions::enterText);
	}

	// ---------------------------------------------------------------- press_key

	private static CompletableFuture<JsonObject> pressKey(Minecraft mc, JsonObject params) {
		String name = params.has("key") ? params.get("key").getAsString().trim() : "";
		if (name.isEmpty()) {
			throw new IllegalArgumentException("Parameter 'key' is required");
		}
		String action = params.has("action") ? params.get("action").getAsString().toLowerCase(Locale.ROOT) : "tap";
		if (!action.equals("tap") && !action.equals("press") && !action.equals("release")) {
			throw new IllegalArgumentException("action must be tap, press or release, not '" + action + "'");
		}
		int holdMs = params.has("hold_ms") ? params.get("hold_ms").getAsInt() : DEFAULT_TAP_MS;
		if (holdMs < 0 || holdMs > MAX_HOLD_MS) {
			throw new IllegalArgumentException("hold_ms must be 0-" + MAX_HOLD_MS);
		}
		int modifiers = modifiers(params.get("modifiers"));
		InputConstants.Key key = resolveKey(name);

		Screen screen = mc.gui.screen();
		JsonObject result = new JsonObject();
		result.addProperty("key", key.getName());
		result.addProperty("keyDisplay", key.getDisplayName().getString());
		result.addProperty("action", action);
		result.addProperty("screenBefore", screen == null ? null : screen.getClass().getSimpleName());

		if (action.equals("release")) {
			send(mc, key, false, modifiers);
			return CompletableFuture.completedFuture(finish(mc, result));
		}
		send(mc, key, true, modifiers);
		if (action.equals("press")) {
			return CompletableFuture.completedFuture(finish(mc, result));
		}
		result.addProperty("heldMs", holdMs);
		// Release later on the client thread, so the game sees the key down for at least one tick.
		CompletableFuture<JsonObject> done = new CompletableFuture<>();
		CompletableFuture.delayedExecutor(holdMs, TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
			try {
				send(mc, key, false, modifiers);
				done.complete(finish(mc, result));
			} catch (RuntimeException e) {
				done.completeExceptionally(e);
			}
		}));
		return done;
	}

	private static JsonObject finish(Minecraft mc, JsonObject result) {
		Screen now = mc.gui.screen();
		result.addProperty("screen", now == null ? null : now.getClass().getSimpleName());
		return result;
	}

	/** Sends one key down or up event, the same way the window does for a real key. */
	private static void send(Minecraft mc, InputConstants.Key key, boolean down, int modifiers) {
		if (key.getType() == InputConstants.Type.MOUSE) {
			// Mouse buttons only act through their key bindings (attack, use, pick block), as in game with no screen.
			KeyMapping.set(key, down);
			if (down) {
				KeyMapping.click(key);
			}
			return;
		}
		int scancode = key.getValue();
		int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) modifiers, true);
		mc.keyboardHandler.keyPress(mc.getWindow().handle(),
				down ? InputConstants.PRESS : InputConstants.RELEASE, new KeyEvent(scancode, keycode, modifiers));
	}

	/**
	 * Finds a key from a name: an options.txt key name (key.keyboard.w, key.mouse.left), a key binding
	 * (key.jump, jump, inventory), or a short keyboard name (w, space, f3, escape, left.shift, shift).
	 */
	static InputConstants.Key resolveKey(String raw) {
		String name = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '.');
		name = ALIASES.getOrDefault(name, name);
		if (name.startsWith("key.keyboard.") || name.startsWith("key.mouse.")) {
			return knownKey(name, raw);
		}
		InputConstants.Key keyboard = tryKey("key.keyboard." + name);
		if (keyboard != null) {
			return keyboard;
		}
		KeyMapping binding = KeyMapping.get(name.startsWith("key.") ? name : "key." + name);
		if (binding != null) {
			if (binding.isUnbound()) {
				throw new IllegalArgumentException("Key binding '" + binding.getName() + "' has no key");
			}
			return InputConstants.getKey(binding.saveString());
		}
		throw new IllegalArgumentException("Unknown key '" + raw + "'. Use a name such as w, space, escape, f3, "
				+ "left.shift, enter, key.keyboard.e, key.mouse.left, or a key binding such as jump or key.inventory.");
	}

	private static InputConstants.Key knownKey(String name, String raw) {
		InputConstants.Key key = tryKey(name);
		if (key == null) {
			throw new IllegalArgumentException("Unknown key '" + raw + "'");
		}
		return key;
	}

	private static InputConstants.Key tryKey(String name) {
		try {
			InputConstants.Key key = InputConstants.getKey(name);
			return key == null || key.equals(InputConstants.UNKNOWN) ? null : key;
		} catch (RuntimeException e) {
			return null;
		}
	}

	static int modifiers(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return 0;
		}
		List<String> names = new ArrayList<>();
		if (value.isJsonArray()) {
			value.getAsJsonArray().forEach(e -> names.add(e.getAsString()));
		} else {
			for (String part : value.getAsString().split("[,+ ]+")) {
				names.add(part);
			}
		}
		int mods = 0;
		for (String n : names) {
			switch (n.trim().toLowerCase(Locale.ROOT)) {
				case "" -> {
				}
				case "shift" -> mods |= InputConstants.MOD_SHIFT;
				case "ctrl", "control" -> mods |= InputConstants.MOD_CONTROL;
				case "alt" -> mods |= InputConstants.MOD_ALT;
				case "super", "win", "cmd" -> mods |= InputConstants.MOD_SUPER;
				default -> throw new IllegalArgumentException("Unknown modifier '" + n + "'. Use shift, ctrl, alt or super.");
			}
		}
		return mods;
	}

	// ---------------------------------------------------------------- enter_text

	private static JsonObject enterText(Minecraft mc, JsonObject params) {
		Screen screen = mc.gui.screen();
		if (screen == null) {
			throw new IllegalStateException("No screen is open, so there is no text box. Open a screen first.");
		}
		List<AbstractWidget> fields = new ArrayList<>();
		collectFields(screen, fields);

		String text = params.has("text") && !params.get("text").isJsonNull() ? params.get("text").getAsString() : null;
		boolean listOnly = text == null;
		boolean clear = params.has("clear") && params.get("clear").getAsBoolean();
		boolean submit = params.has("submit") && params.get("submit").getAsBoolean();
		String mode = params.has("mode") ? params.get("mode").getAsString().toLowerCase(Locale.ROOT) : "type";
		if (!mode.equals("type") && !mode.equals("set")) {
			throw new IllegalArgumentException("mode must be type or set, not '" + mode + "'");
		}

		AbstractWidget target = null;
		if (params.has("field") && !params.get("field").isJsonNull()) {
			int index = params.get("field").getAsInt();
			if (index < 0 || index >= fields.size()) {
				throw new IllegalArgumentException("field " + index + " does not exist. This screen has "
						+ fields.size() + " text box(es): " + describe(fields));
			}
			target = fields.get(index);
			if (!listOnly) {
				focus(screen, target);
			}
		} else {
			for (AbstractWidget f : fields) {
				if (f.isFocused()) {
					target = f;
					break;
				}
			}
			if (target == null && fields.size() == 1) {
				target = fields.get(0);
				if (!listOnly) {
					focus(screen, target);
				}
			}
		}

		JsonObject o = new JsonObject();
		o.addProperty("screen", screen.getClass().getSimpleName());
		if (!listOnly) {
			if (mode.equals("set")) {
				if (target == null) {
					throw new IllegalStateException(noTarget(fields, screen));
				}
				setValue(target, text);
			} else {
				if (target == null && !fields.isEmpty()) {
					throw new IllegalStateException(noTarget(fields, screen));
				}
				if (clear && target != null) {
					setValue(target, "");
				}
				// Typed text goes to the focused widget of the screen, like real keyboard text input. Screens with
				// their own text input (signs, books) get the text too, also when they have no text box widget.
				mc.keyboardHandler.textInput(mc.getWindow().handle(), text);
			}
			o.addProperty("entered", text);
			o.addProperty("mode", mode);
			if (target != null) {
				o.addProperty("field", fields.indexOf(target));
				o.addProperty("value", valueOf(target));
			}
			if (submit) {
				InputConstants.Key enter = InputConstants.getKey("key.keyboard.enter");
				send(mc, enter, true, 0);
				send(mc, enter, false, 0);
				o.addProperty("submitted", true);
				Screen now = mc.gui.screen();
				o.addProperty("screenAfter", now == null ? null : now.getClass().getSimpleName());
			}
		}
		// After submit the screen can close or change. Then the old text boxes are gone.
		o.add("fields", mc.gui.screen() == screen ? describe(fields) : new JsonArray());
		return o;
	}

	private static String noTarget(List<AbstractWidget> fields, Screen screen) {
		if (fields.isEmpty()) {
			return "Screen " + screen.getClass().getSimpleName() + " has no text box.";
		}
		return "No text box is focused. Pass field (0-" + (fields.size() - 1) + "). Text boxes: " + describe(fields);
	}

	/** Finds the text boxes in the screen, also inside lists and other containers, in screen order. */
	private static void collectFields(ContainerEventHandler parent, List<AbstractWidget> out) {
		for (GuiEventListener child : parent.children()) {
			if (child instanceof EditBox || child instanceof MultiLineEditBox) {
				out.add((AbstractWidget) child);
			} else if (child instanceof ContainerEventHandler container) {
				collectFields(container, out);
			}
		}
	}

	/** Focuses a text box like a click does. Boxes inside containers get the focus path through their parents. */
	static void focus(Screen screen, GuiEventListener field) {
		if (!focusIn(screen, field)) {
			screen.setFocused(field);
		}
	}

	private static boolean focusIn(ContainerEventHandler parent, GuiEventListener field) {
		for (GuiEventListener child : parent.children()) {
			if (child == field) {
				parent.setFocused(field);
				return true;
			}
			if (child instanceof ContainerEventHandler container && focusIn(container, field)) {
				parent.setFocused(child);
				return true;
			}
		}
		return false;
	}

	private static void setValue(AbstractWidget field, String value) {
		if (field instanceof EditBox box) {
			box.setValue(value);
		} else if (field instanceof MultiLineEditBox box) {
			box.setValue(value);
		}
	}

	private static String valueOf(AbstractWidget field) {
		if (field instanceof EditBox box) {
			return box.getValue();
		}
		if (field instanceof MultiLineEditBox box) {
			return box.getValue();
		}
		return null;
	}

	private static JsonArray describe(List<AbstractWidget> fields) {
		JsonArray list = new JsonArray();
		for (int i = 0; i < fields.size(); i++) {
			AbstractWidget f = fields.get(i);
			JsonObject o = new JsonObject();
			o.addProperty("field", i);
			o.addProperty("type", f instanceof MultiLineEditBox ? "multi_line" : "single_line");
			o.addProperty("label", f.getMessage().getString());
			o.addProperty("value", valueOf(f));
			o.addProperty("focused", f.isFocused());
			o.addProperty("visible", f.visible);
			o.addProperty("active", f.isActive());
			if (f instanceof EditBox box) {
				o.addProperty("editable", box.canConsumeInput());
			}
			o.addProperty("x", f.getX());
			o.addProperty("y", f.getY());
			o.addProperty("width", f.getWidth());
			o.addProperty("height", f.getHeight());
			list.add(o);
		}
		return list;
	}
}
