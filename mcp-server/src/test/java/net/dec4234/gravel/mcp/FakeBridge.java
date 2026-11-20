package net.dec4234.gravel.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A stand-in for the mod's BridgeServer. Each action returns a canned JSON reply. */
final class FakeBridge implements AutoCloseable {
	private static final Gson GSON = new Gson();

	/** A reply from an action: HTTP status and JSON body. */
	record Reply(int status, JsonObject body) {
		static Reply ok(JsonObject body) {
			return new Reply(200, body);
		}
	}

	/** A request the fake bridge got: action name and JSON params. */
	record Call(String action, JsonObject params) {
	}

	private final HttpServer server;
	private final Map<String, Function<JsonObject, Reply>> actions = new ConcurrentHashMap<>();
	final List<Call> calls = new CopyOnWriteArrayList<>();

	FakeBridge() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/health", ex -> send(ex, 200, "{\"ok\":true}"));
		server.createContext("/action/", ex -> {
			String name = ex.getRequestURI().getPath().substring("/action/".length());
			String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			JsonObject params = text.isBlank() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
			calls.add(new Call(name, params));
			Function<JsonObject, Reply> handler = actions.get(name);
			if (handler == null) {
				send(ex, 404, "{\"error\":\"Unknown action: " + name + "\"}");
				return;
			}
			Reply reply = handler.apply(params);
			send(ex, reply.status(), GSON.toJson(reply.body()));
		});
		server.start();
	}

	FakeBridge on(String action, Function<JsonObject, Reply> handler) {
		actions.put(action, handler);
		return this;
	}

	int port() {
		return server.getAddress().getPort();
	}

	private static void send(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}

	/** A loopback port that nothing listens on (bound, then released). */
	static int unusedPort() throws IOException {
		try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}
}
