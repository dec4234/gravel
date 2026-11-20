package net.dec4234.gravel.client.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WidgetActionsTest {
	@Test
	void matchesTheValueAfterTheLabel() {
		assertTrue(WidgetActions.matches("FOV: 90", null, "90"));
		assertTrue(WidgetActions.matches("FOV: Quake Pro", null, "quake pro"));
		assertFalse(WidgetActions.matches("FOV: 90", null, "9"));
	}

	@Test
	void matchesPercentAndUnits() {
		assertTrue(WidgetActions.matches("Music: 50%", null, "50"));
		assertTrue(WidgetActions.matches("Music: 50%", null, "50%"));
		assertTrue(WidgetActions.matches("Render Distance: 12 chunks", null, "12"));
		assertFalse(WidgetActions.matches("Render Distance: 12 chunks", null, "1"));
	}

	@Test
	void matchesTheWholeMessageOrTheRawValue() {
		assertTrue(WidgetActions.matches("Hard", null, "hard"));
		assertTrue(WidgetActions.matches("Difficulty: Hard", "HARD", "hard"));
		assertTrue(WidgetActions.matches("Game Mode: Creative", "creative", "CREATIVE"));
		assertFalse(WidgetActions.matches("Difficulty: Hard", "HARD", "easy"));
	}
}
