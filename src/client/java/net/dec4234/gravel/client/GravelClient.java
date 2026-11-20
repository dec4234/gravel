package net.dec4234.gravel.client;

import net.dec4234.gravel.Gravel;
import net.dec4234.gravel.client.bridge.ActionRegistry;
import net.dec4234.gravel.client.bridge.BridgeServer;
import net.dec4234.gravel.client.bridge.BuiltinActions;
import net.dec4234.gravel.client.bridge.ChatActions;
import net.dec4234.gravel.client.bridge.ChatLog;
import net.dec4234.gravel.client.bridge.WorldActions;
import net.fabricmc.api.ClientModInitializer;

public class GravelClient implements ClientModInitializer {
	public static final ActionRegistry ACTIONS = new ActionRegistry();

	@Override
	public void onInitializeClient() {
		BuiltinActions.registerAll(ACTIONS);
		WorldActions.registerAll(ACTIONS);
		ChatActions.registerAll(ACTIONS);
		ChatLog.register();

		int port = BridgeServer.configuredPort();
		try {
			new BridgeServer(ACTIONS).start(port);
		} catch (Exception e) {
			Gravel.LOGGER.error("Could not start Gravel bridge on port {}", port, e);
		}
	}
}
