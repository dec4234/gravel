package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dec4234.gravel.client.mixin.DebugScreenOverlayAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads the F3 debug screen. Each debug entry (version, FPS, position, biome, looking at, memory, and so on)
 * writes its lines into a collector, the same way the overlay gets the lines it draws.
 */
public final class DebugScreenActions {
	/** Longest time to wait for the integrated server to give the chunk at the player. */
	private static final long SERVER_CHUNK_WAIT_MS = 500;

	private DebugScreenActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("debug_screen", DebugScreenActions::debugScreen);
	}

	private static JsonObject debugScreen(Minecraft mc, JsonObject params) {
		if (mc.player == null || mc.level == null) {
			throw new IllegalStateException("Not in a world. Join a world first.");
		}
		boolean onlyShown = params != null && params.has("only_shown") && params.get("only_shown").getAsBoolean();
		boolean reduced = mc.showOnlyReducedInfo();
		DebugScreenOverlay overlay = mc.getDebugOverlay();
		DebugScreenOverlayAccessor access = (DebugScreenOverlayAccessor) overlay;
		DebugScreenEntryList enabled = mc.debugEntries;

		// The overlay only updates its chunk cache while it is drawn. Do the same update here, so that the
		// chunk values are correct also when the F3 screen is closed.
		Entity camera = mc.getCameraEntity();
		ChunkPos pos = camera != null ? ChunkPos.containing(camera.blockPosition()) : null;
		if (!Objects.equals(access.gravel$getLastPos(), pos)) {
			access.gravel$setLastPos(pos);
			overlay.clearChunkCache();
		}
		Level level = access.gravel$getLevel();
		LevelChunk clientChunk = access.gravel$getClientChunk();
		LevelChunk serverChunk = serverChunk(access);

		JsonArray entries = new JsonArray();
		JsonArray renderToggles = new JsonArray();
		JsonArray notAllowed = new JsonArray();
		JsonArray errors = new JsonArray();
		for (Map.Entry<Identifier, DebugScreenEntry> e : DebugScreenEntries.allEntries().entrySet()) {
			Identifier id = e.getKey();
			DebugScreenEntry entry = e.getValue();
			boolean shown = enabled.isCurrentlyEnabled(id);
			if (onlyShown && !shown) {
				continue;
			}
			if (!entry.isAllowed(reduced)) {
				notAllowed.add(id.toString());
				continue;
			}
			if (!DebugEntryCategory.SCREEN_TEXT.equals(entry.category())) {
				// Renderer entries draw in the world (hitboxes, chunk borders) and have no text.
				JsonObject toggle = new JsonObject();
				toggle.addProperty("id", id.toString());
				toggle.addProperty("status", enabled.getStatus(id).getSerializedName());
				toggle.addProperty("shown", shown);
				renderToggles.add(toggle);
				continue;
			}

			Collector out = new Collector();
			try {
				entry.display(out, level, clientChunk, serverChunk);
			} catch (RuntimeException ex) {
				JsonObject err = new JsonObject();
				err.addProperty("id", id.toString());
				err.addProperty("error", ex.toString());
				errors.add(err);
				continue;
			}
			if (out.isEmpty()) {
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", id.toString());
			o.addProperty("status", enabled.getStatus(id).getSerializedName());
			o.addProperty("shown", shown);
			if (!out.priority.isEmpty()) {
				o.add("priorityLines", toArray(out.priority));
			}
			if (!out.lines.isEmpty()) {
				o.add("lines", toArray(out.lines));
			}
			if (!out.groups.isEmpty()) {
				JsonObject groups = new JsonObject();
				out.groups.forEach((group, lines) -> groups.add(group.toString(), toArray(lines)));
				o.add("groups", groups);
			}
			entries.add(o);
		}

		JsonObject result = new JsonObject();
		result.addProperty("overlayVisible", enabled.isOverlayVisible());
		result.addProperty("reducedDebugInfo", reduced);
		result.addProperty("serverChunkAvailable", serverChunk != null);
		result.add("entries", entries);
		result.add("renderToggles", renderToggles);
		if (!notAllowed.isEmpty()) {
			result.add("notAllowedWithReducedDebugInfo", notAllowed);
		}
		if (!errors.isEmpty()) {
			result.add("errors", errors);
		}
		return result;
	}

	/**
	 * Gets the chunk at the player from the integrated server (singleplayer only). The overlay asks the server
	 * once and shows nothing until the server replies, so wait a short time for the reply. The server thread
	 * does not wait for the client thread, so this wait cannot lock the game.
	 */
	private static LevelChunk serverChunk(DebugScreenOverlayAccessor access) {
		LevelChunk chunk = access.gravel$getServerChunk();
		if (chunk != null) {
			return chunk;
		}
		CompletableFuture<LevelChunk> future = access.gravel$getServerChunkFuture();
		if (future == null) {
			return null;
		}
		try {
			return future.get(SERVER_CHUNK_WAIT_MS, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			return null;
		}
	}

	private static JsonArray toArray(Collection<String> lines) {
		JsonArray a = new JsonArray();
		for (String line : lines) {
			String plain = ChatFormatting.stripFormatting(line);
			a.add(plain == null ? "" : plain);
		}
		return a;
	}

	/** Keeps the lines that one debug entry writes. */
	private static final class Collector implements DebugScreenDisplayer {
		final List<String> priority = new ArrayList<>();
		final List<String> lines = new ArrayList<>();
		final Map<Identifier, List<String>> groups = new LinkedHashMap<>();

		@Override
		public void addPriorityLine(String line) {
			priority.add(line);
		}

		@Override
		public void addLine(String line) {
			lines.add(line);
		}

		@Override
		public void addToGroup(Identifier group, Collection<String> groupLines) {
			groups.computeIfAbsent(group, g -> new ArrayList<>()).addAll(groupLines);
		}

		@Override
		public void addToGroup(Identifier group, String line) {
			groups.computeIfAbsent(group, g -> new ArrayList<>()).add(line);
		}

		boolean isEmpty() {
			return priority.isEmpty() && lines.isEmpty() && groups.isEmpty();
		}
	}
}
