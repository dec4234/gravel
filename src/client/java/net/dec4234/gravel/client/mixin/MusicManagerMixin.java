package net.dec4234.gravel.client.mixin;

import net.dec4234.gravel.client.GravelClient;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.Music;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops the game music from starting, unless the game started with -Dgravel.music=true. */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
	@Inject(method = "startPlaying", at = @At("HEAD"), cancellable = true)
	private void gravel$blockMusic(Music music, CallbackInfo ci) {
		if (!GravelClient.musicEnabled()) {
			ci.cancel();
		}
	}
}
