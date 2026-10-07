package squid;

import squid.api.Hud;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Squid's own every-tick and every-frame lists. Squid hooks Minecraft's tick and HUD once, at startup, and runs every
 * mod's onTick and onHud from here. That way a mod added or reloaded while the game is running can still run every
 * tick, even though Minecraft's classes can't be patched again by then.
 */
public final class Events {
    private Events() {
    }

    /** One mod's listener, and how often it has failed. After 3 failures it's switched off, like a hook. */
    private record Listener<T>(String modId, Consumer<T> run, AtomicInteger failures) {
    }

    private static final List<Listener<Void>> TICKS = new CopyOnWriteArrayList<>();
    private static final List<Listener<Hud>> HUDS = new CopyOnWriteArrayList<>();

    public static void onTick(String modId, Runnable tick) {
        TICKS.add(new Listener<>(modId, nothing -> tick.run(), new AtomicInteger()));
    }

    public static void onHud(String modId, Consumer<Hud> draw) {
        HUDS.add(new Listener<>(modId, draw, new AtomicInteger()));
    }

    /** Removes a mod's listeners, like when it's reloaded or turned off. */
    static void remove(String modId) {
        TICKS.removeIf(l -> l.modId().equals(modId));
        HUDS.removeIf(l -> l.modId().equals(modId));
    }

    /** Squid hooks Minecraft's tick and HUD here once, before any mod starts. */
    static void registerHooks() {
        Transformers.add("net.minecraft.client.Minecraft", new Transformers.HookPatch("tick", "()V", false,
                Hooks.register("squid", call -> runTicks())));
        // Minecraft draws the potion effects corner only while the HUD is showing, so right after it is a safe place
        Transformers.add("net.minecraft.client.gui.Hud", new Transformers.HookPatch("extractEffects", null, false,
                Hooks.register("squid", call -> run(HUDS, new Hud(call.args()[0])))));
    }

    /** Runs every mod's onTick once (Minecraft does this 20 times a second). */
    static void runTicks() {
        run(TICKS, null);
    }

    private static <T> void run(List<Listener<T>> listeners, T value) {
        for (Listener<T> listener : listeners) {
            if (listener.failures().get() >= 3) continue;
            try {
                listener.run().accept(value);
            } catch (RuntimeException | LinkageError e) {
                int failures = listener.failures().incrementAndGet();
                if (failures == 1) {
                    System.out.println("[Squid] Something from " + listener.modId() + " failed:");
                    e.printStackTrace(System.out);
                }
                if (failures == 3) {
                    System.out.println("[Squid] Turned off something from " + listener.modId() + " because it kept failing.");
                    Main.hookProblem(listener.modId(), Main.describe(e));
                }
            }
        }
    }
}
