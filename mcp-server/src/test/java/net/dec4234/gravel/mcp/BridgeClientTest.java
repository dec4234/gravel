package net.dec4234.gravel.mcp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class BridgeClientTest {
	@Test
	void isUpIsFalseWhenNothingListens() throws IOException {
		assertFalse(new BridgeClient(FakeBridge.unusedPort()).isUp());
	}

	@Test
	void isUpIsTrueWhenBridgeAnswers() throws IOException {
		try (FakeBridge fake = new FakeBridge()) {
			assertTrue(new BridgeClient(fake.port()).isUp());
		}
	}

	@Test
	void actionSendsParamsAndReturnsBody() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("echo", params -> {
				JsonObject o = new JsonObject();
				o.add("got", params);
				return FakeBridge.Reply.ok(o);
			});
			JsonObject params = new JsonObject();
			params.addProperty("x", 1);

			JsonObject result = new BridgeClient(fake.port()).action("echo", params);

			assertEquals(1, result.getAsJsonObject("got").get("x").getAsInt());
			assertEquals("echo", fake.calls.getFirst().action());
		}
	}

	@Test
	void actionWithNullParamsSendsEmptyObject() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("status", params -> FakeBridge.Reply.ok(new JsonObject()));
			new BridgeClient(fake.port()).action("status", null);
			assertTrue(fake.calls.getFirst().params().isEmpty());
		}
	}

	@Test
	void actionErrorUsesErrorFieldFromBody() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("bad", params -> {
				JsonObject o = new JsonObject();
				o.addProperty("error", "boom");
				return new FakeBridge.Reply(500, o);
			});
			IOException e = assertThrows(IOException.class, () -> new BridgeClient(fake.port()).action("bad", null));
			assertEquals("Action 'bad' failed: boom", e.getMessage());
		}
	}

	@Test
	void actionErrorWithoutErrorFieldUsesStatusCode() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			fake.on("teapot", params -> new FakeBridge.Reply(418, new JsonObject()));
			IOException e = assertThrows(IOException.class, () -> new BridgeClient(fake.port()).action("teapot", null));
			assertEquals("Action 'teapot' failed: HTTP 418", e.getMessage());
		}
	}

	@Test
	void unknownActionFails() throws Exception {
		try (FakeBridge fake = new FakeBridge()) {
			IOException e = assertThrows(IOException.class, () -> new BridgeClient(fake.port()).action("nope", null));
			assertTrue(e.getMessage().contains("Unknown action: nope"));
		}
	}
}
