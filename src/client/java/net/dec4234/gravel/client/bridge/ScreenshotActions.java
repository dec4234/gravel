package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Takes a screenshot of the game view (the world, the HUD and the open screen) as a PNG. With hide_hud, the HUD is
 * hidden (as with F1) for the capture and then shown again.
 */
public final class ScreenshotActions {
	/**
	 * Frames to wait after the HUD is hidden. The game prepares the frame state before it draws, so the first frame
	 * can still show the HUD. Two frames make sure that the captured frame has no HUD.
	 */
	private static final int HIDE_HUD_FRAMES = 2;
	/** Less than the 30 s bridge limit, so the HUD comes back and the error is clear. */
	private static final long HIDE_HUD_TIMEOUT_SECONDS = 10;

	/** Captures that wait for frames. Only the client thread uses this list. */
	private static final List<PendingCapture> PENDING = new ArrayList<>();

	private ScreenshotActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.registerAsync("screenshot", ScreenshotActions::screenshot);
	}

	/** Called by MinecraftMixin at the end of each frame, on the client thread. */
	public static void onFrame(Minecraft mc) {
		if (PENDING.isEmpty()) {
			return;
		}
		for (PendingCapture p : List.copyOf(PENDING)) {
			if (--p.framesLeft <= 0) {
				PENDING.remove(p);
				p.run.run();
			}
		}
	}

	private static CompletableFuture<JsonObject> screenshot(Minecraft mc, JsonObject params) {
		boolean hideHud = params.has("hide_hud") && params.get("hide_hud").getAsBoolean();
		CompletableFuture<JsonObject> result = new CompletableFuture<>();
		Hud hud = mc.gui.hud;
		if (!hideHud || hud.isHidden()) {
			capture(mc, hideHud, result);
			return result;
		}
		hud.toggle();
		PENDING.add(new PendingCapture(HIDE_HUD_FRAMES, result, () -> {
			try {
				capture(mc, true, result);
			} finally {
				// The capture copies the frame now, so the HUD can come back before the copy is read.
				if (hud.isHidden()) {
					hud.toggle();
				}
			}
		}));
		// If no frame comes (for example, the window is minimized), stop and show the HUD again.
		result.orTimeout(HIDE_HUD_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((r, e) -> {
			if (e != null) {
				mc.execute(() -> {
					if (PENDING.removeIf(p -> p.result == result) && hud.isHidden()) {
						hud.toggle();
					}
				});
			}
		});
		return result;
	}

	private static void capture(Minecraft mc, boolean hudHidden, CompletableFuture<JsonObject> result) {
		RenderTarget target = mc.gameRenderer.mainRenderTarget();
		Screen screen = mc.gui.screen();
		String screenName = screen == null ? null : screen.getClass().getSimpleName();
		int guiWidth = mc.getWindow().getGuiScaledWidth();
		int guiHeight = mc.getWindow().getGuiScaledHeight();
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
					o.addProperty("hudHidden", hudHidden);
					o.addProperty("png", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
					result.complete(o);
				} finally {
					Files.deleteIfExists(file);
				}
			} catch (Exception e) {
				result.completeExceptionally(e);
			}
		});
	}

	private static final class PendingCapture {
		int framesLeft;
		final CompletableFuture<JsonObject> result;
		final Runnable run;

		PendingCapture(int framesLeft, CompletableFuture<JsonObject> result, Runnable run) {
			this.framesLeft = framesLeft;
			this.result = result;
			this.run = run;
		}
	}
}
