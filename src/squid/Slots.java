package squid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers which hooks each mod set up, so a mod that's reloaded while the game runs can take over its old hooks.
 * Minecraft's classes are only patched once, as they load: a reloaded mod can't add hooks to classes that are already
 * loaded, but it can put its new code into the hook places its old version had. Squid matches them up by class,
 * method and kind (start, end, around, or a call inside it), in the order they're set up. Hooks it can't match need a restart, and Squid says so.
 */
public final class Slots {
    private Slots() {
    }

    /**
     * One hook place: which method it's in, what kind it is ("start", "end", "around", or "call " and the call it
     * wraps), and its number in {@link Hooks}.
     */
    record Slot(String className, String method, String descriptor, String kind, int hookId) {
        boolean sameTarget(String c, String m, String d, String k) {
            return className.equals(c) && method.equals(m) && java.util.Objects.equals(descriptor, d) && kind.equals(k);
        }
    }

    private static String kind(boolean atStart) {
        return atStart ? "start" : "end";
    }

    private static final Map<String, List<Slot>> slots = new HashMap<>();       // by mod id
    private static final Map<String, List<Slot>> reusable = new HashMap<>();    // a reloading mod's old slots
    private static final Map<String, Set<String>> needRestart = new HashMap<>(); // classes a reload couldn't reach

    /** Remembers a hook a mod set up. */
    public static void record(String modId, String className, String method, String descriptor, boolean atStart, int hookId) {
        record(modId, className, method, descriptor, kind(atStart), hookId);
    }

    /** Remembers a hook of any kind ("start", "end", "around", "call ..."). */
    public static synchronized void record(String modId, String className, String method, String descriptor, String kind, int hookId) {
        slots.computeIfAbsent(modId, k -> new ArrayList<>()).add(new Slot(className, method, descriptor, kind, hookId));
    }

    /**
     * The hook number of one of the mod's old hooks in the same place, which the reloaded mod takes over, or -1 if
     * there isn't one (or the mod isn't being reloaded).
     */
    public static int reuse(String modId, String className, String method, String descriptor, boolean atStart) {
        return reuse(modId, className, method, descriptor, kind(atStart));
    }

    /** Like {@link #reuse(String, String, String, String, boolean)}, for a hook of any kind. */
    public static synchronized int reuse(String modId, String className, String method, String descriptor, String kind) {
        List<Slot> old = reusable.get(modId);
        if (old == null) return -1;
        for (Slot slot : old) {
            if (slot.sameTarget(className, method, descriptor, kind)) {
                old.remove(slot);
                slots.computeIfAbsent(modId, k -> new ArrayList<>()).add(slot);
                return slot.hookId();
            }
        }
        return -1;
    }

    /** The hook numbers a mod has now. Only tests need this. */
    static synchronized List<Integer> hookIds(String modId) {
        return slots.getOrDefault(modId, List.of()).stream().map(Slot::hookId).toList();
    }

    /** Notes that a reloaded mod wanted to change a class that's already loaded, which takes a restart. */
    public static synchronized void needsRestart(String modId, String className) {
        if (reusable.containsKey(modId)) needRestart.computeIfAbsent(modId, k -> new LinkedHashSet<>()).add(className);
    }

    /** A mod is about to be reloaded: its hooks are turned off, and become free for its new version to take over. */
    static synchronized void beginReload(String modId) {
        Hooks.turnOff(modId);
        Events.remove(modId);
        List<Slot> old = slots.remove(modId);
        reusable.put(modId, old == null ? new ArrayList<>() : new ArrayList<>(old));
        needRestart.remove(modId);
    }

    /** The reload is done. Gives back the classes it couldn't change without a restart (empty if none). */
    static synchronized Set<String> endReload(String modId) {
        List<Slot> unused = reusable.remove(modId);
        if (unused != null) slots.computeIfAbsent(modId, k -> new ArrayList<>()).addAll(unused); // still off, kept for next time
        Set<String> classes = needRestart.remove(modId);
        return classes == null ? Set.of() : classes;
    }
}
