package net.dec4234.gravel.client.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.dec4234.gravel.Gravel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small HTTP server, bound to localhost only, that the Gravel MCP server talks to.
 *
 * <ul>
 *   <li>{@code GET  /health}         - returns {@code {"ok":true}} once the client is up</li>
 *   <li>{@code GET  /actions}        - lists the registered action names</li>
 *   <li>{@code POST /action/<name>}  - runs an action; body is a JSON object of params</li>
 * </ul>
 */
public final class BridgeServer {
	public static final int DEFAULT_PORT = 25599;
	private static final Gson GSON = new Gson();

	private final ActionRegistry actions;
	private HttpServer server;

	public BridgeServer(ActionRegistry actions) {
		this.actions = actions;
	}

	public static int configuredPort() {
		String value = System.getProperty("gravel.bridge.port");
		if (value == null) {
			value = System.getenv("GRAVEL_BRIDGE_PORT");
		}
		if (value != null) {
			try {
				return Integer.parseInt(value.trim());
			} catch (NumberFormatException e) {
				Gravel.LOGGER.warn("Invalid gravel bridge port '{}', using {}", value, DEFAULT_PORT);
			}
		}
		return DEFAULT_PORT;
	}

	public void start(int port) throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		ExecutorService executor = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "gravel-bridge");
			t.setDaemon(true);
			return t;
		});
		server.setExecutor(executor);

		server.createContext("/health", ex -> {
			JsonObject body = new JsonObject();
			body.addProperty("ok", true);
			send(ex, 200, body);
		});

		server.createContext("/actions", ex -> {
			JsonObject body = new JsonObject();
			body.add("actions", GSON.toJsonTree(actions.names()));
			send(ex, 200, body);
		});

		server.createContext("/action/", this::handleAction);

		server.start();
		Gravel.LOGGER.info("Gravel bridge listening on 127.0.0.1:{}", port);
	}

	private void handleAction(HttpExchange ex) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
				send(ex, 405, error("Use POST"));
				return;
			}
			String name = ex.getRequestURI().getPath().substring("/action/".length());
			if (!actions.has(name)) {
				send(ex, 404, error("Unknown action: " + name));
				return;
			}
			JsonObject params = readBody(ex.getRequestBody());
			JsonObject result = actions.run(name, params);
			send(ex, 200, result);
		} catch (Exception e) {
			Gravel.LOGGER.error("Gravel bridge action failed", e);
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			send(ex, 500, error(cause.getClass().getSimpleName() + ": " + cause.getMessage()));
		}
	}

	private static JsonObject readBody(InputStream in) throws IOException {
		String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		if (text.isEmpty()) {
			return new JsonObject();
		}
		return JsonParser.parseString(text).getAsJsonObject();
	}

	private static JsonObject error(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("error", message);
		return o;
	}

	private static void send(HttpExchange ex, int status, JsonObject body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** The port the server listens on (useful when started on port 0). */
	public int port() {
		return server.getAddress().getPort();
	}

	public void stop() {
		if (server != null) {
			server.stop(0);
		}
	}
}
