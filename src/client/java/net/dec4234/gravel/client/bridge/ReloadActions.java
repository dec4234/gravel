package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Reload actions: resource packs (like F3+T), data packs in singleplayer (like /reload), and chunks (like F3+A). */
public final class ReloadActions {
	/** The longest wait for the chunks, under the bridge's 30 second limit. */
	static final int MAX_CHUNK_WAIT_MS = 25_000;
	private static final long POLL_MS = 50;

	private ReloadActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.registerAsync("reload_resources", ReloadActions::reload);
		registry.registerAsync("reload_chunks", ReloadActions::reloadChunks);
	}

	private static CompletableFuture<JsonObject> reload(Minecraft mc, JsonObject params) {
		boolean resources = !params.has("resources") || params.get("resources").getAsBoolean();
		boolean data = !params.has("data") || params.get("data").getAsBoolean();
		if (!resources && !data) {
			throw new IllegalArgumentException("Nothing to reload: resources and data are both false.");
		}

		JsonObject result = new JsonObject();
		long start = System.nanoTime();

		IntegratedServer server = mc.getSingleplayerServer();
		CompletableFuture<JsonArray> dataDone = data && server != null
				? server.submit(() -> reloadData(server)).thenCompose(f -> f)
				: CompletableFuture.completedFuture(null);
		if (data && server == null) {
			result.addProperty("data", mc.level == null
					? "skipped: not in a world"
					: "skipped: data packs reload only in singleplayer");
		}

		CompletableFuture<Void> resourcesDone = resources ? mc.reloadResourcePacks() : CompletableFuture.completedFuture(null);
		if (resources) {
			JsonArray packs = new JsonArray();
			mc.getResourcePackRepository().getSelectedIds().forEach(packs::add);
			result.add("resourcePacks", packs);
		}

		return CompletableFuture.allOf(dataDone, resourcesDone).thenApply(v -> {
			if (dataDone.join() != null) {
				result.add("dataPacks", dataDone.join());
			}
			result.addProperty("resourcesReloaded", resources);
			result.addProperty("dataReloaded", data && server != null);
			result.addProperty("ms", (System.nanoTime() - start) / 1_000_000);
			return result;
		});
	}

	/** The same steps as the /reload command: find new packs, keep the selected ones, skip the disabled ones. Runs on the server thread. */
	private static CompletableFuture<JsonArray> reloadData(IntegratedServer server) {
		PackRepository repo = server.getPackRepository();
		List<String> ids = new ArrayList<>(repo.getSelectedIds());
		repo.reload();
		Collection<String> disabled = server.getWorldData().getDataConfiguration().dataPacks().getDisabled();
		for (String id : repo.getAvailableIds()) {
			if (!disabled.contains(id) && !ids.contains(id)) {
				ids.add(id);
			}
		}
		JsonArray packs = new JsonArray();
		ids.forEach(packs::add);
		return server.reloadResources(ids).thenApply(v -> packs);
	}

	/** Rebuilds all chunk sections, like F3+A, and waits until they are all built again or the wait time ends. */
	private static CompletableFuture<JsonObject> reloadChunks(Minecraft mc, JsonObject params) {
		if (mc.level == null) {
			throw new IllegalStateException("Not in a world. Join a world first.");
		}
		JsonObject result = new JsonObject();
		result.addProperty("sectionsBefore", mc.levelExtractor.countRenderedSections());
		ChunkWait wait = new ChunkWait(waitMs(params));
		mc.levelExtractor.allChanged();

		CompletableFuture<JsonObject> done = new CompletableFuture<>();
		if (wait.waitMs == 0) {
			done.complete(finishChunks(mc, result, false, 0));
		} else {
			poll(mc, done, result, wait);
		}
		return done;
	}

	static int waitMs(JsonObject params) {
		int waitMs = params.has("wait_ms") && !params.get("wait_ms").isJsonNull() ? params.get("wait_ms").getAsInt() : 10_000;
		return Math.max(0, Math.min(MAX_CHUNK_WAIT_MS, waitMs));
	}

	/**
	 * The rebuild is done when the compile queue is empty and the count of rendered sections stays the same for
	 * some polls. The queue alone is not enough: it is empty just after the reload, before the sections are queued.
	 */
	static final class ChunkWait {
		static final int STABLE_POLLS = 10;

		final int waitMs;
		final long start = System.nanoTime();
		int lastCount = -1;
		int stablePolls;

		ChunkWait(int waitMs) {
			this.waitMs = waitMs;
		}

		long elapsedMs() {
			return (System.nanoTime() - start) / 1_000_000;
		}

		/** Records one poll and tells if the rebuild is done. */
		boolean update(boolean queueEmpty, int count) {
			stablePolls = queueEmpty && count == lastCount ? stablePolls + 1 : 0;
			lastCount = count;
			return stablePolls >= STABLE_POLLS;
		}
	}

	private static void poll(Minecraft mc, CompletableFuture<JsonObject> done, JsonObject result, ChunkWait wait) {
		CompletableFuture.delayedExecutor(POLL_MS, TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
			try {
				if (mc.level == null) {
					throw new IllegalStateException("Left the world while the chunks reloaded.");
				}
				boolean built = wait.update(mc.levelRenderer.hasRenderedAllSections(), mc.levelExtractor.countRenderedSections());
				long ms = wait.elapsedMs();
				if (built || ms >= wait.waitMs) {
					done.complete(finishChunks(mc, result, built, ms));
				} else {
					poll(mc, done, result, wait);
				}
			} catch (RuntimeException e) {
				done.completeExceptionally(e);
			}
		}));
	}

	private static JsonObject finishChunks(Minecraft mc, JsonObject result, boolean built, long ms) {
		result.addProperty("allBuilt", built);
		result.addProperty("sectionsAfter", mc.levelExtractor.countRenderedSections());
		result.addProperty("compileQueue", mc.levelRenderer.sectionRenderDispatcher().getCompileQueueSize());
		result.addProperty("statistics", mc.levelExtractor.sectionStatistics());
		result.addProperty("ms", ms);
		return result;
	}
}
