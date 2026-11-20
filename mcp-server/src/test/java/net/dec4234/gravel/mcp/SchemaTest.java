package net.dec4234.gravel.mcp;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchemaTest {
	@Test
	void buildsObjectSchemaWithPropertiesAndRequired() {
		JsonObject schema = Schema.object()
				.prop("a", "string", "first")
				.requiredProp("b", "integer", "second")
				.build();

		assertEquals("object", schema.get("type").getAsString());
		JsonObject props = schema.getAsJsonObject("properties");
		assertEquals("string", props.getAsJsonObject("a").get("type").getAsString());
		assertEquals("first", props.getAsJsonObject("a").get("description").getAsString());
		assertEquals("integer", props.getAsJsonObject("b").get("type").getAsString());
		assertEquals(1, schema.getAsJsonArray("required").size());
		assertEquals("b", schema.getAsJsonArray("required").get(0).getAsString());
	}

	@Test
	void omitsRequiredWhenNoPropIsRequired() {
		JsonObject schema = Schema.object().prop("a", "string", "x").build();
		assertFalse(schema.has("required"));
		assertTrue(Schema.object().build().getAsJsonObject("properties").isEmpty());
	}

	@Test
	void optionalReadersUseFallbackForMissingOrNull() {
		JsonObject args = new JsonObject();
		args.add("nulled", JsonNull.INSTANCE);

		assertNull(Schema.optString(args, "missing"));
		assertNull(Schema.optString(args, "nulled"));
		assertTrue(Schema.optBool(args, "missing", true));
		assertFalse(Schema.optBool(args, "nulled", false));
		assertEquals(7, Schema.optInt(args, "missing", 7));
		assertEquals(7, Schema.optInt(args, "nulled", 7));
	}

	@Test
	void optionalReadersReturnGivenValues() {
		JsonObject args = new JsonObject();
		args.addProperty("s", "hello");
		args.addProperty("b", true);
		args.addProperty("i", 42);
		args.addProperty("numericString", "13");

		assertEquals("hello", Schema.optString(args, "s"));
		assertTrue(Schema.optBool(args, "b", false));
		assertEquals(42, Schema.optInt(args, "i", 0));
		assertEquals(13, Schema.optInt(args, "numericString", 0));
	}
}
