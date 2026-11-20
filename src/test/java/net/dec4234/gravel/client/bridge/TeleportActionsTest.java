package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeleportActionsTest {
	private static JsonObject params(String name, String value) {
		JsonObject o = new JsonObject();
		o.addProperty(name, value);
		return o;
	}

	@Test
	void buildCommandWithoutRotation() {
		assertEquals("tp @s 1 ~2 -3.5", TeleportActions.buildCommand("1", "~2", "-3.5", null, null));
	}

	@Test
	void buildCommandFillsMissingRotationWithTilde() {
		assertEquals("tp @s 0 64 0 90 ~", TeleportActions.buildCommand("0", "64", "0", "90", null));
		assertEquals("tp @s 0 64 0 ~ -45", TeleportActions.buildCommand("0", "64", "0", null, "-45"));
	}

	@Test
	void coordinateAcceptsNumbersAndRelativeForms() {
		for (String v : new String[]{"0", "-12", "3.5", ".5", "~", "~-20", "^", "^1.5"}) {
			assertEquals(v, TeleportActions.coordinate(params("x", v), "x", true));
		}
		JsonObject number = new JsonObject();
		number.addProperty("x", 42);
		assertEquals("42", TeleportActions.coordinate(number, "x", true));
	}

	@Test
	void coordinateRejectsOtherText() {
		for (String v : new String[]{"abc", "1 2", "~~", "1;", "-"}) {
			assertThrows(IllegalArgumentException.class, () -> TeleportActions.coordinate(params("x", v), "x", true), v);
		}
	}

	@Test
	void missingCoordinate() {
		assertThrows(IllegalArgumentException.class, () -> TeleportActions.coordinate(new JsonObject(), "x", true));
		assertNull(TeleportActions.coordinate(new JsonObject(), "yaw", false));
	}
}
