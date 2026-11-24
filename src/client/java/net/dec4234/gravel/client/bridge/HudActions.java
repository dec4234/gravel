package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dec4234.gravel.client.mixin.BossHealthOverlayAccessor;
import net.dec4234.gravel.client.mixin.HudAccessor;
import net.dec4234.gravel.client.mixin.PlayerTabOverlayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Comparator;
import java.util.List;

/**
 * Reads the text that the HUD shows: title, subtitle, action bar, boss bars, scoreboard sidebar, and the tab list
 * header and footer. Many servers and mods show data only in these places.
 */
public final class HudActions {
	/** The sidebar shows at most this many lines, the same as vanilla. */
	private static final int SIDEBAR_MAX_LINES = 15;
	/** The sort order of the vanilla sidebar: highest score first, then by name. */
	private static final Comparator<PlayerScoreEntry> SIDEBAR_ORDER = Comparator
			.comparing(PlayerScoreEntry::value).reversed()
			.thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

	private HudActions() {
	}

	public static void registerAll(ActionRegistry registry) {
		registry.register("read_hud", HudActions::readHud);
	}

	private static JsonObject readHud(Minecraft mc, JsonObject params) {
		if (mc.player == null || mc.level == null) {
			throw new IllegalStateException("Not in a world. Join a world first.");
		}
		Hud hud = mc.gui.hud;
		HudAccessor access = (HudAccessor) hud;

		JsonObject result = new JsonObject();
		result.addProperty("hudHidden", hud.isHidden());

		// The game keeps the last title and action bar text after they fade, so also tell if they show now.
		JsonObject title = new JsonObject();
		boolean titleShown = access.gravel$getTitleTime() > 0 && access.gravel$getTitle() != null;
		title.addProperty("shown", titleShown);
		title.addProperty("title", text(access.gravel$getTitle()));
		title.addProperty("subtitle", text(access.gravel$getSubtitle()));
		title.addProperty("ticksLeft", Math.max(0, access.gravel$getTitleTime()));
		result.add("title", title);

		JsonObject actionBar = new JsonObject();
		actionBar.addProperty("shown", access.gravel$getOverlayMessageTime() > 0 && access.gravel$getOverlayMessage() != null);
		actionBar.addProperty("text", text(access.gravel$getOverlayMessage()));
		actionBar.addProperty("ticksLeft", Math.max(0, access.gravel$getOverlayMessageTime()));
		result.add("actionBar", actionBar);

		result.add("bossBars", bossBars(hud));
		result.add("sidebar", sidebar(mc));

		PlayerTabOverlayAccessor tab = (PlayerTabOverlayAccessor) hud.getTabList();
		JsonObject tabList = new JsonObject();
		tabList.addProperty("open", tab.gravel$isVisible());
		tabList.addProperty("header", text(tab.gravel$getHeader()));
		tabList.addProperty("footer", text(tab.gravel$getFooter()));
		result.add("tabList", tabList);
		return result;
	}

	private static JsonArray bossBars(Hud hud) {
		JsonArray bars = new JsonArray();
		BossHealthOverlayAccessor overlay = (BossHealthOverlayAccessor) hud.getBossOverlay();
		for (LerpingBossEvent event : overlay.gravel$getEvents().values()) {
			JsonObject bar = new JsonObject();
			bar.addProperty("name", text(event.getName()));
			bar.addProperty("progress", event.getProgress());
			bar.addProperty("color", event.getColor().getName());
			bar.addProperty("style", event.getOverlay().getName());
			bars.add(bar);
		}
		return bars;
	}

	/** The sidebar the player sees: the objective for the player's team color, else the normal sidebar objective. */
	private static JsonObject sidebar(Minecraft mc) {
		Scoreboard scoreboard = mc.level.getScoreboard();
		Objective objective = null;
		PlayerTeam team = scoreboard.getPlayersTeam(mc.player.getScoreboardName());
		if (team != null && team.getColor().isPresent()) {
			objective = scoreboard.getDisplayObjective(team.getColor().get().displaySlot());
		}
		if (objective == null) {
			objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		}

		JsonObject sidebar = new JsonObject();
		sidebar.addProperty("shown", objective != null);
		if (objective == null) {
			return sidebar;
		}
		sidebar.addProperty("objective", objective.getName());
		sidebar.addProperty("title", text(objective.getDisplayName()));

		NumberFormat format = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
		List<PlayerScoreEntry> entries = scoreboard.listPlayerScores(objective).stream()
				.filter(e -> !e.isHidden())
				.sorted(SIDEBAR_ORDER)
				.limit(SIDEBAR_MAX_LINES)
				.toList();
		JsonArray lines = new JsonArray();
		for (PlayerScoreEntry entry : entries) {
			JsonObject line = new JsonObject();
			line.addProperty("text", text(PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName())));
			line.addProperty("score", text(entry.formatValue(format)));
			line.addProperty("value", entry.value());
			lines.add(line);
		}
		sidebar.add("lines", lines);
		return sidebar;
	}

	private static String text(Component component) {
		return component == null ? null : component.getString();
	}
}
