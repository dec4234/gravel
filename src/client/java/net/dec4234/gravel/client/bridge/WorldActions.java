package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dec4234.gravel.Gravel;
import net.dec4234.gravel.client.mixin.CreateWorldScreenInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Singleplayer world actions. Loading a world takes many ticks, so {@code join_world} only schedules the
 * work and returns. The caller polls {@code status} until {@code inWorld} is true, or {@code worldError} is set.
 * Then the caller can send {@code setup_world} to set game rules, the game mode and data packs.
 */
public final class WorldActions {
	static final List<String> WORLD_TYPES = List.of("normal", "flat", "void");

	private static volatile String lastError;

	private WorldActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("join_world", WorldActions::joinWorld);
		registry.registerAsync("setup_world", WorldActions::setupWorld);
	}

	public static String lastError() {
		return lastError;
	}

	/** The save folder name of the open singleplayer world, or null. */
	public static String currentWorld(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			return null;
		}
		Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		return root.getFileName() == null ? null : root.getFileName().toString();
	}

	private static JsonObject joinWorld(Minecraft mc, JsonObject params) {
		String name = params.has("name") ? params.get("name").getAsString().trim() : "";
		if (name.isEmpty()) {
			throw new IllegalArgumentException("Parameter 'name' is required");
		}
		boolean recreate = params.has("recreate") && params.get("recreate").getAsBoolean();
		CreateOptions options = CreateOptions.from(params);

		JsonObject result = new JsonObject();
		result.addProperty("world", name);

		if (!recreate && name.equals(currentWorld(mc)) && mc.player != null) {
			result.addProperty("alreadyInWorld", true);
			if (options.hasCreateOnly()) {
				result.addProperty("warning", CreateOptions.NOT_USED);
			}
			return result;
		}

		LevelStorageSource saves = mc.getLevelSource();
		boolean exists = saves.levelExists(name);
		result.addProperty("existed", exists);
		result.addProperty("wasInWorld", mc.level != null || mc.getSingleplayerServer() != null);
		result.addProperty("action", recreate ? "recreate" : (exists ? "open" : "create"));
		if (exists && !recreate && options.hasCreateOnly()) {
			result.addProperty("warning", CreateOptions.NOT_USED);
		}

		lastError = null;
		// Leave the current world first, then open on a later tick so the client state is clean.
		mc.execute(() -> {
			try {
				if (mc.level != null || mc.getSingleplayerServer() != null) {
					mc.disconnectWithSavingScreen();
				}
				mc.execute(() -> openOrCreate(mc, name, recreate, options));
			} catch (Exception e) {
				fail("Could not leave the current world", e);
			}
		});

		result.addProperty("scheduled", true);
		return result;
	}

	private static void openOrCreate(Minecraft mc, String name, boolean recreate, CreateOptions options) {
		try {
			LevelStorageSource saves = mc.getLevelSource();
			if (recreate && saves.levelExists(name)) {
				try (LevelStorageSource.LevelStorageAccess access = saves.createAccess(name)) {
					access.deleteLevel();
				}
				Gravel.LOGGER.info("Deleted world '{}'", name);
			}

			if (saves.levelExists(name)) {
				mc.createWorldOpenFlows().openWorld(name, () -> {
					lastError = "Opening world '" + name + "' was canceled";
					mc.gui.setScreen(new TitleScreen());
				});
			} else {
				createWorld(mc, name, options);
			}
		} catch (Exception e) {
			fail("Could not open or create world '" + name + "'", e);
		}
	}

	private static void createWorld(Minecraft mc, String name, CreateOptions options) {
		CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen()));
		if (!(mc.gui.screen() instanceof CreateWorldScreen screen)) {
			throw new IllegalStateException("CreateWorldScreen did not open");
		}

		WorldCreationUiState state = screen.getUiState();
		state.setName(name);
		if (!name.equals(state.getTargetFolder())) {
			throw new IllegalStateException("World folder would be '" + state.getTargetFolder()
					+ "' instead of '" + name + "' (name not usable as a folder name?)");
		}
		state.setGameMode(options.selectedGameMode());
		state.setAllowCommands(true);
		applyWorldType(state, options.worldType());
		if (options.seed() != null) {
			state.setSeed(options.seed());
		}

		((CreateWorldScreenInvoker) screen).gravel$onCreate();
		Gravel.LOGGER.info("Creating world '{}' (type {}, seed {})", name,
				options.worldType() == null ? "normal" : options.worldType(), options.seed() == null ? "random" : options.seed());
	}

	/** Options that apply when a world is created. The game mode also applies later, in setup_world. */
	record CreateOptions(String worldType, String seed, String gameMode) {
		static final String NOT_USED = "world_type, seed and the hardcore game mode apply only to a new world. The "
				+ "world exists, so they are not used. Set recreate to true to make the world again with them.";

		static CreateOptions from(JsonObject params) {
			String type = optString(params, "world_type");
			if (type != null) {
				type = type.trim().toLowerCase(Locale.ROOT);
				if (!WORLD_TYPES.contains(type)) {
					throw new IllegalArgumentException("Unknown world_type '" + type + "'. Use normal, flat or void.");
				}
			}
			String mode = optString(params, "game_mode");
			if (mode != null) {
				mode = mode.trim().toLowerCase(Locale.ROOT);
				if (!mode.equals("hardcore") && GameType.byName(mode, null) == null) {
					throw new IllegalArgumentException("Unknown game_mode '" + mode
							+ "'. Use survival, creative, adventure, spectator or hardcore.");
				}
			}
			String seed = optString(params, "seed");
			if (seed != null && seed.isBlank()) {
				seed = null;
			}
			return new CreateOptions(type, seed, mode);
		}

		boolean hasCreateOnly() {
			return worldType != null || seed != null || "hardcore".equals(gameMode);
		}

		/** The create screen knows only survival, creative and hardcore. setup_world sets the other modes later. */
		WorldCreationUiState.SelectedGameMode selectedGameMode() {
			if (gameMode == null) {
				return WorldCreationUiState.SelectedGameMode.CREATIVE;
			}
			return switch (gameMode) {
				case "hardcore" -> WorldCreationUiState.SelectedGameMode.HARDCORE;
				case "creative" -> WorldCreationUiState.SelectedGameMode.CREATIVE;
				default -> WorldCreationUiState.SelectedGameMode.SURVIVAL;
			};
		}
	}

	/** Flat uses the vanilla "Superflat" preset. Void is flat with the vanilla "The Void" layers (a small platform at spawn). */
	private static void applyWorldType(WorldCreationUiState state, String type) {
		if (type == null || type.equals("normal")) {
			return;
		}
		RegistryAccess.Frozen registries = state.getSettings().worldgenLoadContext();
		Holder<WorldPreset> flat = registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT);
		state.setWorldType(new WorldCreationUiState.WorldTypeEntry(flat));
		if (type.equals("void")) {
			FlatLevelGeneratorSettings voidSettings = registries.lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
					.getOrThrow(FlatLevelGeneratorPresets.THE_VOID).value().settings();
			state.updateDimensions(PresetEditor.flatWorldConfigurator(voidSettings));
		}
	}

	// ---------------------------------------------------------------- setup_world

	/**
	 * Sets up the open singleplayer world for a test: copies data packs into the world and loads them (like
	 * /reload), sets game rules, and sets the player's game mode.
	 */
	private static CompletableFuture<JsonObject> setupWorld(Minecraft mc, JsonObject params) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) {
			throw new IllegalStateException("Not in a singleplayer world. Use join_world first.");
		}
		UUID playerId = mc.player.getUUID();
		String mode = optString(params, "game_mode");
		GameType gameType = null;
		if (mode != null && !mode.trim().equalsIgnoreCase("hardcore")) {
			gameType = GameType.byName(mode.trim().toLowerCase(Locale.ROOT), null);
			if (gameType == null) {
				throw new IllegalArgumentException("Unknown game_mode '" + mode + "'");
			}
		}

		List<String> copied = new ArrayList<>();
		if (params.has("datapacks") && params.get("datapacks").isJsonArray()) {
			Path target = server.getWorldPath(LevelResource.DATAPACK_DIR);
			for (JsonElement e : params.getAsJsonArray("datapacks")) {
				copied.add(copyDataPack(Path.of(e.getAsString()), target));
			}
		}

		CompletableFuture<JsonArray> packs = copied.isEmpty()
				? CompletableFuture.completedFuture(null)
				: server.submit(() -> ReloadActions.reloadData(server)).thenCompose(f -> f);

		GameType finalGameType = gameType;
		JsonObject result = new JsonObject();
		return packs.thenCompose(selected -> server.submit(() -> {
			if (selected != null) {
				JsonArray added = new JsonArray();
				for (String folder : copied) {
					String id = "file/" + folder;
					JsonObject p = new JsonObject();
					p.addProperty("id", id);
					p.addProperty("enabled", server.getPackRepository().getSelectedIds().contains(id));
					added.add(p);
				}
				result.add("datapacks", added);
				result.add("enabledPacks", selected);
			}
			if (params.has("gamerules") && params.get("gamerules").isJsonObject()) {
				result.add("gamerules", setGameRules(server, params.getAsJsonObject("gamerules")));
			}
			if (finalGameType != null) {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player == null) {
					throw new IllegalStateException("The server has no player " + playerId);
				}
				player.setGameMode(finalGameType);
				result.addProperty("gameMode", finalGameType.getName());
			}
			return result;
		}));
	}

	/** Copies a data pack folder or .zip into the world's datapacks folder. Returns its name in that folder. */
	static String copyDataPack(Path source, Path datapacksDir) {
		if (!Files.exists(source)) {
			throw new IllegalArgumentException("Data pack not found: " + source);
		}
		boolean folder = Files.isDirectory(source);
		if (folder && !Files.isRegularFile(source.resolve("pack.mcmeta"))) {
			throw new IllegalArgumentException("Data pack folder has no pack.mcmeta: " + source);
		}
		if (!folder && !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
			throw new IllegalArgumentException("A data pack must be a folder or a .zip file: " + source);
		}
		String name = source.getFileName().toString();
		Path target = datapacksDir.resolve(name);
		try {
			Files.createDirectories(datapacksDir);
			deleteRecursively(target);
			if (folder) {
				try (Stream<Path> files = Files.walk(source)) {
					for (Path from : files.toList()) {
						Path to = target.resolve(source.relativize(from).toString());
						if (Files.isDirectory(from)) {
							Files.createDirectories(to);
						} else {
							Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
						}
					}
				}
			} else {
				Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not copy data pack " + source, e);
		}
		return name;
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		try (Stream<Path> files = Files.walk(path)) {
			for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}

	/** Sets game rules by name (the old camelCase names work too). Runs on the server thread. */
	private static JsonObject setGameRules(MinecraftServer server, JsonObject rules) {
		GameRules gameRules = server.getGameRules();
		Map<String, GameRule<?>> byId = new HashMap<>();
		gameRules.availableRules().forEach(rule -> byId.put(rule.getIdentifier().toString(), rule));
		// Find all rules first, so a bad name changes no rule.
		Map<String, GameRuleNames.Resolved> resolved = new java.util.LinkedHashMap<>();
		for (String name : rules.keySet()) {
			GameRuleNames.Resolved r = GameRuleNames.resolve(name);
			if (!byId.containsKey(r.id())) {
				throw new IllegalArgumentException("Unknown game rule '" + name + "'. Known rules: "
						+ byId.keySet().stream().sorted().toList());
			}
			resolved.put(name, r);
		}
		JsonObject out = new JsonObject();
		for (Map.Entry<String, GameRuleNames.Resolved> entry : resolved.entrySet()) {
			GameRuleNames.Resolved r = entry.getValue();
			String value = GameRuleNames.value(r, rules.get(entry.getKey()).getAsString());
			out.addProperty(r.id(), setRule(server, gameRules, byId.get(r.id()), value));
		}
		return out;
	}

	private static <T> String setRule(MinecraftServer server, GameRules gameRules, GameRule<T> rule, String value) {
		T parsed = rule.deserialize(value).getOrThrow(message ->
				new IllegalArgumentException("Bad value '" + value + "' for game rule " + rule.getIdentifier() + ": " + message));
		gameRules.set(rule, parsed, server);
		return gameRules.getAsString(rule);
	}

	private static String optString(JsonObject params, String name) {
		return params.has(name) && !params.get(name).isJsonNull() ? params.get(name).getAsString() : null;
	}

	private static void fail(String message, Exception e) {
		Gravel.LOGGER.error(message, e);
		lastError = message + ": " + e;
	}
}
