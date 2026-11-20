package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real BridgeServer over HTTP, with actions that run on the test thread instead of the game thread. */
class BridgeServerTest {
	private final HttpClient http = HttpClient.newHttpClient();
	private BridgeServer server;
	private String base;

	@BeforeEach
	void start() throws Exception {
		ActionRegistry registry = new ActionRegistry(() -> null, task -> CompletableFuture.supplyAsync(task));
		registry.register("echo", (mc, params) -> {
			JsonObject o = new JsonObject();
			o.add("got", params);
			return o;
		});
		registry.register("fail", (mc, params) -> {
			throw new IllegalStateException("Not in a world");
		});
		registry.register("checked", (mc, params) -> {
			throw new Exception("checked problem");
		});
		server = new BridgeServer(registry);
		server.start(0);
		base = "http://127.0.0.1:" + server.port();
	}

	@AfterEach
	void stop() {
		server.stop();
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, String body) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(base + path))
						.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private static JsonObject json(HttpResponse<String> res) {
		return JsonParser.parseString(res.body()).getAsJsonObject();
	}

	@Test
	void healthIsOk() throws Exception {
		HttpResponse<String> res = get("/health");
		assertEquals(200, res.statusCode());
		assertTrue(json(res).get("ok").getAsBoolean());
	}

	@Test
	void actionsListsRegisteredNames() throws Exception {
		HttpResponse<String> res = get("/actions");
		assertEquals("[\"echo\",\"fail\",\"checked\"]", json(res).get("actions").toString());
	}

	@Test
	void actionGetsParamsAndReturnsResult() throws Exception {
		HttpResponse<String> res = post("/action/echo", "{\"x\":3}");
		assertEquals(200, res.statusCode());
		assertEquals(3, json(res).getAsJsonObject("got").get("x").getAsInt());
	}

	@Test
	void emptyBodyGivesEmptyParams() throws Exception {
		HttpResponse<String> res = post("/action/echo", "");
		assertEquals(200, res.statusCode());
		assertTrue(json(res).getAsJsonObject("got").isEmpty());
	}

	@Test
	void actionNeedsPost() throws Exception {
		HttpResponse<String> res = get("/action/echo");
		assertEquals(405, res.statusCode());
		assertEquals("Use POST", json(res).get("error").getAsString());
	}

	@Test
	void unknownActionIs404() throws Exception {
		HttpResponse<String> res = post("/action/nope", "{}");
		assertEquals(404, res.statusCode());
		assertEquals("Unknown action: nope", json(res).get("error").getAsString());
	}

	@Test
	void actionExceptionBecomes500WithRootCause() throws Exception {
		HttpResponse<String> res = post("/action/fail", "{}");
		assertEquals(500, res.statusCode());
		assertEquals("IllegalStateException: Not in a world", json(res).get("error").getAsString());
	}

	@Test
	void checkedExceptionIsReported() throws Exception {
		HttpResponse<String> res = post("/action/checked", "{}");
		assertEquals(500, res.statusCode());
		assertTrue(json(res).get("error").getAsString().contains("checked problem"), res.body());
	}

	@Test
	void badJsonIs500() throws Exception {
		HttpResponse<String> res = post("/action/echo", "{not json");
		assertEquals(500, res.statusCode());
		assertTrue(json(res).has("error"));
	}

	@Test
	void configuredPortUsesSystemProperty() {
		String old = System.getProperty("gravel.bridge.port");
		try {
			System.setProperty("gravel.bridge.port", " 31337 ");
			assertEquals(31337, BridgeServer.configuredPort());
			System.setProperty("gravel.bridge.port", "not a number");
			assertEquals(BridgeServer.DEFAULT_PORT, BridgeServer.configuredPort());
		} finally {
			if (old == null) {
				System.clearProperty("gravel.bridge.port");
			} else {
				System.setProperty("gravel.bridge.port", old);
			}
		}
	}
}
