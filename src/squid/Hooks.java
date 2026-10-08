package squid;

import squid.api.Around;
import squid.api.Call;
import squid.api.Hook;
import squid.api.Original;

import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps every hook mods register. Hooked Minecraft methods call {@link #start}, {@link #end} and {@link #around},
 * which Squid writes into their bytecode, so these have to stay public.
 */
public final class Hooks {
    /** One registered hook, and how often it has failed. Hooks can run on more than one thread, so these are thread-safe. */
    private static final class Entry {
        final String modId;
        final Object hook; // a Hook (start and end) or an Around (around and atCall)
        final AtomicInteger failures = new AtomicInteger();
        volatile boolean turnedOff;

        Entry(String modId, Object hook) {
            this.modId = modId;
            this.hook = hook;
        }
    }

    /** After this many failures, a hook is turned off so it can't keep breaking things (or flood the log). */
    private static final int MAX_FAILURES = 3;

    // Hooked methods can run thousands of times a frame on several threads (like building chunks), so finding a hook
    // must be quick and never wait for a lock. Registering makes a new, longer array; running only reads it.
    private static volatile Entry[] hooks = new Entry[0];

    private Hooks() {
    }

    /** Saves a hook and returns its number, which gets written into the hooked method. */
    public static int register(String modId, Hook hook) {
        return add(modId, hook);
    }

    /** Saves an around or atCall hook and returns its number. */
    public static int register(String modId, Around hook) {
        return add(modId, hook);
    }

    private static synchronized int add(String modId, Object hook) {
        Entry[] longer = Arrays.copyOf(hooks, hooks.length + 1);
        longer[hooks.length] = new Entry(modId, hook);
        hooks = longer;
        return hooks.length - 1;
    }

    /** Puts new code into an existing hook place (for a reloaded mod), switched on and with no failures counted. */
    public static void replace(int id, String modId, Hook hook) {
        put(id, modId, hook);
    }

    /** Like {@link #replace(int, String, Hook)}, for an around or atCall hook. */
    public static void replace(int id, String modId, Around hook) {
        put(id, modId, hook);
    }

    private static synchronized void put(int id, String modId, Object hook) {
        Entry[] copy = hooks.clone();
        copy[id] = new Entry(modId, hook);
        hooks = copy;
    }

    /** Switches off every hook a mod set up, like when the mod broke while starting. */
    public static synchronized void turnOff(String modId) {
        for (Entry entry : hooks) {
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
        Entry entry = hooks[id];
        if (entry.turnedOff) return;
        try {
            ((Hook) entry.hook).run(call);
        } catch (RuntimeException | LinkageError e) {
            failed(entry, e);
        }
    }

    /**
     * Called instead of a method's own code by an around hook, and instead of the call by an atCall hook. original
     * runs the real thing (the method's code, moved to a hidden method of its own, or the call), through a small
     * invoker Squid writes into the class: it's a constant in the class, so finding it costs nothing per call.
     * returns is what the method gives back (Integer.TYPE for int), so a hook giving back the wrong kind of value
     * counts as broken instead of crashing Minecraft's code.
     */
    public static Object around(int id, Object self, Object[] args, Object caller, MethodHandle original, Class<?> returns) throws Throwable {
        Entry entry = hooks[id];
        if (entry.turnedOff) return (Object) original.invokeExact(self, args);
        Wrapped wrapped = new Wrapped(original, self, args);
        try {
            return fit(((Around) entry.hook).run(new Call(self, args, null, caller), wrapped), returns);
        } catch (RuntimeException | LinkageError e) {
            if (e == wrapped.thrown) throw e; // Minecraft's own code threw it, not the hook: it goes on as it would
            failed(entry, e);
            // The game carries on as if the hook weren't there: with what the original gave, or by running it now
            return wrapped.runs > 0 ? wrapped.last : (Object) original.invokeExact(self, args);
        }
    }

    private static void failed(Entry entry, Throwable e) {
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

    /** The original an around or atCall hook gets. It notes what happened, so a broken hook can be stepped around. */
    private static final class Wrapped implements Original {
        private final MethodHandle invoker;
        private final Object self;
        private final Object[] args;
        int runs;
        Object last;
        Throwable thrown;

        Wrapped(MethodHandle invoker, Object self, Object[] args) {
            this.invoker = invoker;
            this.self = self;
            this.args = args;
        }

        @Override
        public Object call() {
            return callOn(self, args);
        }

        @Override
        public Object call(Object... args) {
            return callOn(self, args);
        }

        @Override
        public Object callOn(Object target, Object... args) {
            if (args == null) args = new Object[] {null}; // call(null) for a method with one argument
            Object result;
            try {
                result = (Object) invoker.invokeExact(target, args);
            } catch (BadValue mistake) {
                throw mistake; // the hook handed over the wrong values: that's the hook's problem
            } catch (Throwable fromMinecraft) {
                thrown = fromMinecraft;
                throw Hooks.<RuntimeException>sneaky(fromMinecraft);
            }
            runs++;
            last = result;
            return result;
        }

        @Override
        public int timesCalled() {
            return runs;
        }
    }

    /** Throws any exception as it is (even a checked one, like Minecraft's own IOException), without wrapping it. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable e) throws T {
        throw (T) e;
    }

    // ---- Turning hooks' values into what Minecraft's code wants. The invokers Squid writes call these. ----

    /** A value a hook handed to Minecraft's code that doesn't fit, explained in plain words. */
    public static final class BadValue extends IllegalArgumentException {
        BadValue(String message) {
            super(message);
        }
    }

    /** The original was run with the wrong number of arguments. */
    public static RuntimeException wrongCount(int wanted, Object[] given) {
        return new BadValue(Lang.t("the original needs {0} value(s), but the hook gave it {1}", wanted, given.length));
    }

    /** The original was run with a value of the wrong kind (the invoker caught Java's ClassCastException). */
    public static RuntimeException badValue(Throwable cast) {
        String message = String.valueOf(cast.getMessage());
        // "class java.lang.String cannot be cast to class net.minecraft.core.BlockPos (...)": the two names are enough
        java.util.regex.Matcher names = java.util.regex.Pattern.compile("class (\\S+) cannot be cast to class (\\S+)").matcher(message);
        if (names.find()) message = Lang.t("{0} where {1} is needed", simple(names.group(1)), simple(names.group(2)));
        return new BadValue(Lang.t("the hook gave the original a value of the wrong kind: {0}", message));
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static Number number(Object value, String wanted) {
        if (value instanceof Number n) return n;
        throw new BadValue(Lang.t("the hook gave {0} where a number ({1}) is needed", describe(value), wanted));
    }

    public static int toInt(Object value) {
        return value instanceof Integer i ? i : number(value, "int").intValue();
    }

    public static long toLong(Object value) {
        return value instanceof Long l ? l : number(value, "long").longValue();
    }

    public static float toFloat(Object value) {
        return value instanceof Float f ? f : number(value, "float").floatValue();
    }

    public static double toDouble(Object value) {
        return value instanceof Double d ? d : number(value, "double").doubleValue();
    }

    public static short toShort(Object value) {
        return number(value, "short").shortValue();
    }

    public static byte toByte(Object value) {
        return number(value, "byte").byteValue();
    }

    public static char toChar(Object value) {
        if (value instanceof Character c) return c;
        throw new BadValue(Lang.t("the hook gave {0} where a letter (char) is needed", describe(value)));
    }

    public static boolean toBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        throw new BadValue(Lang.t("the hook gave {0} where true or false is needed", describe(value)));
    }

    /**
     * What an around or atCall hook gave back, made into what the method gives back: any number works for a number
     * (5 for a float), and anything else must be the right kind. Otherwise the hook counts as broken.
     */
    static Object fit(Object value, Class<?> type) {
        if (type == void.class) return null;
        if (!type.isPrimitive()) {
            if (value == null || type.isInstance(value)) return value;
            throw new BadValue(Lang.t("the hook gave back {0}, but {1} is needed", describe(value), type.getSimpleName()));
        }
        if (type == boolean.class) return toBoolean(value);
        if (type == char.class) return toChar(value);
        Number n = number(value, type.getName());
        if (type == int.class) return n instanceof Integer ? n : n.intValue();
        if (type == float.class) return n instanceof Float ? n : n.floatValue();
        if (type == double.class) return n instanceof Double ? n : n.doubleValue();
        if (type == long.class) return n instanceof Long ? n : n.longValue();
        if (type == short.class) return n.shortValue();
        return n.byteValue();
    }

    /** A value for an error message: "nothing (null)", or its kind and what it says. */
    static String describe(Object value) {
        if (value == null) return Lang.t("nothing (null)");
        String text = String.valueOf(value);
        if (text.length() > 40) text = text.substring(0, 40) + "...";
        return value.getClass().getSimpleName() + " " + text;
    }
}
