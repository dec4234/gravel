package net.dec4234.gravel.client.bridge;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MovementActionsTest {
	private static final Vec3 EYE = new Vec3(0, 64, 0);

	@Test
	void yawPointsToCompassDirections() {
		assertEquals(0f, MovementActions.anglesTo(EYE, new Vec3(0, 64, 10))[0], 1e-3);   // south
		assertEquals(90f, MovementActions.anglesTo(EYE, new Vec3(-10, 64, 0))[0], 1e-3); // west
		assertEquals(180f, Math.abs(MovementActions.anglesTo(EYE, new Vec3(0, 64, -10))[0]), 1e-3); // north
		assertEquals(-90f, MovementActions.anglesTo(EYE, new Vec3(10, 64, 0))[0], 1e-3); // east
	}

	@Test
	void pitchIsNegativeUpAndPositiveDown() {
		assertEquals(-45f, MovementActions.anglesTo(EYE, new Vec3(0, 74, 10))[1], 1e-3);
		assertEquals(45f, MovementActions.anglesTo(EYE, new Vec3(0, 54, 10))[1], 1e-3);
		assertEquals(90f, MovementActions.anglesTo(EYE, new Vec3(0, 50, 0))[1], 1e-3);
	}

	@Test
	void samePointIsAnError() {
		assertThrows(IllegalArgumentException.class, () -> MovementActions.anglesTo(EYE, EYE));
	}

	@Test
	void directionPartsSplitMixes() {
		assertEquals(List.of("forward", "left"), MovementActions.directionParts("Forward+Left"));
		assertEquals(List.of("back", "right"), MovementActions.directionParts("back_right"));
		assertEquals(List.of("forward"), MovementActions.directionParts("  "));
	}

	@Test
	void horizontalIgnoresHeight() {
		assertEquals(5.0, MovementActions.horizontal(new Vec3(0, 0, 0), new Vec3(3, 100, 4)), 1e-9);
	}
}
