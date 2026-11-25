package net.dec4234.gravel.client;

import net.dec4234.gravel.Gravel;
import net.dec4234.gravel.client.bridge.ActionRegistry;
import net.dec4234.gravel.client.bridge.BridgeServer;
import net.dec4234.gravel.client.bridge.BuiltinActions;
import net.dec4234.gravel.client.bridge.ChatActions;
import net.dec4234.gravel.client.bridge.ChatLog;
import net.dec4234.gravel.client.bridge.ChunkActions;
import net.dec4234.gravel.client.bridge.DebugScreenActions;
import net.dec4234.gravel.client.bridge.HudActions;
import net.dec4234.gravel.client.bridge.InputActions;
import net.dec4234.gravel.client.bridge.InventoryActions;
import net.dec4234.gravel.client.bridge.InteractActions;
import net.dec4234.gravel.client.bridge.MouseActions;
import net.dec4234.gravel.client.bridge.MovementActions;
import net.dec4234.gravel.client.bridge.OptionsActions;
import net.dec4234.gravel.client.bridge.PacketActions;
import net.dec4234.gravel.client.bridge.RegistryActions;
import net.dec4234.gravel.client.bridge.ReloadActions;
import net.dec4234.gravel.client.bridge.Pathfinding;
import net.dec4234.gravel.client.bridge.ScreenActions;
import net.dec4234.gravel.client.bridge.ScreenshotActions;
import net.dec4234.gravel.client.bridge.ServerActions;
import net.dec4234.gravel.client.bridge.SurroundingsActions;
import net.dec4234.gravel.client.bridge.TeleportActions;
import net.dec4234.gravel.client.bridge.WaitActions;
import net.dec4234.gravel.client.bridge.WidgetActions;
import net.dec4234.gravel.client.bridge.WorldActions;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class GravelClient implements ClientModInitializer {
	public static final ActionRegistry ACTIONS = new ActionRegistry();

	/** Music is off unless the game starts with -Dgravel.music=true (launch_minecraft with music: true). */
	public static boolean musicEnabled() {
		return Boolean.getBoolean("gravel.music");
	}

	@Override
	public void onInitializeClient() {
		BuiltinActions.registerAll(ACTIONS);
		WorldActions.registerAll(ACTIONS);
		ServerActions.registerAll(ACTIONS);
		ChatActions.registerAll(ACTIONS);
		InventoryActions.registerAll(ACTIONS);
		ScreenActions.registerAll(ACTIONS);
		OptionsActions.registerAll(ACTIONS);
		ChunkActions.registerAll(ACTIONS);
		TeleportActions.registerAll(ACTIONS);
		SurroundingsActions.registerAll(ACTIONS);
		DebugScreenActions.registerAll(ACTIONS);
		HudActions.registerAll(ACTIONS);
		ScreenshotActions.registerAll(ACTIONS);
		InputActions.registerAll(ACTIONS);
		WidgetActions.registerAll(ACTIONS);
		MouseActions.registerAll(ACTIONS);
		MovementActions.registerAll(ACTIONS);
		InteractActions.registerAll(ACTIONS);
		ReloadActions.registerAll(ACTIONS);
		RegistryActions.registerAll(ACTIONS);
		PacketActions.registerAll(ACTIONS);
		Pathfinding.registerAll(ACTIONS);
		WaitActions.registerAll(ACTIONS);
		ChatLog.register();

		int port = BridgeServer.configuredPort();
		try {
			BridgeServer bridge = new BridgeServer(ACTIONS);
			bridge.start(port);
			// The HTTP server's dispatcher thread is not a daemon, so it would keep the JVM alive after the game quits.
			ClientLifecycleEvents.CLIENT_STOPPING.register(client -> bridge.stop());
		} catch (Exception e) {
			Gravel.LOGGER.error("Could not start Gravel bridge on port {}", port, e);
		}
	}
}
