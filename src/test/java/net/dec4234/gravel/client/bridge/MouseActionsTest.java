package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MouseActionsTest {
	@Test
	void buttonDefaultsToLeft() {
		assertEquals(InputConstants.MOUSE_BUTTON_LEFT, MouseActions.button(new JsonObject()));
	}

	@Test
	void buttonNames() {
		assertEquals(InputConstants.MOUSE_BUTTON_RIGHT, MouseActions.button(params("button", "Right")));
		assertEquals(InputConstants.MOUSE_BUTTON_MIDDLE, MouseActions.button(params("button", "middle")));
		assertThrows(IllegalArgumentException.class, () -> MouseActions.button(params("button", "side")));
	}

	private static JsonObject params(String name, String value) {
		JsonObject o = new JsonObject();
		o.addProperty(name, value);
		return o;
	}
}
