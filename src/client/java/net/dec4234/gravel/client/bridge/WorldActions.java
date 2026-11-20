package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import net.dec4234.gravel.Gravel;
import net.dec4234.gravel.client.mixin.CreateWorldScreenInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.nio.file.Path;

/**
 * Singleplayer world actions. Loading a world takes many ticks, so {@code join_world} only schedules the
 * work and returns. The caller polls {@code status} until {@code inWorld} is true, or {@code worldError} is set.
 */
public final class WorldActions {
	private static volatile String lastError;

	private WorldActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("join_world", WorldActions::joinWorld);
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

		JsonObject result = new JsonObject();
		result.addProperty("world", name);

		if (!recreate && name.equals(currentWorld(mc)) && mc.player != null) {
			result.addProperty("alreadyInWorld", true);
			return result;
		}

		LevelStorageSource saves = mc.getLevelSource();
		boolean exists = saves.levelExists(name);
		result.addProperty("existed", exists);
		result.addProperty("wasInWorld", mc.level != null || mc.getSingleplayerServer() != null);
		result.addProperty("action", recreate ? "recreate" : (exists ? "open" : "create"));

		lastError = null;
		// Leave the current world first, then open on a later tick so the client state is clean.
		mc.execute(() -> {
			try {
				if (mc.level != null || mc.getSingleplayerServer() != null) {
					mc.disconnectWithSavingScreen();
				}
				mc.execute(() -> openOrCreate(mc, name, recreate));
			} catch (Exception e) {
				fail("Could not leave the current world", e);
			}
		});

		result.addProperty("scheduled", true);
		return result;
	}

	private static void openOrCreate(Minecraft mc, String name, boolean recreate) {
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
				createWorld(mc, name);
			}
		} catch (Exception e) {
			fail("Could not open or create world '" + name + "'", e);
		}
	}

	private static void createWorld(Minecraft mc, String name) {
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
		state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
		state.setAllowCommands(true);

		((CreateWorldScreenInvoker) screen).gravel$onCreate();
		Gravel.LOGGER.info("Creating world '{}'", name);
	}

	private static void fail(String message, Exception e) {
		Gravel.LOGGER.error(message, e);
		lastError = message + ": " + e;
	}
}
