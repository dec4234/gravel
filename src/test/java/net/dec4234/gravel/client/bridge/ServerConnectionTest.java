package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerConnectionTest {
	@BeforeEach
	void reset() {
		ServerConnection.clear();
	}

	@Test
	void noAttemptGivesNull() {
		assertNull(ServerConnection.attemptJson());
	}

	@Test
	void disconnectWhileConnectingFailsTheAttempt() {
		long id = ServerConnection.begin("localhost");
		ServerConnection.connecting(id);
		ServerConnection.disconnected("Failed to connect to the server", "Connection refused", null);

		JsonObject a = ServerConnection.attemptJson();
		assertEquals("failed", a.get("state").getAsString());
		JsonObject d = a.getAsJsonObject("disconnect");
		assertEquals("Connection refused", d.get("reason").getAsString());
		assertEquals("localhost", d.get("address").getAsString());
	}

	@Test
	void disconnectWhileLeavingOldWorldIsNotTheAttempts() {
		ServerConnection.begin("localhost");
		ServerConnection.disconnected("Disconnected", "old server", null);

		assertEquals("leaving", ServerConnection.attemptJson().get("state").getAsString());
		assertEquals(1, ServerConnection.read(0).getAsJsonArray("disconnects").size());
	}

	@Test
	void kickAfterJoinIsDisconnected() {
		long id = ServerConnection.begin("localhost");
		ServerConnection.connecting(id);
		ServerConnection.joined("localhost");
		assertEquals("joined", ServerConnection.attemptJson().get("state").getAsString());

		ServerConnection.disconnected("Disconnected", "Kicked by an operator", "multiplayer.disconnect.kicked");
		assertEquals("disconnected", ServerConnection.attemptJson().get("state").getAsString());
	}

	@Test
	void errorOnlyChangesTheCurrentAttempt() {
		long first = ServerConnection.begin("a");
		long second = ServerConnection.begin("b");
		ServerConnection.error(first, "old");
		assertEquals("leaving", ServerConnection.attemptJson().get("state").getAsString());
		ServerConnection.error(second, "new");
		assertEquals("new", ServerConnection.attemptJson().get("error").getAsString());
	}

	@Test
	void onlineModeRejectionsGetAHint() {
		assertNotNull(ServerConnection.hintFor("multiplayer.disconnect.unverified_username"));
		assertNotNull(ServerConnection.hintFor("disconnect.loginFailedInfo"));
		assertNull(ServerConnection.hintFor("multiplayer.disconnect.kicked"));
		assertNull(ServerConnection.hintFor(null));
	}

	@Test
	void readReturnsOnlyNewerDisconnects() {
		ServerConnection.disconnected("A", "one", null);
		long after = ServerConnection.latestDisconnectId();
		ServerConnection.disconnected("B", "two", null);

		var list = ServerConnection.read(after).getAsJsonArray("disconnects");
		assertEquals(1, list.size());
		assertEquals("two", list.get(0).getAsJsonObject().get("reason").getAsString());
	}
}
