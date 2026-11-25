package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** The mods that Fabric Loader loaded: id, version, jar, dependencies, entrypoints and mixin configs. */
final class ModInfo {
	private ModInfo() {
	}

	/**
	 * @param full   also give dependencies, entrypoints and mixin configs
	 * @param filter only mods whose id or name has this text (null or blank for all)
	 */
	static JsonObject json(boolean full, String filter) {
		FabricLoader loader = FabricLoader.getInstance();
		String f = filter == null || filter.isBlank() ? null : filter.trim().toLowerCase(Locale.ROOT);
		List<ModContainer> mods = new ArrayList<>(loader.getAllMods());
		mods.sort(Comparator.comparing(m -> m.getMetadata().getId()));

		JsonArray list = new JsonArray();
		for (ModContainer mod : mods) {
			ModMetadata meta = mod.getMetadata();
			if (f != null && !meta.getId().toLowerCase(Locale.ROOT).contains(f)
					&& !meta.getName().toLowerCase(Locale.ROOT).contains(f)) {
				continue;
			}
			list.add(modJson(mod, full));
		}

		JsonObject o = new JsonObject();
		o.addProperty("loaderVersion", loader.getModContainer("fabricloader")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(null));
		o.addProperty("developmentEnvironment", loader.isDevelopmentEnvironment());
		o.addProperty("total", mods.size());
		o.addProperty("shown", list.size());
		o.add("list", list);
		return o;
	}

	private static JsonObject modJson(ModContainer mod, boolean full) {
		ModMetadata meta = mod.getMetadata();
		JsonObject o = new JsonObject();
		o.addProperty("id", meta.getId());
		o.addProperty("name", meta.getName());
		o.addProperty("version", meta.getVersion().getFriendlyString());
		o.addProperty("type", meta.getType());
		ModOrigin origin = mod.getOrigin();
		switch (origin.getKind()) {
			case PATH -> o.add("paths", strings(origin.getPaths().stream().map(Path::toString).toList()));
			case NESTED -> {
				o.addProperty("nestedIn", origin.getParentModId());
				o.addProperty("nestedPath", origin.getParentSubLocation());
			}
			default -> o.addProperty("origin", origin.toString());
		}
		if (!meta.getProvides().isEmpty()) {
			o.add("provides", strings(meta.getProvides()));
		}
		if (!full) {
			return o;
		}

		JsonArray deps = new JsonArray();
		for (ModDependency dep : meta.getDependencies()) {
			JsonObject d = new JsonObject();
			d.addProperty("kind", dep.getKind().getKey());
			d.addProperty("modId", dep.getModId());
			d.addProperty("versions", dep.getVersionRequirements().stream()
					.map(VersionPredicate::toString).collect(Collectors.joining(" || ")));
			FabricLoader.getInstance().getModContainer(dep.getModId()).ifPresentOrElse(
					found -> {
						d.addProperty("foundVersion", found.getMetadata().getVersion().getFriendlyString());
						d.addProperty("matches", dep.matches(found.getMetadata().getVersion()));
					},
					() -> d.addProperty("foundVersion", (String) null));
			deps.add(d);
		}
		o.add("dependencies", deps);

		// The public API has no entrypoint list without making the entrypoint objects, so read fabric.mod.json.
		readModJson(mod).ifPresent(json -> {
			if (json.has("entrypoints") && json.get("entrypoints").isJsonObject()) {
				JsonObject entrypoints = new JsonObject();
				for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("entrypoints").entrySet()) {
					entrypoints.add(e.getKey(), entrypointValues(e.getValue()));
				}
				o.add("entrypoints", entrypoints);
			}
			if (json.has("mixins") && json.get("mixins").isJsonArray()) {
				JsonArray mixins = new JsonArray();
				for (JsonElement m : json.getAsJsonArray("mixins")) {
					if (m.isJsonPrimitive()) {
						mixins.add(m.getAsString());
					} else if (m.isJsonObject() && m.getAsJsonObject().has("config")) {
						mixins.add(m.getAsJsonObject().get("config").getAsString());
					}
				}
				o.add("mixinConfigs", mixins);
			}
		});
		return o;
	}

	/** An entrypoint is a string "a.b.C" or an object {"adapter": "kotlin", "value": "a.b.C"}. */
	private static JsonArray entrypointValues(JsonElement value) {
		JsonArray out = new JsonArray();
		List<JsonElement> items = new ArrayList<>();
		if (value.isJsonArray()) {
			value.getAsJsonArray().forEach(items::add);
		} else {
			items.add(value);
		}
		for (JsonElement item : items) {
			if (item.isJsonPrimitive()) {
				out.add(item.getAsString());
			} else if (item.isJsonObject() && item.getAsJsonObject().has("value")) {
				JsonObject obj = item.getAsJsonObject();
				String adapter = obj.has("adapter") ? obj.get("adapter").getAsString() : "default";
				out.add(obj.get("value").getAsString() + ("default".equals(adapter) ? "" : " (adapter " + adapter + ")"));
			}
		}
		return out;
	}

	private static Optional<JsonObject> readModJson(ModContainer mod) {
		Optional<Path> path = mod.findPath("fabric.mod.json");
		if (path.isEmpty()) {
			return Optional.empty();
		}
		try (Reader r = Files.newBufferedReader(path.get(), StandardCharsets.UTF_8)) {
			JsonElement e = JsonParser.parseReader(r);
			return e.isJsonObject() ? Optional.of(e.getAsJsonObject()) : Optional.empty();
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	private static JsonArray strings(Iterable<String> values) {
		JsonArray a = new JsonArray();
		values.forEach(a::add);
		return a;
	}
}
