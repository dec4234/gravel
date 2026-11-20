package net.dec4234.gravel.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Talks to the HTTP bridge inside the running Minecraft client (see BridgeServer in the mod). */
final class BridgeClient {
	private static final Gson GSON = new Gson();

	private final String baseUrl;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(2))
			.build();

	BridgeClient(int port) {
		this.baseUrl = "http://127.0.0.1:" + port;
	}

	/** True if the mod's bridge answers. */
	boolean isUp() {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
					.timeout(Duration.ofSeconds(2))
					.GET()
					.build();
			return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Runs an action registered in the mod's ActionRegistry. */
	JsonObject action(String name, JsonObject params) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/action/" + name))
				.timeout(Duration.ofSeconds(60))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(params == null ? new JsonObject() : params)))
				.build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
		JsonObject body = res.body().isBlank() ? new JsonObject() : JsonParser.parseString(res.body()).getAsJsonObject();
		if (res.statusCode() != 200) {
			String msg = body.has("error") ? body.get("error").getAsString() : ("HTTP " + res.statusCode());
			throw new IOException("Action '" + name + "' failed: " + msg);
		}
		return body;
	}
}
