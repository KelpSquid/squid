package squid;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Mods talking to each other without needing each other's code: events any mod can send and any mod can listen for
 * (see {@link squid.api.Squid#emit} and {@link squid.api.Squid#on}), and values or answers one mod shares by name for
 * others to use ({@link squid.api.Squid#share}, {@link squid.api.Squid#ask}). Every mod has its own class loader, so
 * what's passed should be plain Java things: text, numbers, lists, maps, and Java's own Function and Runnable.
 */
public final class ModBus {
    private ModBus() {
    }

    private record Listener(String modId, Consumer<Object> run, AtomicInteger failures) {
    }

    private record Shared(String modId, Object value, AtomicInteger failures) {
    }

    private static final Map<String, List<Listener>> EVENTS = new ConcurrentHashMap<>();
    private static final Map<String, Shared> SHARED = new ConcurrentHashMap<>();

    /** How deep events sent from inside listeners can go, so two mods answering each other can't loop forever. */
    private static final int MAX_DEPTH = 16;
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    public static void on(String modId, String event, Consumer<Object> listener) {
        if (event == null || event.isEmpty()) throw new IllegalArgumentException(Lang.t("an event needs a name, like \"treasure-found\""));
        EVENTS.computeIfAbsent(event, e -> new CopyOnWriteArrayList<>()).add(new Listener(modId, listener, new AtomicInteger()));
    }

    /** Hands a value to every mod listening for the event. Gives back how many listeners there were. */
    public static int emit(String fromModId, String event, Object value) {
        List<Listener> listeners = EVENTS.get(event);
        if (listeners == null || listeners.isEmpty()) return 0;
        int[] depth = DEPTH.get();
        if (depth[0] >= MAX_DEPTH) {
            System.out.println("[Squid] " + fromModId + " sent \"" + event + "\" from inside listeners " + MAX_DEPTH
                    + " deep, so it was dropped (mods answering each other in a loop?)");
            return 0;
        }
        depth[0]++;
        try {
            int heard = 0;
            for (Listener listener : listeners) {
                if (listener.failures().get() >= 3) continue;
                heard++;
                try {
                    listener.run().accept(value);
                } catch (RuntimeException | LinkageError e) {
                    failed(listener.modId(), listener.failures(), "a listener for \"" + event + "\"", e);
                }
            }
            return heard;
        } finally {
            depth[0]--;
        }
    }

    /** A mod shares a value (or a Function other mods can ask) by name. Sharing the same name again replaces it. */
    public static void share(String modId, String name, Object value) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException(Lang.t("a shared value needs a name, like \"score\""));
        Shared before = SHARED.put(name, new Shared(modId, value, new AtomicInteger()));
        if (before != null && !before.modId().equals(modId)) {
            System.out.println("[Squid] " + modId + " shares \"" + name + "\", which " + before.modId() + " shared before. It's " + modId + "'s now.");
        }
    }

    /** A shared value, or null if no mod shares one by that name. */
    public static Object shared(String name) {
        Shared shared = SHARED.get(name);
        return shared == null ? null : shared.value();
    }

    /**
     * Asks a shared answer: a Function gets the question, a Supplier is asked for its value, and anything else is just
     * given back. If the other mod's code breaks, the answer is null (and after 3 breaks it isn't asked anymore).
     */
    @SuppressWarnings("unchecked")
    public static Object ask(String name, Object question) {
        Shared shared = SHARED.get(name);
        if (shared == null || shared.failures().get() >= 3) return null;
        try {
            if (shared.value() instanceof Function<?, ?> f) return ((Function<Object, Object>) f).apply(question);
            if (shared.value() instanceof Supplier<?> s) return s.get();
            return shared.value();
        } catch (RuntimeException | LinkageError e) {
            failed(shared.modId(), shared.failures(), "the answer it shares as \"" + name + "\"", e);
            return null;
        }
    }

    private static void failed(String modId, AtomicInteger failures, String what, Throwable e) {
        int count = failures.incrementAndGet();
        if (count == 1) {
            System.out.println("[Squid] " + what + " from " + modId + " failed:");
            e.printStackTrace(System.out);
        }
        if (count == 3) {
            System.out.println("[Squid] Turned off " + what + " from " + modId + " because it kept failing.");
            Main.hookProblem(modId, Main.describe(e));
        }
    }

    /** A mod is reloaded or turned off: its listeners and shared values go. */
    static void remove(String modId) {
        for (List<Listener> listeners : EVENTS.values()) listeners.removeIf(l -> l.modId().equals(modId));
        SHARED.values().removeIf(s -> s.modId().equals(modId));
    }
}
