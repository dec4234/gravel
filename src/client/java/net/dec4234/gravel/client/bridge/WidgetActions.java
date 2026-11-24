package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.dec4234.gravel.client.mixin.AbstractSliderButtonAccessor;
import net.dec4234.gravel.client.mixin.AbstractWidgetAccessor;
import net.dec4234.gravel.client.mixin.ScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractContainerWidget;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractStringWidget;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.LockIconButton;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Reads the widgets of the open screen (buttons, sliders, cycle buttons, check boxes, text boxes, tabs, lists and
 * their entries, text labels) and acts on them. Clicks go through the screen the same way a real mouse click does,
 * so each screen runs its own code. With these two actions, any menu can be used.
 */
public final class WidgetActions {
	/** Most clicks on a cycle button to find a value. */
	private static final int MAX_CYCLE_CLICKS = 256;
	/** Steps tried on a slider to find a value by its text. */
	private static final int SLIDER_STEPS = 1000;
	/** Width of the slider handle. The handle center moves from 4 to width - 4. */
	private static final int SLIDER_HANDLE = 8;

	private WidgetActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("read_screen", WidgetActions::readScreen);
		registry.register("interact_widget", WidgetActions::interactWidget);
	}

	// ---------------------------------------------------------------- tree

	/** One element of the screen tree. The index stays the same between calls while the screen is the same. */
	private record Node(int index, GuiEventListener element, Node parent, List<AbstractScrollArea> scrollAreas,
						AbstractSelectionList<?> ownerList) {
	}

	/** All elements of the screen, parents before their children, in the order the screen holds them. */
	private static List<Node> walk(Screen screen) {
		List<Node> out = new ArrayList<>();
		Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (GuiEventListener child : List.copyOf(screen.children())) {
			visit(child, null, List.of(), null, out, seen);
		}
		// Some widgets (text labels) are only drawn, so they are not in the children.
		for (Renderable r : List.copyOf(((ScreenAccessor) screen).gravel$getRenderables())) {
			if (r instanceof AbstractWidget w) {
				visit(w, null, List.of(), null, out, seen);
			}
		}
		return out;
	}

	private static void visit(GuiEventListener element, Node parent, List<AbstractScrollArea> scrollAreas,
							  AbstractSelectionList<?> ownerList, List<Node> out, Set<Object> seen) {
		if (!seen.add(element)) {
			return;
		}
		Node node = new Node(out.size(), element, parent, scrollAreas, ownerList);
		out.add(node);
		if (element instanceof ContainerEventHandler container) {
			List<AbstractScrollArea> inner = scrollAreas;
			if (element instanceof AbstractScrollArea area) {
				inner = new ArrayList<>(scrollAreas);
				inner.add(area);
			}
			AbstractSelectionList<?> owner = element instanceof AbstractSelectionList<?> list ? list : null;
			for (GuiEventListener child : List.copyOf(container.children())) {
				visit(child, node, inner, owner, out, seen);
			}
		}
	}

	/** Entries are the direct children of a list. Their class is not public, so the tree tells them apart. */
	private static String type(Node node) {
		return node.ownerList() != null ? "list_entry" : type(node.element());
	}

	private static String type(GuiEventListener e) {
		if (e instanceof EditBox) return "text_box";
		if (e instanceof MultiLineEditBox) return "multi_line_text_box";
		if (e instanceof CycleButton<?>) return "cycle_button";
		if (e instanceof Checkbox) return "checkbox";
		if (e instanceof AbstractSliderButton) return "slider";
		if (e instanceof TabButton) return "tab";
		if (e instanceof AbstractButton) return "button";
		if (e instanceof AbstractStringWidget) return "text";
		if (e instanceof AbstractTextAreaWidget) return "text_area";
		if (e instanceof AbstractSelectionList<?>) return "list";
		if (e instanceof TabNavigationBar) return "tab_bar";
		if (e instanceof AbstractContainerWidget || e instanceof ContainerEventHandler) return "container";
		if (e instanceof AbstractWidget) return "widget";
		return "other";
	}

	private static boolean isInteractive(String type) {
		return switch (type) {
			case "text_box", "multi_line_text_box", "cycle_button", "checkbox", "slider", "tab", "button", "list_entry" -> true;
			default -> false;
		};
	}

	private static String label(GuiEventListener e) {
		if (e instanceof AbstractWidget w) {
			return w.getMessage().getString();
		}
		if (e instanceof ObjectSelectionList.Entry<?> entry) {
			return entry.getNarration().getString();
		}
		return null;
	}

	/** Not hidden, and no parent is hidden. */
	private static boolean shown(Node node) {
		for (Node n = node; n != null; n = n.parent()) {
			if (n.element() instanceof AbstractWidget w && !w.visible) {
				return false;
			}
		}
		return true;
	}

	private static boolean active(GuiEventListener e) {
		return !(e instanceof AbstractWidget w) || w.isActive();
	}

	/** The center of the element is inside the screen and inside every scroll area around it. */
	private static boolean inView(Screen screen, Node node) {
		ScreenRectangle r = node.element().getRectangle();
		int cx = r.left() + r.width() / 2;
		int cy = r.top() + r.height() / 2;
		if (cx < 0 || cy < 0 || cx >= screen.width || cy >= screen.height) {
			return false;
		}
		for (AbstractScrollArea area : node.scrollAreas()) {
			ScreenRectangle a = area.getRectangle();
			if (cy < a.top() || cy >= a.bottom()) {
				return false;
			}
		}
		return true;
	}

	private static JsonObject describe(Minecraft mc, Screen screen, Node node) {
		GuiEventListener e = node.element();
		String type = type(node);
		JsonObject o = new JsonObject();
		o.addProperty("widget", node.index());
		o.addProperty("type", type);
		o.addProperty("class", className(e));
		if (node.parent() != null) {
			o.addProperty("parent", node.parent().index());
		}
		String label = label(e);
		if (label != null && !label.isEmpty()) {
			o.addProperty("label", label);
		}
		addValue(o, node);
		boolean shown = shown(node);
		o.addProperty("interactive", isInteractive(type) && shown && active(e));
		o.addProperty("active", active(e));
		o.addProperty("visible", shown);
		o.addProperty("inView", shown && inView(screen, node));
		o.addProperty("focused", e.isFocused());
		ScreenRectangle r = e.getRectangle();
		o.addProperty("x", r.left());
		o.addProperty("y", r.top());
		o.addProperty("width", r.width());
		o.addProperty("height", r.height());
		String tooltip = tooltip(mc, e);
		if (tooltip != null && !tooltip.isEmpty()) {
			o.addProperty("tooltip", tooltip);
		}
		return o;
	}

	private static void addValue(JsonObject o, Node node) {
		GuiEventListener e = node.element();
		if (e instanceof EditBox box) {
			o.addProperty("value", box.getValue());
			o.addProperty("editable", box.canConsumeInput());
		} else if (e instanceof MultiLineEditBox box) {
			o.addProperty("value", box.getValue());
		} else if (e instanceof CycleButton<?> cycle) {
			o.addProperty("value", valueText(cycle.getValue()));
		} else if (e instanceof Checkbox box) {
			o.addProperty("checked", box.selected());
		} else if (e instanceof AbstractSliderButton slider) {
			o.addProperty("fraction", ((AbstractSliderButtonAccessor) slider).gravel$getValue());
		} else if (e instanceof LockIconButton lock) {
			o.addProperty("locked", lock.isLocked());
		} else if (e instanceof TabButton tab) {
			o.addProperty("selected", tab.isSelected());
		} else if (e instanceof AbstractSelectionList<?> list) {
			o.addProperty("entries", list.children().size());
			o.addProperty("scroll", list.scrollAmount());
			o.addProperty("maxScroll", list.maxScrollAmount());
		} else if (e instanceof AbstractScrollArea area && area.maxScrollAmount() > 0) {
			o.addProperty("scroll", area.scrollAmount());
			o.addProperty("maxScroll", area.maxScrollAmount());
		} else if (node.ownerList() != null) {
			o.addProperty("selected", node.ownerList().getSelected() == e);
		}
	}

	private static String valueText(Object value) {
		if (value instanceof Enum<?> e) {
			return e.name();
		}
		if (value instanceof net.minecraft.network.chat.Component c) {
			return c.getString();
		}
		return String.valueOf(value);
	}

	static String className(Object o) {
		Class<?> c = o.getClass();
		while (c.isAnonymousClass() && c.getSuperclass() != null) {
			c = c.getSuperclass();
		}
		String name = c.getName();
		return name.substring(name.lastIndexOf('.') + 1);
	}

	private static String tooltip(Minecraft mc, GuiEventListener e) {
		if (!(e instanceof AbstractWidget w)) {
			return null;
		}
		Tooltip tooltip = ((AbstractWidgetAccessor) w).gravel$getTooltip().get();
		if (tooltip == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (FormattedCharSequence line : tooltip.toCharSequence(mc)) {
			if (!sb.isEmpty()) {
				sb.append('\n');
			}
			line.accept((index, style, codePoint) -> {
				sb.appendCodePoint(codePoint);
				return true;
			});
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------- read_screen

	private static JsonObject readScreen(Minecraft mc, JsonObject params) {
		boolean includeHidden = flag(params, "include_hidden");
		boolean interactiveOnly = flag(params, "interactive_only");
		Screen screen = mc.gui.screen();
		if (screen == null) {
			JsonObject o = new JsonObject();
			o.add("screen", null);
			o.addProperty("note", "No screen is open (in game). Use screen with action open, or press_key, to open one.");
			return o;
		}
		return screenState(mc, screen, includeHidden, interactiveOnly);
	}

	static JsonObject screenState(Minecraft mc, Screen screen, boolean includeHidden, boolean interactiveOnly) {
		JsonObject o = new JsonObject();
		o.addProperty("screen", className(screen));
		o.addProperty("title", screen.getTitle().getString());
		o.addProperty("width", screen.width);
		o.addProperty("height", screen.height);
		o.addProperty("guiScale", mc.getWindow().getGuiScale());
		o.addProperty("pausesGame", screen.isPauseScreen());
		String note = "Parts that the screen draws itself (not widgets) are not in this list. To use them, take a "
				+ "screenshot and use screen_input with x and y at their position.";
		if (screen instanceof AbstractContainerScreen<?>) {
			note = "This screen has item slots. Use the inventory tool for the slots. " + note;
		}
		o.addProperty("note", note);
		JsonArray widgets = new JsonArray();
		for (Node node : walk(screen)) {
			if (!includeHidden && !shown(node)) {
				continue;
			}
			JsonObject d = describe(mc, screen, node);
			if (interactiveOnly && !d.get("interactive").getAsBoolean()) {
				continue;
			}
			widgets.add(d);
		}
		o.addProperty("count", widgets.size());
		o.add("widgets", widgets);
		return o;
	}

	// ---------------------------------------------------------------- interact_widget

	private static JsonObject interactWidget(Minecraft mc, JsonObject params) throws Exception {
		Screen screen = mc.gui.screen();
		if (screen == null) {
			throw new IllegalStateException("No screen is open. Open a screen first (screen with action open, or press_key e).");
		}
		String action = params.has("action") ? params.get("action").getAsString().toLowerCase(Locale.ROOT) : "click";
		List<Node> nodes = walk(screen);
		Node target = findTarget(screen, nodes, params, !action.equals("scroll"));

		JsonObject o = new JsonObject();
		o.addProperty("action", action);
		o.addProperty("screenBefore", className(screen));
		if (target != null) {
			o.add("target", describe(mc, screen, target));
		}

		switch (action) {
			case "click" -> click(mc, screen, target, InputConstants.MOUSE_BUTTON_LEFT, false, o);
			case "double_click" -> click(mc, screen, target, InputConstants.MOUSE_BUTTON_LEFT, true, o);
			case "right_click" -> click(mc, screen, target, InputConstants.MOUSE_BUTTON_RIGHT, false, o);
			case "set" -> set(mc, screen, target, params, o);
			case "scroll" -> scroll(screen, target, params, o);
			case "focus" -> {
				requireUsable(target);
				InputActions.focus(screen, target.element());
			}
			default -> throw new IllegalArgumentException("action must be click, double_click, right_click, set, scroll "
					+ "or focus, not '" + action + "'");
		}

		Screen now = mc.gui.screen();
		o.addProperty("screen", now == null ? null : className(now));
		if (now == screen) {
			if (target != null) {
				for (Node n : walk(screen)) {
					if (n.element() == target.element()) {
						o.add("after", describe(mc, screen, n));
						break;
					}
				}
			}
		} else if (now != null) {
			// A new screen opened. Give its widgets so the next step needs no read_screen call.
			o.add("newScreen", screenState(mc, now, false, false));
		}
		return o;
	}

	/** Finds the widget by its index from read_screen, or by its label text. */
	private static Node findTarget(Screen screen, List<Node> nodes, JsonObject params, boolean required) {
		if (params.has("widget") && !params.get("widget").isJsonNull()) {
			int index = params.get("widget").getAsInt();
			if (index < 0 || index >= nodes.size()) {
				throw new IllegalArgumentException("widget " + index + " does not exist. Screen " + className(screen)
						+ " has widgets 0-" + (nodes.size() - 1) + ". Read the screen again, it can change.");
			}
			return nodes.get(index);
		}
		String label = params.has("label") && !params.get("label").isJsonNull() ? params.get("label").getAsString().trim() : "";
		if (label.isEmpty()) {
			if (required) {
				throw new IllegalArgumentException("Pass widget (index from the screen read) or label (text on the widget).");
			}
			return null;
		}
		String want = label.toLowerCase(Locale.ROOT);
		List<Node> exact = new ArrayList<>();
		List<Node> partial = new ArrayList<>();
		for (Node n : nodes) {
			String l = label(n.element());
			if (l == null || !shown(n) || !isInteractive(type(n))) {
				continue;
			}
			String have = l.toLowerCase(Locale.ROOT).trim();
			if (have.equals(want)) {
				exact.add(n);
			} else if (have.contains(want)) {
				partial.add(n);
			}
		}
		List<Node> matches = !exact.isEmpty() ? exact : partial;
		if (matches.size() == 1) {
			return matches.get(0);
		}
		if (matches.isEmpty()) {
			throw new IllegalArgumentException("No visible widget has the label '" + label + "' on screen "
					+ className(screen) + ". Read the screen to see the widgets.");
		}
		Set<String> found = new LinkedHashSet<>();
		for (Node n : matches) {
			found.add(n.index() + ": " + label(n.element()));
		}
		throw new IllegalArgumentException("More than one widget has the label '" + label + "': " + found
				+ ". Pass widget with the index.");
	}

	private static void requireUsable(Node target) {
		if (!shown(target)) {
			throw new IllegalStateException("Widget " + target.index() + " is hidden.");
		}
		if (!active(target.element())) {
			throw new IllegalStateException("Widget " + target.index() + " ('" + label(target.element())
					+ "') is not active (greyed out), so it cannot be used now.");
		}
	}

	/** Scrolls the lists and scroll areas around the element until its center shows. */
	private static void scrollIntoView(Screen screen, Node node) {
		for (AbstractScrollArea area : node.scrollAreas()) {
			ScreenRectangle a = area.getRectangle();
			ScreenRectangle r = node.element().getRectangle();
			int cy = r.top() + r.height() / 2;
			if (cy < a.top() || cy >= a.bottom()) {
				// setScrollAmount clamps the value and moves the entries of the area.
				area.setScrollAmount(area.scrollAmount() + (cy - (a.top() + a.height() / 2.0)));
			}
		}
		if (!inView(screen, node)) {
			throw new IllegalStateException("Widget " + node.index() + " is outside the screen and cannot be scrolled into view.");
		}
	}

	/** The click point. A slider is clicked on its handle, so a click does not move it. */
	private static double[] clickPoint(Node node) {
		ScreenRectangle r = node.element().getRectangle();
		double x = r.left() + r.width() / 2.0;
		double y = r.top() + r.height() / 2.0;
		if (node.element() instanceof AbstractSliderButton slider) {
			double value = ((AbstractSliderButtonAccessor) slider).gravel$getValue();
			x = r.left() + SLIDER_HANDLE / 2.0 + value * (r.width() - SLIDER_HANDLE);
		}
		return new double[]{x, y};
	}

	/** True if a mouse event at the point reaches the element, the same search the screen does for a click. */
	private static boolean reaches(ContainerEventHandler parent, GuiEventListener target, double x, double y) {
		GuiEventListener hit = parent.getChildAt(x, y).orElse(null);
		if (hit == null) {
			return false;
		}
		if (hit == target) {
			return true;
		}
		return hit instanceof ContainerEventHandler c && reaches(c, target, x, y);
	}

	private static void click(Minecraft mc, Screen screen, Node target, int button, boolean doubleClick, JsonObject o) {
		requireUsable(target);
		scrollIntoView(screen, target);
		double[] p = clickPoint(target);
		boolean handled = mouseClick(mc, screen, target, p[0], p[1], button);
		if (doubleClick && mc.gui.screen() == screen) {
			handled |= mouseClick(mc, screen, target, p[0], p[1], button, true);
		}
		o.addProperty("x", p[0]);
		o.addProperty("y", p[1]);
		o.addProperty("handled", handled);
	}

	private static boolean mouseClick(Minecraft mc, Screen screen, Node target, double x, double y, int button) {
		return mouseClick(mc, screen, target, x, y, button, false);
	}

	/** One press and release through the screen, like the mouse handler sends for a real click. */
	private static boolean mouseClick(Minecraft mc, Screen screen, Node target, double x, double y, int button,
									  boolean doubleClick) {
		if (!reaches(screen, target.element(), x, y)) {
			GuiEventListener top = screen.getChildAt(x, y).orElse(null);
			throw new IllegalStateException("A click at (" + Math.round(x) + ", " + Math.round(y) + ") does not reach widget "
					+ target.index() + ". " + (top == null ? "Nothing takes the click there."
					: "It goes to " + className(top) + (label(top) == null ? "" : " '" + label(top) + "'") + "."));
		}
		MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
		boolean handled = screen.mouseClicked(event, doubleClick);
		// The click can open another screen. The release then goes to the screen that is open, as with a real mouse.
		Screen now = mc.gui.screen();
		if (now != null) {
			now.mouseReleased(event);
			now.afterMouseAction();
		}
		return handled;
	}

	private static void set(Minecraft mc, Screen screen, Node target, JsonObject params, JsonObject o) {
		requireUsable(target);
		GuiEventListener e = target.element();
		String value = params.has("value") && !params.get("value").isJsonNull() ? params.get("value").getAsString() : null;
		if (e instanceof AbstractSliderButton slider) {
			setSlider(mc, screen, target, slider, value, params, o);
			return;
		}
		if (value == null) {
			throw new IllegalArgumentException("Parameter 'value' is required for set");
		}
		if (e instanceof EditBox || e instanceof MultiLineEditBox) {
			InputActions.focus(screen, e);
			if (e instanceof EditBox box) {
				box.setValue(value);
			} else {
				((MultiLineEditBox) e).setValue(value);
			}
		} else if (e instanceof Checkbox box) {
			boolean want = parseBool(value);
			if (box.selected() != want) {
				scrollIntoView(screen, target);
				double[] p = clickPoint(target);
				mouseClick(mc, screen, target, p[0], p[1], InputConstants.MOUSE_BUTTON_LEFT);
			}
		} else if (e instanceof LockIconButton lock) {
			boolean want = parseBool(value);
			if (lock.isLocked() != want) {
				scrollIntoView(screen, target);
				double[] p = clickPoint(target);
				mouseClick(mc, screen, target, p[0], p[1], InputConstants.MOUSE_BUTTON_LEFT);
			}
		} else if (e instanceof CycleButton<?> cycle) {
			setCycle(mc, screen, target, cycle, value, o);
		} else {
			throw new IllegalArgumentException("set works on text boxes, check boxes, cycle buttons, lock buttons and "
					+ "sliders. Widget " + target.index() + " is a " + type(target) + ". Use click.");
		}
	}

	/** Clicks the cycle button until it shows the value. Each click is a real click, so the screen sees each change. */
	private static void setCycle(Minecraft mc, Screen screen, Node target, CycleButton<?> cycle, String value, JsonObject o) {
		scrollIntoView(screen, target);
		Object start = cycle.getValue();
		List<String> seen = new ArrayList<>();
		int clicks = 0;
		while (!matches(cycle.getMessage().getString(), valueText(cycle.getValue()), value)) {
			seen.add(valueText(cycle.getValue()));
			if (clicks >= MAX_CYCLE_CLICKS || mc.gui.screen() != screen) {
				break;
			}
			double[] p = clickPoint(target);
			mouseClick(mc, screen, target, p[0], p[1], InputConstants.MOUSE_BUTTON_LEFT);
			clicks++;
			if (Objects.equals(cycle.getValue(), start)) {
				throw new IllegalArgumentException("Cycle button " + target.index() + " has no value '" + value
						+ "'. Its values are: " + seen);
			}
		}
		o.addProperty("clicks", clicks);
	}

	private static void setSlider(Minecraft mc, Screen screen, Node target, AbstractSliderButton slider, String value,
								  JsonObject params, JsonObject o) {
		AbstractSliderButtonAccessor access = (AbstractSliderButtonAccessor) slider;
		if (params.has("fraction") && !params.get("fraction").isJsonNull()) {
			double fraction = params.get("fraction").getAsDouble();
			if (fraction < 0 || fraction > 1) {
				throw new IllegalArgumentException("fraction must be 0-1");
			}
			access.gravel$setValue(fraction);
		} else if (value != null) {
			if (!matches(slider.getMessage().getString(), null, value)) {
				double original = access.gravel$getValue();
				boolean found = false;
				// Same as dragging the handle along the slider until it shows the value.
				for (int i = 0; i <= SLIDER_STEPS && !found; i++) {
					access.gravel$setValue(i / (double) SLIDER_STEPS);
					found = matches(slider.getMessage().getString(), null, value);
				}
				if (!found) {
					access.gravel$setValue(original);
					slider.onRelease(new MouseButtonEvent(0, 0, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
					throw new IllegalArgumentException("Slider " + target.index() + " does not show '" + value
							+ "' at any position. It shows '" + slider.getMessage().getString() + "'. Pass fraction (0-1) "
							+ "to set the position.");
				}
			}
		} else {
			throw new IllegalArgumentException("For a slider pass value (the text it shows, such as 90 or 50%) or fraction (0-1).");
		}
		// The release applies values that some sliders keep until the mouse lets go.
		ScreenRectangle r = slider.getRectangle();
		slider.onRelease(new MouseButtonEvent(r.left(), r.top(), new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
		o.addProperty("fraction", access.gravel$getValue());
	}

	/**
	 * True if the widget shows the wanted value. The message is often "Label: Value". Also accepts the value with
	 * a % sign or a unit after it (50 matches "Music: 50%", 12 matches "Render Distance: 12 chunks").
	 */
	static boolean matches(String message, String rawValue, String want) {
		String w = want.trim().toLowerCase(Locale.ROOT);
		if (rawValue != null && rawValue.trim().equalsIgnoreCase(w)) {
			return true;
		}
		String m = message.trim().toLowerCase(Locale.ROOT);
		if (m.equals(w)) {
			return true;
		}
		int colon = m.lastIndexOf(": ");
		String shown = colon >= 0 ? m.substring(colon + 2).trim() : m;
		return shown.equals(w) || shown.equals(w + "%") || shown.startsWith(w + " ");
	}

	private static void scroll(Screen screen, Node target, JsonObject params, JsonObject o) {
		double amount = params.has("amount") ? params.get("amount").getAsDouble() : 1;
		double x;
		double y;
		if (target != null) {
			if (!shown(target)) {
				throw new IllegalStateException("Widget " + target.index() + " is hidden.");
			}
			ScreenRectangle r = target.element().getRectangle();
			x = r.left() + r.width() / 2.0;
			y = r.top() + r.height() / 2.0;
		} else {
			x = screen.width / 2.0;
			y = screen.height / 2.0;
		}
		// Minecraft uses positive for the wheel going up. Here positive goes down the list.
		boolean handled = screen.mouseScrolled(x, y, 0, -amount);
		o.addProperty("handled", handled);
		AbstractScrollArea area = target == null ? null
				: target.element() instanceof AbstractScrollArea a ? a
				: target.scrollAreas().isEmpty() ? null : target.scrollAreas().get(target.scrollAreas().size() - 1);
		if (area != null) {
			o.addProperty("scroll", area.scrollAmount());
			o.addProperty("maxScroll", area.maxScrollAmount());
		}
	}

	// ---------------------------------------------------------------- params

	private static boolean flag(JsonObject params, String name) {
		return params.has(name) && !params.get(name).isJsonNull() && params.get(name).getAsBoolean();
	}

	private static boolean parseBool(String value) {
		String v = value.trim().toLowerCase(Locale.ROOT);
		return switch (v) {
			case "true", "on", "yes", "1", "checked", "locked" -> true;
			case "false", "off", "no", "0", "unchecked", "unlocked" -> false;
			default -> throw new IllegalArgumentException("Needs true or false, got '" + value + "'");
		};
	}
}
