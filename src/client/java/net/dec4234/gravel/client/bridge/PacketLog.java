package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Keeps the packets of the client connection: received (in) and sent (out). The channel of a custom payload
 * packet is its payload id (such as mymod:sync). The channel of a vanilla packet is its packet type id (such as
 * minecraft:set_time). Each packet gets an increasing id. The log keeps the newest packets and, for each channel,
 * totals since the start or the last clear.
 *
 * <p>The network threads call {@link #add}, so all methods are synchronized.
 */
public final class PacketLog {
	static final int CAPACITY = 4096;
	static final String IN = "in";
	static final String OUT = "out";
	private static final int UNKNOWN = -1;

	record Entry(long id, long timeMs, String direction, String channel, boolean custom, String phase, int bytes) {
	}

	/** The totals of one channel in one direction. */
	private static final class Stats {
		final String direction;
		final String channel;
		final boolean custom;
		long count;
		long sizedCount;
		long totalBytes;
		int minBytes = Integer.MAX_VALUE;
		int maxBytes;
		int lastBytes = UNKNOWN;
		long lastId;
		long lastTimeMs;

		Stats(String direction, String channel, boolean custom) {
			this.direction = direction;
			this.channel = channel;
			this.custom = custom;
		}

		void add(Entry e) {
			count++;
			lastId = e.id();
			lastTimeMs = e.timeMs();
			lastBytes = e.bytes();
			if (e.bytes() >= 0) {
				sizedCount++;
				totalBytes += e.bytes();
				minBytes = Math.min(minBytes, e.bytes());
				maxBytes = Math.max(maxBytes, e.bytes());
			}
		}
	}

	private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
	private static final Map<String, Stats> TOTALS = new LinkedHashMap<>();
	private static long nextId = 1;
	/** The id of the first packet after the last clear. Packets before it are not in the totals. */
	private static long clearedAt = 1;

	private PacketLog() {
	}

	// ---------------------------------------------------------------- recording (mixins)

	/**
	 * Records a packet that went through the network codec. In this version the singleplayer (memory) connection also
	 * encodes its packets, so this sees all packets of the client.
	 *
	 * @param bytes the packet size after decompression, with the packet id
	 */
	public static void record(PacketFlow flow, ConnectionProtocol phase, Packet<?> packet, int bytes) {
		try {
			if (packet instanceof BundlePacket<?> bundle) {
				for (Packet<?> sub : bundle.subPackets()) {
					record(flow, phase, sub, UNKNOWN);
				}
				return;
			}
			String channel;
			boolean custom = true;
			if (packet instanceof ClientboundCustomPayloadPacket c) {
				channel = c.payload().type().id().toString();
			} else if (packet instanceof ServerboundCustomPayloadPacket s) {
				channel = s.payload().type().id().toString();
			} else {
				channel = packet.type().id().toString();
				custom = false;
			}
			add(flow == PacketFlow.CLIENTBOUND ? IN : OUT, channel, custom, phase == null ? null : phase.id(), bytes);
		} catch (RuntimeException e) {
			// The log must never break the connection.
		}
	}

	// ---------------------------------------------------------------- log

	/** @param bytes the size, or a negative number if it is not known */
	static synchronized void add(String direction, String channel, boolean custom, String phase, int bytes) {
		Entry e = new Entry(nextId++, System.currentTimeMillis(), direction, channel, custom, phase, Math.max(UNKNOWN, bytes));
		ENTRIES.addLast(e);
		while (ENTRIES.size() > CAPACITY) {
			ENTRIES.removeFirst();
		}
		TOTALS.computeIfAbsent(direction + " " + channel, k -> new Stats(direction, channel, custom)).add(e);
	}

	/** Removes all packets and totals. Ids keep counting up, so old ids are never used again. */
	public static synchronized void clear() {
		ENTRIES.clear();
		TOTALS.clear();
		clearedAt = nextId;
	}

	/** The id of the newest packet, or 0 if there is none. */
	public static synchronized long latestId() {
		return nextId - 1;
	}

	/** Which packets to look at. A null channel matches all channels. A null direction matches both. */
	record Filter(Predicate<String> channel, String direction, boolean customOnly) {
		static final Filter ALL = new Filter(null, null, false);

		boolean test(String dir, String ch, boolean custom) {
			return (!customOnly || custom)
					&& (direction == null || direction.equals(dir))
					&& (channel == null || channel.test(ch));
		}

		/** "in", "out", or "any"/"both"/null for both directions. */
		static String direction(String value) {
			if (value == null) {
				return null;
			}
			String v = value.trim().toLowerCase(Locale.ROOT);
			return switch (v) {
				case "in", "out" -> v;
				case "", "any", "both" -> null;
				default -> throw new IllegalArgumentException("direction must be in, out or any, not '" + value + "'");
			};
		}
	}

	/** The oldest packet after {@code sinceId} that matches, as JSON, or null. */
	public static synchronized JsonObject find(long sinceId, Filter filter) {
		for (Entry e : ENTRIES) {
			if (e.id() > sinceId && filter.test(e.direction(), e.channel(), e.custom())) {
				return json(e);
			}
		}
		return null;
	}

	/**
	 * The packets by channel, with counts and sizes, and the newest packets.
	 *
	 * @param sinceId      0 for the totals since the start or the last clear. Else only the kept packets after this id.
	 * @param channelLimit at most this many channels (custom channels first, then the most packets)
	 * @param recentLimit  at most this many of the newest packets
	 */
	public static synchronized JsonObject read(long sinceId, Filter filter, int channelLimit, int recentLimit) {
		long now = System.currentTimeMillis();
		List<Stats> stats = new ArrayList<>();
		boolean complete;
		if (sinceId <= 0) {
			for (Stats s : TOTALS.values()) {
				if (filter.test(s.direction, s.channel, s.custom)) {
					stats.add(s);
				}
			}
			complete = true;
		} else {
			Map<String, Stats> picked = new LinkedHashMap<>();
			for (Entry e : ENTRIES) {
				if (e.id() > sinceId && filter.test(e.direction(), e.channel(), e.custom())) {
					picked.computeIfAbsent(e.direction() + " " + e.channel(), k -> new Stats(e.direction(), e.channel(), e.custom())).add(e);
				}
			}
			stats.addAll(picked.values());
			long oldestKept = ENTRIES.isEmpty() ? nextId : ENTRIES.getFirst().id();
			complete = oldestKept <= Math.max(sinceId + 1, clearedAt);
		}
		stats.sort(Comparator.<Stats, Boolean>comparing(s -> !s.custom)
				.thenComparing(Comparator.<Stats>comparingLong(s -> s.count).reversed())
				.thenComparing(s -> s.channel));

		long totalPackets = 0;
		long totalBytes = 0;
		JsonArray channels = new JsonArray();
		for (Stats s : stats) {
			totalPackets += s.count;
			totalBytes += s.totalBytes;
			if (channels.size() < channelLimit) {
				channels.add(json(s, now));
			}
		}

		JsonArray recent = new JsonArray();
		Deque<Entry> newest = new ArrayDeque<>();
		var it = ENTRIES.descendingIterator();
		while (it.hasNext() && newest.size() < recentLimit) {
			Entry e = it.next();
			if (e.id() <= sinceId) {
				break;
			}
			if (filter.test(e.direction(), e.channel(), e.custom())) {
				newest.addFirst(e);
			}
		}
		newest.forEach(e -> recent.add(json(e)));

		JsonObject result = new JsonObject();
		result.addProperty("latestId", latestId());
		result.addProperty("totalPackets", totalPackets);
		result.addProperty("totalBytes", totalBytes);
		result.addProperty("channelCount", stats.size());
		result.addProperty("complete", complete);
		result.add("channels", channels);
		result.add("recent", recent);
		return result;
	}

	private static JsonObject json(Stats s, long now) {
		JsonObject o = new JsonObject();
		o.addProperty("channel", s.channel);
		o.addProperty("direction", s.direction);
		o.addProperty("custom", s.custom);
		o.addProperty("count", s.count);
		if (s.sizedCount > 0) {
			o.addProperty("totalBytes", s.totalBytes);
			o.addProperty("avgBytes", Math.round((double) s.totalBytes / s.sizedCount));
			o.addProperty("minBytes", s.minBytes);
			o.addProperty("maxBytes", s.maxBytes);
			if (s.lastBytes >= 0) {
				o.addProperty("lastBytes", s.lastBytes);
			}
			if (s.sizedCount < s.count) {
				o.addProperty("sizedCount", s.sizedCount);
			}
		}
		o.addProperty("lastId", s.lastId);
		o.addProperty("lastAgoMs", Math.max(0, now - s.lastTimeMs));
		return o;
	}

	private static JsonObject json(Entry e) {
		JsonObject o = new JsonObject();
		o.addProperty("id", e.id());
		o.addProperty("time", Instant.ofEpochMilli(e.timeMs()).toString());
		o.addProperty("direction", e.direction());
		o.addProperty("channel", e.channel());
		o.addProperty("custom", e.custom());
		if (e.phase() != null) {
			o.addProperty("phase", e.phase());
		}
		if (e.bytes() >= 0) {
			o.addProperty("bytes", e.bytes());
		}
		return o;
	}
}
