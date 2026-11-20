package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups block positions by block name, so that a scan result lists each kind of block once with its
 * positions. Each group keeps at most {@code maxPositions} positions, the ones nearest to the center.
 * Has no Minecraft types, so tests can use it.
 */
final class BlockGroups {
	private record Pos(int x, int y, int z, int distSq) {
	}

	private final int centerX;
	private final int centerY;
	private final int centerZ;
	private final int maxPositions;
	private final Map<String, List<Pos>> groups = new LinkedHashMap<>();

	BlockGroups(int centerX, int centerY, int centerZ, int maxPositions) {
		this.centerX = centerX;
		this.centerY = centerY;
		this.centerZ = centerZ;
		this.maxPositions = Math.max(0, maxPositions);
	}

	void add(String block, int x, int y, int z) {
		int dx = x - centerX;
		int dy = y - centerY;
		int dz = z - centerZ;
		groups.computeIfAbsent(block, k -> new ArrayList<>()).add(new Pos(x, y, z, dx * dx + dy * dy + dz * dz));
	}

	int kinds() {
		return groups.size();
	}

	int total() {
		return groups.values().stream().mapToInt(List::size).sum();
	}

	/**
	 * One object per block kind, most common first: {"block", "count", "positions": [[x,y,z], ...], "truncated"}.
	 * Positions are sorted nearest first.
	 */
	JsonArray toJson() {
		List<Map.Entry<String, List<Pos>>> entries = new ArrayList<>(groups.entrySet());
		entries.sort(Comparator.<Map.Entry<String, List<Pos>>>comparingInt(e -> e.getValue().size()).reversed()
				.thenComparing(Map.Entry::getKey));
		JsonArray out = new JsonArray();
		for (Map.Entry<String, List<Pos>> e : entries) {
			List<Pos> list = e.getValue();
			list.sort(Comparator.comparingInt(Pos::distSq)
					.thenComparingInt(Pos::y).thenComparingInt(Pos::x).thenComparingInt(Pos::z));
			JsonObject g = new JsonObject();
			g.addProperty("block", e.getKey());
			g.addProperty("count", list.size());
			JsonArray positions = new JsonArray();
			for (int i = 0; i < Math.min(list.size(), maxPositions); i++) {
				Pos p = list.get(i);
				JsonArray xyz = new JsonArray();
				xyz.add(p.x());
				xyz.add(p.y());
				xyz.add(p.z());
				positions.add(xyz);
			}
			g.add("positions", positions);
			g.addProperty("truncated", list.size() > maxPositions);
			out.add(g);
		}
		return out;
	}

	/** True if the name contains any of the comma-separated filter terms (not case sensitive). A blank filter matches all. */
	static boolean matches(String name, String filter) {
		if (filter == null || filter.isBlank()) {
			return true;
		}
		String lower = name.toLowerCase();
		for (String term : filter.toLowerCase().split(",")) {
			String t = term.trim();
			if (!t.isEmpty() && lower.contains(t)) {
				return true;
			}
		}
		return false;
	}

	static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
