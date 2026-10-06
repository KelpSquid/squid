package squid;

import squid.api.Call;
import squid.api.Hook;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps every hook mods register. Hooked Minecraft methods call {@link #start} and {@link #end},
 * which Squid writes into their bytecode, so these have to stay public.
 */
public final class Hooks {
    private record Entry(String modId, Hook hook) {
    }

    private static final List<Entry> HOOKS = new ArrayList<>();

    private Hooks() {
    }

    /** Saves a hook and returns its number, which gets written into the hooked method. */
    public static synchronized int register(String modId, Hook hook) {
        HOOKS.add(new Entry(modId, hook));
        return HOOKS.size() - 1;
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
        try {
            entry.hook().run(call);
        } catch (RuntimeException e) {
            // One broken hook shouldn't crash the game. Say which mod it was and carry on.
            System.out.println("[Squid] A hook from " + entry.modId() + " failed:");
            e.printStackTrace(System.out);
        }
    }
}
