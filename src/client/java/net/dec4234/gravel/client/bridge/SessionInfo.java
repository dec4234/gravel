package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * The account the client runs with. The dev client (gradlew runClient) starts with an offline account
 * ("Player123" with no real access token), which can only join servers with online-mode=false.
 *
 * <p>Online mode is not supported yet. To add it later: start the client with a real Microsoft account
 * (for example pass --username, --uuid and --accessToken to runClient, or use a login library), then
 * {@link #mode} reports {@link AuthMode#ONLINE} and vanilla does the session check by itself when it joins.
 */
public final class SessionInfo {
	/** How the client logs in to servers. */
	public enum AuthMode {
		/** No Mojang session: only servers with online-mode=false accept the client. */
		OFFLINE,
		/** A real Microsoft account session: servers with online-mode=true accept the client. */
		ONLINE
	}

	/** Real access tokens are long JWTs. Dev launch tokens are short placeholders such as "0" or "FabricMC". */
	private static final int MIN_REAL_TOKEN_LENGTH = 32;

	private SessionInfo() {
	}

	public static AuthMode mode(Minecraft mc) {
		if (mc.isOfflineDeveloperMode()) {
			return AuthMode.OFFLINE;
		}
		return looksLikeRealToken(mc.getUser().getAccessToken()) ? AuthMode.ONLINE : AuthMode.OFFLINE;
	}

	static boolean looksLikeRealToken(String token) {
		return token != null && token.length() >= MIN_REAL_TOKEN_LENGTH;
	}

	public static JsonObject json(Minecraft mc) {
		User user = mc.getUser();
		JsonObject o = new JsonObject();
		o.addProperty("username", user.getName());
		o.addProperty("uuid", user.getProfileId() == null ? null : user.getProfileId().toString());
		o.addProperty("authMode", mode(mc).name().toLowerCase());
		o.addProperty("multiplayerAllowed", mc.allowsMultiplayer());
		return o;
	}
}
