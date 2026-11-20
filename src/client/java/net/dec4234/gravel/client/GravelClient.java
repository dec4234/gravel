package net.dec4234.gravel.client;

import net.dec4234.gravel.Gravel;
import net.dec4234.gravel.client.bridge.ActionRegistry;
import net.dec4234.gravel.client.bridge.BridgeServer;
import net.dec4234.gravel.client.bridge.BuiltinActions;
import net.dec4234.gravel.client.bridge.ChatActions;
import net.dec4234.gravel.client.bridge.ChatLog;
import net.dec4234.gravel.client.bridge.ChunkActions;
import net.dec4234.gravel.client.bridge.DebugScreenActions;
import net.dec4234.gravel.client.bridge.InventoryActions;
import net.dec4234.gravel.client.bridge.OptionsActions;
import net.dec4234.gravel.client.bridge.ScreenActions;
import net.dec4234.gravel.client.bridge.ServerActions;
import net.dec4234.gravel.client.bridge.SurroundingsActions;
import net.dec4234.gravel.client.bridge.TeleportActions;
import net.dec4234.gravel.client.bridge.WorldActions;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class GravelClient implements ClientModInitializer {
	public static final ActionRegistry ACTIONS = new ActionRegistry();

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
