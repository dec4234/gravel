package net.dec4234.gravel.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Small helpers to build JSON Schemas for tool inputs and to read tool arguments. */
final class Schema {
	private final JsonObject properties = new JsonObject();
	private final JsonArray required = new JsonArray();

	private Schema() {
	}

	static Schema object() {
		return new Schema();
	}

	Schema prop(String name, String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("description", description);
		properties.add(name, p);
		return this;
	}

	Schema requiredProp(String name, String type, String description) {
		prop(name, type, description);
		required.add(name);
		return this;
	}

	JsonObject build() {
		JsonObject o = new JsonObject();
		o.addProperty("type", "object");
		o.add("properties", properties);
		if (!required.isEmpty()) {
			o.add("required", required);
		}
		return o;
	}

	static String optString(JsonObject args, String name) {
		JsonElement e = args.get(name);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	static boolean optBool(JsonObject args, String name, boolean fallback) {
		JsonElement e = args.get(name);
		return e == null || e.isJsonNull() ? fallback : e.getAsBoolean();
	}

	static int optInt(JsonObject args, String name, int fallback) {
		JsonElement e = args.get(name);
		return e == null || e.isJsonNull() ? fallback : e.getAsInt();
	}
}
