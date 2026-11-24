package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/** Takes a screenshot of the game view (the world, the HUD and the open screen) as a PNG. */
public final class ScreenshotActions {
	private ScreenshotActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.registerAsync("screenshot", ScreenshotActions::screenshot);
	}

	private static CompletableFuture<JsonObject> screenshot(Minecraft mc, JsonObject params) {
		RenderTarget target = mc.gameRenderer.mainRenderTarget();
		Screen screen = mc.gui.screen();
		String screenName = screen == null ? null : screen.getClass().getSimpleName();
		int guiWidth = mc.getWindow().getGuiScaledWidth();
		int guiHeight = mc.getWindow().getGuiScaledHeight();
		CompletableFuture<JsonObject> result = new CompletableFuture<>();
		// The copy from the GPU is done later. The game gives the image to this callback when it is ready.
		Screenshot.takeScreenshot(target, image -> {
			try (NativeImage img = image) {
				Path file = Files.createTempFile("gravel-screenshot", ".png");
				try {
					img.writeToFile(file);
					JsonObject o = new JsonObject();
					o.addProperty("width", img.getWidth());
					o.addProperty("height", img.getHeight());
					o.addProperty("screen", screenName);
					o.addProperty("guiWidth", guiWidth);
					o.addProperty("guiHeight", guiHeight);
					o.addProperty("png", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
					result.complete(o);
				} finally {
					Files.deleteIfExists(file);
				}
			} catch (Exception e) {
				result.completeExceptionally(e);
			}
		});
		return result;
	}
}
