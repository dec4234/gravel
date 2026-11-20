package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;

/** Chunk actions: how many chunks the client has loaded and renders around the player. */
public final class ChunkActions {
	private ChunkActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("chunk_info", ChunkActions::chunkInfo);
	}

	private static JsonObject chunkInfo(Minecraft mc, JsonObject params) {
		if (mc.player == null || mc.level == null) {
			throw new IllegalStateException("Not in a world. Join a world first.");
		}
		LevelRenderer renderer = mc.levelRenderer;
		int playerChunkX = mc.player.getBlockX() >> 4;
		int playerChunkZ = mc.player.getBlockZ() >> 4;

		// Visible sections are the 16x16x16 sections drawn in the last frame (the "C:" value on the F3 screen).
		// Count the chunk columns they belong to, and the farthest one from the player.
		LongOpenHashSet columns = new LongOpenHashSet();
		int farthest = 0;
		for (SectionRenderDispatcher.RenderSection section : renderer.visibleSections()) {
			BlockPos origin = section.getRenderOrigin();
			int cx = origin.getX() >> 4;
			int cz = origin.getZ() >> 4;
			if (columns.add(((long) cx << 32) | (cz & 0xFFFFFFFFL))) {
				farthest = Math.max(farthest, Math.max(Math.abs(cx - playerChunkX), Math.abs(cz - playerChunkZ)));
			}
		}

		int effective = mc.options.getEffectiveRenderDistance();
		JsonObject o = new JsonObject();
		o.addProperty("renderedChunks", columns.size());
		o.addProperty("renderedSections", renderer.visibleSections().size());
		o.addProperty("farthestRenderedChunkDistance", farthest);
		o.addProperty("loadedChunks", mc.level.getChunkSource().getLoadedChunksCount());
		o.addProperty("renderDistanceOption", mc.options.renderDistance().get());
		o.addProperty("effectiveRenderDistance", effective);
		o.addProperty("simulationDistance", mc.options.simulationDistance().get());
		int side = 2 * effective + 1;
		o.addProperty("chunksInRenderDistanceSquare", side * side);
		o.addProperty("allSectionsRendered", renderer.hasRenderedAllSections());
		o.addProperty("sectionCompileQueue", renderer.sectionRenderDispatcher().getCompileQueueSize());
		o.addProperty("playerChunkX", playerChunkX);
		o.addProperty("playerChunkZ", playerChunkZ);
		return o;
	}
}
