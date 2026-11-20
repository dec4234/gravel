package net.dec4234.gravel.client.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Holds the actions that the bridge can run. Each action runs on the Minecraft client thread,
 * so it is safe to touch game state from inside it.
 */
public final class ActionRegistry {
	/** An action that the MCP server can call. Runs on the client thread. */
	@FunctionalInterface
	public interface Action {
		JsonObject run(Minecraft mc, JsonObject params) throws Exception;
	}

	/**
	 * An action that starts on the client thread and gives its result later, for example after a GPU read
	 * or after a key is held for some time. The bridge waits for the future, not the client thread.
	 */
	@FunctionalInterface
	public interface AsyncAction {
		CompletableFuture<JsonObject> start(Minecraft mc, JsonObject params) throws Exception;
	}

	/** Runs a task on the thread that owns the game state and gives back its result. */
	@FunctionalInterface
	public interface ClientThread {
		CompletableFuture<JsonObject> submit(Supplier<JsonObject> task);
	}

	private static final long TIMEOUT_SECONDS = 30;

	private final Map<String, AsyncAction> actions = new LinkedHashMap<>();
	private final Supplier<Minecraft> client;
	private final ClientThread clientThread;

	/** Runs actions on the Minecraft client thread. */
	public ActionRegistry() {
		this(Minecraft::getInstance, task -> Minecraft.getInstance().submit(task));
	}

	/** Lets tests run actions without a Minecraft client. */
	ActionRegistry(Supplier<Minecraft> client, ClientThread clientThread) {
		this.client = client;
		this.clientThread = clientThread;
	}

	public ActionRegistry register(String name, Action action) {
		actions.put(name, (mc, params) -> CompletableFuture.completedFuture(action.run(mc, params)));
		return this;
	}

	public ActionRegistry registerAsync(String name, AsyncAction action) {
		actions.put(name, action);
		return this;
	}

	public boolean has(String name) {
		return actions.containsKey(name);
	}

	public Set<String> names() {
		return actions.keySet();
	}

	public JsonObject run(String name, JsonObject params) throws Exception {
		AsyncAction action = actions.get(name);
		Minecraft mc = client.get();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		AtomicReference<CompletableFuture<JsonObject>> pending = new AtomicReference<>();
		CompletableFuture<JsonObject> started = clientThread.submit(() -> {
			try {
				pending.set(action.start(mc, params));
				return null;
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		started.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		long left = Math.max(1, deadline - System.nanoTime());
		return pending.get().get(left, TimeUnit.NANOSECONDS);
	}
}
