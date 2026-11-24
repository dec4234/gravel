package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.util.LenientJsonParser;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reads and changes game options by the same names that options.txt uses (for example {@code fov},
 * {@code renderDistance}, {@code guiScale}, {@code soundCategory_master}, {@code key_key.jump}).
 *
 * <p>This uses {@code Options.processOptions}, the method that loads and saves options.txt. It visits
 * every option, so all options are reachable, not only the ones with a getter. That method and its
 * visitor interface are not public, so they are called by reflection with a proxy visitor.
 */
public final class OptionsActions {
	private static final String KEY_PREFIX = "key_";

	private OptionsActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("list_options", OptionsActions::listOptions);
		registry.register("set_option", OptionsActions::setOption);
	}

	private static JsonObject listOptions(Minecraft mc, JsonObject params) throws Exception {
		String filter = params.has("filter") ? params.get("filter").getAsString().toLowerCase(Locale.ROOT) : "";
		JsonObject options = new JsonObject();
		for (Map.Entry<String, String> e : readAll(mc.options).entrySet()) {
			if (e.getKey().toLowerCase(Locale.ROOT).contains(filter)) {
				options.addProperty(e.getKey(), e.getValue());
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("count", options.size());
		o.add("options", options);
		return o;
	}

	private static JsonObject setOption(Minecraft mc, JsonObject params) throws Exception {
		String name = params.has("name") ? params.get("name").getAsString().trim() : "";
		if (name.isEmpty() || !params.has("value") || params.get("value").isJsonNull()) {
			throw new IllegalArgumentException("Parameters 'name' and 'value' are required");
		}
		String value = params.get("value").getAsString();

		Options options = mc.options;
		Map<String, String> before = readAll(options);
		if (!before.containsKey(name)) {
			throw new IllegalArgumentException("Unknown option '" + name + "'. Use options with action list to see the names.");
		}

		process(options, new Setter(name, value));

		Map<String, String> after = readAll(options);
		String oldValue = before.get(name);
		String newValue = after.get(name);
		boolean changed = !oldValue.equals(newValue);
		if (changed) {
			applySideEffects(mc, name, newValue);
		}
		options.save();
		if (mc.player != null) {
			options.broadcastOptions();
		}

		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("oldValue", oldValue);
		o.addProperty("value", newValue);
		o.addProperty("changed", changed);
		return o;
	}

	/** Most options apply by themselves when set. These need an extra step, the same as their menu does. */
	private static void applySideEffects(Minecraft mc, String name, String newValue) {
		if (name.startsWith(KEY_PREFIX)) {
			KeyMapping.resetMapping();
		} else if (name.equals("lang")) {
			mc.getLanguageManager().setSelected(newValue);
			mc.reloadResourcePacks();
		}
	}

	private static boolean isPlainValue(Object value) {
		return value instanceof Number || value instanceof Boolean || value instanceof String || value instanceof Enum<?>;
	}

	/**
	 * Every option name with its value as text. Values are the real values the menus show (for example
	 * fov 70, an enum by its name). Other values use the options.txt form.
	 */
	static Map<String, String> readAll(Options options) throws Exception {
		Reader reader = new Reader();
		process(options, reader);
		return reader.values;
	}

	// ---------------------------------------------------------------- visitor plumbing

	/** One visitor for all the {@code FieldAccess.process} overloads. Each returns the value to keep in the field. */
	private interface Visitor {
		<T> void option(String name, OptionInstance<T> option);

		int intValue(String name, int value);

		boolean boolValue(String name, boolean value);

		float floatValue(String name, float value);

		String stringValue(String name, String value);

		<T> T objectValue(String name, T value, Function<String, T> reader, Function<T, String> writer);
	}

	private static Method processOptions;
	private static Class<?> fieldAccess;

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void process(Options options, Visitor visitor) throws Exception {
		if (processOptions == null) {
			fieldAccess = Class.forName("net.minecraft.client.Options$FieldAccess", false, Options.class.getClassLoader());
			Method m = Options.class.getDeclaredMethod("processOptions", fieldAccess);
			m.setAccessible(true);
			processOptions = m;
		}
		Object proxy = Proxy.newProxyInstance(fieldAccess.getClassLoader(), new Class<?>[]{fieldAccess}, (self, method, args) -> {
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "hashCode" -> System.identityHashCode(self);
					case "equals" -> self == args[0];
					default -> "GravelOptionVisitor";
				};
			}
			String name = (String) args[0];
			if (args.length == 4) {
				return visitor.objectValue(name, args[1], (Function) args[2], (Function) args[3]);
			}
			Class<?> type = method.getParameterTypes()[1];
			if (type == OptionInstance.class) {
				visitor.option(name, (OptionInstance) args[1]);
				return null;
			} else if (type == int.class) {
				return visitor.intValue(name, (Integer) args[1]);
			} else if (type == boolean.class) {
				return visitor.boolValue(name, (Boolean) args[1]);
			} else if (type == float.class) {
				return visitor.floatValue(name, (Float) args[1]);
			} else if (type == String.class) {
				return visitor.stringValue(name, (String) args[1]);
			}
			throw new IllegalStateException("Unexpected option visitor method: " + method);
		});
		try {
			processOptions.invoke(options, proxy);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof Exception cause) {
				throw cause;
			}
			throw e;
		}
	}

	/** Collects every option as text. Leaves all values as they are. */
	private static final class Reader implements Visitor {
		final Map<String, String> values = new LinkedHashMap<>();

		@Override
		public <T> void option(String name, OptionInstance<T> option) {
			T value = option.get();
			if (isPlainValue(value)) {
				// The real value, as the menu shows it (options.txt stores some of these in a scaled form).
				values.put(name, value instanceof Enum<?> e ? e.name() : String.valueOf(value));
			} else {
				DataResult<JsonElement> encoded = option.codec().encodeStart(JsonOps.INSTANCE, value);
				values.put(name, encoded.result().map(JsonElement::toString).orElse(String.valueOf(value)));
			}
		}

		@Override
		public int intValue(String name, int value) {
			values.put(name, Integer.toString(value));
			return value;
		}

		@Override
		public boolean boolValue(String name, boolean value) {
			values.put(name, Boolean.toString(value));
			return value;
		}

		@Override
		public float floatValue(String name, float value) {
			values.put(name, Float.toString(value));
			return value;
		}

		@Override
		public String stringValue(String name, String value) {
			values.put(name, value);
			return value;
		}

		@Override
		public <T> T objectValue(String name, T value, Function<String, T> reader, Function<T, String> writer) {
			values.put(name, writer.apply(value));
			return value;
		}
	}

	/** Changes one option and leaves all the others as they are. */
	private static final class Setter implements Visitor {
		private final String target;
		private final String value;

		Setter(String target, String value) {
			this.target = target;
			this.value = value;
		}

		@Override
		public <T> void option(String name, OptionInstance<T> option) {
			if (!name.equals(target)) {
				return;
			}
			T result = parsePlainValue(option.get(), value);
			if (result == null) {
				result = parseWithCodec(name, option);
			}
			option.set(result);
			// OptionInstance.set ignores a value outside the allowed range, and only logs it.
			if (!Objects.equals(option.get(), result)) {
				throw new IllegalArgumentException("Option '" + name + "' does not accept '" + value
						+ "' (out of range or not allowed). It stays " + option.get());
			}
		}

		/** Same parsing as options.txt. A bare word is also tried as a JSON string. */
		private <T> T parseWithCodec(String name, OptionInstance<T> option) {
			DataResult<T> parsed = parseJson(value).map(json -> option.codec().parse(JsonOps.INSTANCE, json))
					.orElseGet(() -> DataResult.error(() -> "not JSON"));
			if (parsed.isError()) {
				DataResult<T> asString = option.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(value));
				if (asString.isSuccess()) {
					parsed = asString;
				}
			}
			return parsed.getOrThrow(message -> new IllegalArgumentException(
					"Bad value '" + value + "' for option '" + name + "': " + message));
		}

		/** Parses the text as the same type as the current value, or gives null if that type is not a plain one. */
		@SuppressWarnings("unchecked")
		private static <T> T parsePlainValue(T current, String text) {
			String v = text.trim();
			try {
				if (current instanceof Integer) {
					return (T) Integer.valueOf(v);
				} else if (current instanceof Double) {
					return (T) Double.valueOf(v);
				} else if (current instanceof Boolean) {
					if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
						throw new IllegalArgumentException("Needs true or false, got '" + text + "'");
					}
					return (T) Boolean.valueOf(v);
				} else if (current instanceof String) {
					return (T) text;
				} else if (current instanceof Enum<?> e) {
					for (Object constant : e.getDeclaringClass().getEnumConstants()) {
						if (((Enum<?>) constant).name().equalsIgnoreCase(v)) {
							return (T) constant;
						}
					}
					return null; // maybe the options.txt form, so let the codec try
				}
			} catch (NumberFormatException ex) {
				throw new IllegalArgumentException("Needs a number, got '" + text + "'");
			}
			return null;
		}

		@Override
		public int intValue(String name, int current) {
			if (!name.equals(target)) {
				return current;
			}
			try {
				return Integer.parseInt(value.trim());
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Option '" + name + "' needs a whole number, got '" + value + "'");
			}
		}

		@Override
		public boolean boolValue(String name, boolean current) {
			if (!name.equals(target)) {
				return current;
			}
			String v = value.trim().toLowerCase(Locale.ROOT);
			if (!v.equals("true") && !v.equals("false")) {
				throw new IllegalArgumentException("Option '" + name + "' needs true or false, got '" + value + "'");
			}
			return v.equals("true");
		}

		@Override
		public float floatValue(String name, float current) {
			if (!name.equals(target)) {
				return current;
			}
			try {
				return Float.parseFloat(value.trim());
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Option '" + name + "' needs a number, got '" + value + "'");
			}
		}

		@Override
		public String stringValue(String name, String current) {
			if (!name.equals(target)) {
				return current;
			}
			if (name.startsWith(KEY_PREFIX)) {
				// The game unbinds a key with a bad name, so check the name first.
				try {
					InputConstants.getKey(value);
				} catch (RuntimeException e) {
					throw new IllegalArgumentException("Unknown key '" + value
							+ "'. Use names like key.keyboard.r, key.keyboard.left.shift or key.mouse.left");
				}
			}
			return value;
		}

		@Override
		public <T> T objectValue(String name, T current, Function<String, T> reader, Function<T, String> writer) {
			if (!name.equals(target)) {
				return current;
			}
			try {
				return reader.apply(value);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("Bad value '" + value + "' for option '" + name + "': " + e.getMessage());
			}
		}

		private static java.util.Optional<JsonElement> parseJson(String text) {
			try {
				return java.util.Optional.of(LenientJsonParser.parse(text));
			} catch (RuntimeException e) {
				return java.util.Optional.empty();
			}
		}
	}
}
