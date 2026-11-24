package net.dec4234.gravel.client.mixin;

import net.dec4234.gravel.client.bridge.InteractActions;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * While the attack tool holds the attack button, the game continues to break the target block. The game only does
 * this when the mouse is grabbed, and the mouse is not grabbed when the game window does not have focus.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@ModifyArg(method = "handleKeybinds",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;continueAttack(Z)V"))
	private boolean gravel$holdAttack(boolean leftClick) {
		return leftClick || InteractActions.attackHeld((Minecraft) (Object) this);
	}
}
