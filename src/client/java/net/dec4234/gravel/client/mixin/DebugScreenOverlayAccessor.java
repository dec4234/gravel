package net.dec4234.gravel.client.mixin;

import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/** Lets Gravel read the F3 screen lines without drawing the screen. */
@Mixin(DebugScreenOverlay.class)
public interface DebugScreenOverlayAccessor {
	@Accessor("lastPos")
	ChunkPos gravel$getLastPos();

	@Accessor("lastPos")
	void gravel$setLastPos(ChunkPos pos);

	@Accessor("serverChunk")
	CompletableFuture<LevelChunk> gravel$getServerChunkFuture();

	@Invoker("getLevel")
	Level gravel$getLevel();

	@Invoker("getClientChunk")
	LevelChunk gravel$getClientChunk();

	@Invoker("getServerChunk")
	LevelChunk gravel$getServerChunk();
}
