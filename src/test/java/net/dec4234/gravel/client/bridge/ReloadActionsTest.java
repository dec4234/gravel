package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadActionsTest {
	@Test
	void waitMsHasDefaultAndLimits() {
		assertEquals(10_000, ReloadActions.waitMs(new JsonObject()));
		JsonObject params = new JsonObject();
		params.addProperty("wait_ms", 999_999);
		assertEquals(ReloadActions.MAX_CHUNK_WAIT_MS, ReloadActions.waitMs(params));
		params.addProperty("wait_ms", -5);
		assertEquals(0, ReloadActions.waitMs(params));
	}

	@Test
	void chunkWaitNeedsEmptyQueueAndStableCount() {
		ReloadActions.ChunkWait wait = new ReloadActions.ChunkWait(1000);
		// Just after the reload the queue is empty, but the count still grows.
		assertFalse(wait.update(true, 5));
		assertFalse(wait.update(true, 40));
		for (int i = 0; i < 5; i++) {
			assertFalse(wait.update(false, 40));
		}
		// The count changes to 90, then must stay the same for STABLE_POLLS more polls.
		assertFalse(wait.update(true, 90));
		for (int i = 1; i < ReloadActions.ChunkWait.STABLE_POLLS; i++) {
			assertFalse(wait.update(true, 90));
		}
		assertTrue(wait.update(true, 90));
	}
}
