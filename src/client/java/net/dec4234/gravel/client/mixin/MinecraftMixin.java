package net.dec4234.gravel.client.mixin;

import net.dec4234.gravel.client.bridge.InteractActions;
import net.dec4234.gravel.client.bridge.ScreenshotActions;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/**
	 * While the attack tool holds the attack button, the game continues to break the target block. The game only does
	 * this when the mouse is grabbed, and the mouse is not grabbed when the game window does not have focus.
	 */
	@ModifyArg(method = "handleKeybinds",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;continueAttack(Z)V"))
	private boolean gravel$holdAttack(boolean leftClick) {
		return leftClick || InteractActions.attackHeld((Minecraft) (Object) this);
	}

	/** Tells the screenshot action that a frame is done, so it can wait for a frame with no HUD. */
	@Inject(method = "renderFrame", at = @At("TAIL"))
	private void gravel$frameDone(boolean advanceGameTime, CallbackInfo ci) {
		ScreenshotActions.onFrame((Minecraft) (Object) this);
	}
}
