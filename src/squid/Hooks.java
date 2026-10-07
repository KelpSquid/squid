package squid;

import squid.api.Call;
import squid.api.Hook;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps every hook mods register. Hooked Minecraft methods call {@link #start} and {@link #end},
 * which Squid writes into their bytecode, so these have to stay public.
 */
public final class Hooks {
    /** One registered hook, and how often it has failed. Hooks can run on more than one thread, so these are thread-safe. */
    private static final class Entry {
        final String modId;
        final Hook hook;
        final AtomicInteger failures = new AtomicInteger();
        volatile boolean turnedOff;

        Entry(String modId, Hook hook) {
            this.modId = modId;
            this.hook = hook;
        }
    }

    /** After this many failures, a hook is turned off so it can't keep breaking things (or flood the log). */
    private static final int MAX_FAILURES = 3;

    private static final List<Entry> HOOKS = new ArrayList<>();

    private Hooks() {
    }

    /** Saves a hook and returns its number, which gets written into the hooked method. */
    public static synchronized int register(String modId, Hook hook) {
        HOOKS.add(new Entry(modId, hook));
        return HOOKS.size() - 1;
    }

    /** Switches off every hook a mod set up, like when the mod broke while starting. */
    public static synchronized void turnOff(String modId) {
        for (Entry entry : HOOKS) {
            if (entry.modId.equals(modId)) entry.turnedOff = true;
        }
    }

    /** Called at the start of a hooked method. If the result is cancelled, the method returns right away. */
    public static Call start(int id, Object self, Object[] args) {
        Call call = new Call(self, args, null);
        run(id, call);
        return call;
    }

    /** Called just before a hooked method returns. Gives back what the method should return. */
    public static Object end(int id, Object self, Object[] args, Object returnValue) {
        Call call = new Call(self, args, returnValue);
        run(id, call);
        return call.returnValue();
    }

    private static void run(int id, Call call) {
        Entry entry;
        synchronized (Hooks.class) {
            entry = HOOKS.get(id);
        }
        if (entry.turnedOff) return;
        try {
            entry.hook.run(call);
        } catch (RuntimeException | LinkageError e) {
            // One broken hook shouldn't crash the game. LinkageError covers a mod made for a different
            // Minecraft version, which can't find a class or method it expects.
            int failures = entry.failures.incrementAndGet();
            if (failures == 1) {
                System.out.println("[Squid] A hook from " + entry.modId + " failed:");
                e.printStackTrace(System.out);
            }
            if (failures == MAX_FAILURES) {
                entry.turnedOff = true;
                System.out.println("[Squid] Turned off a hook from " + entry.modId + " because it kept failing. The game keeps going.");
                Main.hookProblem(entry.modId, Main.describe(e));
            }
        }
    }
}
