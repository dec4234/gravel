package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.dec4234.gravel.client.mixin.MouseHandlerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.Locale;

/**
 * Mouse input at a point of the open screen. This works on all screens, also screens from other mods and screens
 * that draw their own parts with no widgets (recipe book, advancements, trades, mod GUIs). The events go to the
 * screen the same way the real mouse sends them, so the screen runs its own code.
 */
public final class MouseActions {
	/** Most steps in one drag. */
	static final int MAX_DRAG_STEPS = 200;
	private static final int DEFAULT_DRAG_STEPS = 10;

	private MouseActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("mouse", MouseActions::mouse);
	}

	private static JsonObject mouse(Minecraft mc, JsonObject params) {
		Screen screen = mc.gui.screen();
		if (screen == null) {
			throw new IllegalStateException("No screen is open. screen_input works on screens. In game, use attack, "
					+ "use or look.");
		}
		String action = params.has("action") ? params.get("action").getAsString().toLowerCase(Locale.ROOT) : "click";
		double scale = toGui(params, screen);
		double x = coordinate(params, "x", screen.width / 2.0) * scale;
		double y = coordinate(params, "y", screen.height / 2.0) * scale;
		checkInside(screen, x, y, "x, y");
		int button = button(params);
		int modifiers = InputActions.modifiers(params.get("modifiers"));

		JsonObject o = new JsonObject();
		o.addProperty("action", action);
		o.addProperty("screenBefore", WidgetActions.className(screen));
		o.addProperty("x", x);
		o.addProperty("y", y);
		o.add("under", under(screen, x, y));

		switch (action) {
			case "move" -> moveTo(mc, screen, x, y);
			case "click" -> o.addProperty("handled", click(mc, screen, x, y, button, modifiers, false));
			case "double_click" -> {
				boolean handled = click(mc, screen, x, y, button, modifiers, false);
				if (mc.gui.screen() == screen) {
					handled |= click(mc, screen, x, y, button, modifiers, true);
				}
				o.addProperty("handled", handled);
			}
			case "press" -> {
				moveTo(mc, screen, x, y);
				o.addProperty("handled", screen.mouseClicked(event(x, y, button, modifiers), false));
				screen.afterMouseAction();
			}
			case "release" -> {
				moveTo(mc, screen, x, y);
				o.addProperty("handled", screen.mouseReleased(event(x, y, button, modifiers)));
				screen.afterMouseAction();
			}
			case "drag" -> drag(mc, screen, x, y, scale, button, modifiers, params, o);
			case "scroll" -> {
				moveTo(mc, screen, x, y);
				double amount = params.has("amount") ? params.get("amount").getAsDouble() : 1;
				// Minecraft uses positive for the wheel going up. Here positive goes down, as for widgets.
				o.addProperty("handled", screen.mouseScrolled(x, y, 0, -amount));
			}
			default -> throw new IllegalArgumentException("action must be click, double_click, press, release, drag, "
					+ "scroll or move, not '" + action + "'");
		}

		Screen now = mc.gui.screen();
		o.addProperty("screen", now == null ? null : WidgetActions.className(now));
		if (now != null && now != screen) {
			// A new screen opened. Give its widgets so the next step needs no screen read call.
			o.add("newScreen", WidgetActions.screenState(mc, now, false, false));
		}
		return o;
	}

	/** One press and release, like the mouse handler sends for a real click. */
	private static boolean click(Minecraft mc, Screen screen, double x, double y, int button, int modifiers,
								 boolean doubleClick) {
		moveTo(mc, screen, x, y);
		MouseButtonEvent event = event(x, y, button, modifiers);
		boolean handled = screen.mouseClicked(event, doubleClick);
		// The click can open another screen. The release then goes to the screen that is open, as with a real mouse.
		Screen now = mc.gui.screen();
		if (now != null) {
			now.mouseReleased(event);
			now.afterMouseAction();
		}
		return handled;
	}

	/** Press at the start, move in steps to the end with the button down, release at the end. */
	private static void drag(Minecraft mc, Screen screen, double x, double y, double scale, int button, int modifiers,
							 JsonObject params, JsonObject o) {
		if (!params.has("to_x") || !params.has("to_y")) {
			throw new IllegalArgumentException("drag needs to_x and to_y (the end point).");
		}
		double toX = params.get("to_x").getAsDouble() * scale;
		double toY = params.get("to_y").getAsDouble() * scale;
		checkInside(screen, toX, toY, "to_x, to_y");
		int steps = params.has("steps") ? params.get("steps").getAsInt() : DEFAULT_DRAG_STEPS;
		if (steps < 1 || steps > MAX_DRAG_STEPS) {
			throw new IllegalArgumentException("steps must be 1-" + MAX_DRAG_STEPS);
		}
		moveTo(mc, screen, x, y);
		boolean handled = screen.mouseClicked(event(x, y, button, modifiers), false);
		double lastX = x;
		double lastY = y;
		for (int i = 1; i <= steps && mc.gui.screen() == screen; i++) {
			double px = x + (toX - x) * i / steps;
			double py = y + (toY - y) * i / steps;
			setPosition(mc, px, py);
			screen.mouseMoved(px, py);
			handled |= screen.mouseDragged(event(px, py, button, modifiers), px - lastX, py - lastY);
			lastX = px;
			lastY = py;
		}
		Screen now = mc.gui.screen();
		if (now != null) {
			now.mouseReleased(event(lastX, lastY, button, modifiers));
			now.afterMouseAction();
		}
		o.addProperty("toX", toX);
		o.addProperty("toY", toY);
		o.addProperty("handled", handled);
	}

	/** Moves the game's mouse to the point, so hover effects and tooltips show there. */
	private static void moveTo(Minecraft mc, Screen screen, double x, double y) {
		setPosition(mc, x, y);
		screen.mouseMoved(x, y);
		screen.afterMouseMove();
	}

	private static void setPosition(Minecraft mc, double guiX, double guiY) {
		Window window = mc.getWindow();
		MouseHandlerAccessor mouse = (MouseHandlerAccessor) mc.mouseHandler;
		mouse.gravel$setXpos(guiX * window.getScreenWidth() / window.getGuiScaledWidth());
		mouse.gravel$setYpos(guiY * window.getScreenHeight() / window.getGuiScaledHeight());
	}

	private static MouseButtonEvent event(double x, double y, int button, int modifiers) {
		return new MouseButtonEvent(x, y, new MouseButtonInfo(button, modifiers));
	}

	/** The element of the screen that gets a click at the point, if any. */
	private static JsonObject under(Screen screen, double x, double y) {
		GuiEventListener hit = screen.getChildAt(x, y).orElse(null);
		if (hit == null) {
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("class", WidgetActions.className(hit));
		if (hit instanceof AbstractWidget w) {
			o.addProperty("label", w.getMessage().getString());
		}
		return o;
	}

	/**
	 * The factor from the given coordinates to GUI coordinates. With image_width the coordinates are pixels of a
	 * screenshot of that width. Without it they are GUI coordinates, the same as in a screen read.
	 */
	static double toGui(JsonObject params, Screen screen) {
		if (!params.has("image_width") || params.get("image_width").isJsonNull()) {
			return 1;
		}
		int imageWidth = params.get("image_width").getAsInt();
		if (imageWidth <= 0) {
			throw new IllegalArgumentException("image_width must be more than 0");
		}
		return screen.width / (double) imageWidth;
	}

	private static double coordinate(JsonObject params, String name, double fallback) {
		return params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsDouble() : fallback;
	}

	private static void checkInside(Screen screen, double x, double y, String what) {
		if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) {
			throw new IllegalArgumentException("Point " + what + " (" + Math.round(x) + ", " + Math.round(y)
					+ " in GUI coordinates) is outside the screen, which is " + screen.width + "x" + screen.height
					+ ". If the point comes from a screenshot, pass image_width.");
		}
	}

	static int button(JsonObject params) {
		String name = params.has("button") ? params.get("button").getAsString().toLowerCase(Locale.ROOT) : "left";
		return switch (name) {
			case "left" -> InputConstants.MOUSE_BUTTON_LEFT;
			case "right" -> InputConstants.MOUSE_BUTTON_RIGHT;
			case "middle" -> InputConstants.MOUSE_BUTTON_MIDDLE;
			default -> throw new IllegalArgumentException("button must be left, right or middle, not '" + name + "'");
		};
	}
}
