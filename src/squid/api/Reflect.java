package squid.api;

import squid.Lang;
import squid.Main;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads and changes any field, and calls any method, on Minecraft's objects (or anyone's) by its real name, private
 * ones too. Minecraft 26 comes with its real names, so no mappings or access wideners are needed:
 *
 * <pre>
 * int level = Reflect.get(player, "experienceLevel");
 * Reflect.set(player, "experienceLevel", level + 1);
 * Reflect.call(player, "setSprinting", true);
 * Object options = Reflect.get(Reflect.callStatic("net.minecraft.client.Minecraft", "getInstance"), "options");
 * </pre>
 *
 * A name that's wrong says so in plain words, with a guess when it looks like a typo. Each field and method is looked
 * up once and kept, so these are quick enough to use every frame. Numbers can be given as any kind of number.
 */
public final class Reflect {
    private Reflect() {
    }

    /** A field, ready to read and (unless it can't change) write. */
    private record FieldAccess(Field field, MethodHandle getter, MethodHandle setter) {
    }

    /** A method or constructor, ready to call with an Object[] of arguments. */
    private record Callable(Executable executable, Class<?>[] params, MethodHandle invoker) {
    }

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Map<String, Class<?>> CLASSES = new ConcurrentHashMap<>();

    private static final ClassValue<Map<String, FieldAccess>> FIELDS = new ClassValue<>() {
        @Override
        protected Map<String, FieldAccess> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private static final ClassValue<Map<String, Callable[]>> METHODS = new ClassValue<>() {
        @Override
        protected Map<String, Callable[]> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    // ---- Fields ----

    /** A field's value, private or not: {@code int level = Reflect.get(player, "experienceLevel");} */
    @SuppressWarnings("unchecked")
    public static <T> T get(Object target, String field) {
        if (target == null) throw new IllegalArgumentException(Lang.t("can't read \"{0}\" from nothing (null)", field));
        FieldAccess access = field(target.getClass(), field, false);
        try {
            return (T) (Object) access.getter().invokeExact(target);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /** Changes a field: {@code Reflect.set(player, "experienceLevel", 30);} */
    public static void set(Object target, String field, Object value) {
        if (target == null) throw new IllegalArgumentException(Lang.t("can't change \"{0}\" on nothing (null)", field));
        FieldAccess access = field(target.getClass(), field, false);
        try {
            setter(access).invokeExact(target, convert(value, access.field().getType(), access.field().getName()));
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /** A static field's value, by the class's full name: {@code Reflect.getStatic("net.minecraft.SharedConstants", "DEBUG_ENABLED")}. */
    @SuppressWarnings("unchecked")
    public static <T> T getStatic(String className, String field) {
        FieldAccess access = field(type(className), field, true);
        try {
            return (T) (Object) access.getter().invokeExact((Object) null);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /** Changes a static field. */
    public static void setStatic(String className, String field, Object value) {
        FieldAccess access = field(type(className), field, true);
        try {
            setter(access).invokeExact((Object) null, convert(value, access.field().getType(), access.field().getName()));
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    private static MethodHandle setter(FieldAccess access) {
        if (access.setter() == null) {
            throw new IllegalArgumentException(Lang.t("\"{0}\" in {1} can't be changed (it's a final static field)",
                    access.field().getName(), access.field().getDeclaringClass().getSimpleName()));
        }
        return access.setter();
    }

    private static FieldAccess field(Class<?> type, String name, boolean wantStatic) {
        String key = wantStatic ? "static " + name : name;
        FieldAccess known = FIELDS.get(type).get(key);
        if (known != null) return known;
        Field found = null;
        for (Class<?> c = type; c != null && found == null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name) && Modifier.isStatic(f.getModifiers()) == wantStatic) {
                    found = f;
                    break;
                }
            }
        }
        if (found == null) {
            Set<String> names = new LinkedHashSet<>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) if (Modifier.isStatic(f.getModifiers()) == wantStatic) names.add(f.getName());
            }
            throw new IllegalArgumentException(Lang.t("{0} has no field called \"{1}\"", type.getSimpleName(), name) + guess(name, names));
        }
        try {
            found.setAccessible(true);
            MethodHandle getter = LOOKUP.unreflectGetter(found);
            MethodHandle setter = null;
            try {
                setter = LOOKUP.unreflectSetter(found);
            } catch (IllegalAccessException finalStatic) {
                // Java doesn't let final static fields change: set() says so
            }
            if (wantStatic) {
                getter = MethodHandles.dropArguments(getter.asType(MethodType.methodType(Object.class)), 0, Object.class);
                if (setter != null) setter = MethodHandles.dropArguments(setter.asType(MethodType.methodType(void.class, Object.class)), 0, Object.class);
            } else {
                getter = getter.asType(MethodType.methodType(Object.class, Object.class));
                if (setter != null) setter = setter.asType(MethodType.methodType(void.class, Object.class, Object.class));
            }
            FieldAccess access = new FieldAccess(found, getter, setter);
            FIELDS.get(type).put(key, access);
            return access;
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't reach \"{0}\" in {1}", name, type.getSimpleName()), e);
        }
    }

    // ---- Methods ----

    /** Calls a method, private or not, with these arguments: {@code Reflect.call(player, "setSprinting", true);} */
    @SuppressWarnings("unchecked")
    public static <T> T call(Object target, String method, Object... args) {
        if (target == null) throw new IllegalArgumentException(Lang.t("can't call \"{0}\" on nothing (null)", method));
        return (T) invoke(target.getClass(), target, method, args == null ? new Object[] {null} : args, false);
    }

    /** Calls a static method, by the class's full name: {@code Reflect.callStatic("net.minecraft.client.Minecraft", "getInstance")}. */
    @SuppressWarnings("unchecked")
    public static <T> T callStatic(String className, String method, Object... args) {
        return (T) invoke(type(className), null, method, args == null ? new Object[] {null} : args, true);
    }

    /** Makes a new object of a class, with the constructor that fits the arguments. */
    @SuppressWarnings("unchecked")
    public static <T> T create(String className, Object... args) {
        return (T) invoke(type(className), null, "<init>", args == null ? new Object[] {null} : args, true);
    }

    private static Object invoke(Class<?> type, Object target, String name, Object[] args, boolean wantStatic) {
        Callable[] candidates = METHODS.get(type).get(wantStatic ? "static " + name : name);
        if (candidates == null) candidates = find(type, name, wantStatic);
        Callable chosen = choose(candidates, args, true);
        if (chosen == null) chosen = choose(candidates, args, false);
        if (chosen == null) {
            List<String> shapes = new ArrayList<>();
            for (Callable c : candidates) shapes.add(shape(c.params()));
            String what = name.equals("<init>") ? type.getSimpleName() : type.getSimpleName() + "." + name;
            throw new IllegalArgumentException(Lang.t("{0} doesn't take these values: ({1}). It takes: {2}", what,
                    describe(args), String.join(" or ", shapes)));
        }
        Object[] converted = args;
        for (int i = 0; i < args.length; i++) {
            Class<?> p = chosen.params()[i];
            if (p.isPrimitive() && args[i] != null && !boxed(p).isInstance(args[i])) {
                if (converted == args) converted = args.clone();
                converted[i] = convert(args[i], p, name);
            }
        }
        try {
            return (Object) chosen.invoker().invokeExact(target, converted);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    private static Callable[] find(Class<?> type, String name, boolean wantStatic) {
        List<Executable> found = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>(); // a method overridden lower down is only counted once
        if (name.equals("<init>")) {
            found.addAll(List.of(type.getDeclaredConstructors()));
        } else {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && !m.isBridge() && Modifier.isStatic(m.getModifiers()) == wantStatic
                            && seen.add(java.util.Arrays.toString(m.getParameterTypes()))) found.add(m);
                }
            }
            for (Method m : type.getMethods()) { // default methods from interfaces
                if (m.getName().equals(name) && !m.isBridge() && Modifier.isStatic(m.getModifiers()) == wantStatic
                        && seen.add(java.util.Arrays.toString(m.getParameterTypes()))) found.add(m);
            }
        }
        if (found.isEmpty()) {
            Set<String> names = new LinkedHashSet<>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) if (Modifier.isStatic(m.getModifiers()) == wantStatic) names.add(m.getName());
            }
            throw new IllegalArgumentException(Lang.t("{0} has no method called \"{1}\"", type.getSimpleName(), name) + guess(name, names));
        }
        Callable[] callables = new Callable[found.size()];
        for (int i = 0; i < callables.length; i++) {
            Executable e = found.get(i);
            try {
                e.setAccessible(true);
                MethodHandle h = e instanceof Method m ? LOOKUP.unreflect(m) : LOOKUP.unreflectConstructor((Constructor<?>) e);
                if (e.isVarArgs()) h = h.asFixedArity();
                h = h.asType(h.type().generic()).asSpreader(Object[].class, e.getParameterCount());
                // Every invoker takes (target, args): static methods and constructors just don't use the target
                boolean hasTarget = e instanceof Method m && !Modifier.isStatic(m.getModifiers());
                if (!hasTarget) h = MethodHandles.dropArguments(h, 0, Object.class);
                callables[i] = new Callable(e, e.getParameterTypes(), h);
            } catch (IllegalAccessException | RuntimeException problem) {
                throw new IllegalStateException(Lang.t("Squid couldn't reach \"{0}\" in {1}", name, type.getSimpleName()), problem);
            }
        }
        METHODS.get(type).put(wantStatic ? "static " + name : name, callables);
        return callables;
    }

    /** The method the arguments fit: exactly (strict), or with numbers turned into other kinds of numbers. */
    private static Callable choose(Callable[] candidates, Object[] args, boolean strict) {
        for (Callable c : candidates) {
            Class<?>[] params = c.params();
            if (params.length != args.length) continue;
            boolean fits = true;
            for (int i = 0; i < params.length && fits; i++) fits = fits(params[i], args[i], strict);
            if (fits) return c;
        }
        return null;
    }

    private static boolean fits(Class<?> param, Object arg, boolean strict) {
        if (arg == null) return !param.isPrimitive();
        if (!param.isPrimitive()) return param.isInstance(arg);
        Class<?> box = boxed(param);
        if (box.isInstance(arg)) return true;
        return !strict && arg instanceof Number && Number.class.isAssignableFrom(box);
    }

    // ---- Helpers ----

    /** A class by its full name, from the game (looked up once). */
    private static Class<?> type(String className) {
        if (className == null) throw new IllegalArgumentException(Lang.t("the class name is missing (null)"));
        Class<?> known = CLASSES.get(className);
        if (known != null) return known;
        ClassLoader loader = Main.gameLoader() != null ? Main.gameLoader() : Thread.currentThread().getContextClassLoader();
        try {
            Class<?> found = Class.forName(className, true, loader);
            CLASSES.put(className, found);
            return found;
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalArgumentException(Lang.t("there's no class called \"{0}\". Class names are written in full, like \"net.minecraft.client.Minecraft\"", className));
        }
    }

    /** A value made into the type a field or method needs: any number for a number. */
    private static Object convert(Object value, Class<?> type, String name) {
        if (!type.isPrimitive()) {
            if (value == null || type.isInstance(value)) return value;
            throw new IllegalArgumentException(Lang.t("\"{0}\" needs {1}, not {2}", name, type.getSimpleName(), describe(new Object[] {value})));
        }
        if (type == boolean.class && value instanceof Boolean) return value;
        if (type == char.class && value instanceof Character) return value;
        if (value instanceof Number n && type != boolean.class && type != char.class) {
            if (type == int.class) return n.intValue();
            if (type == long.class) return n.longValue();
            if (type == float.class) return n.floatValue();
            if (type == double.class) return n.doubleValue();
            if (type == short.class) return n.shortValue();
            return n.byteValue();
        }
        throw new IllegalArgumentException(Lang.t("\"{0}\" needs {1}, not {2}", name, type.getName(), describe(new Object[] {value})));
    }

    private static Class<?> boxed(Class<?> type) {
        return MethodType.methodType(type).wrap().returnType();
    }

    private static String shape(Class<?>[] params) {
        List<String> names = new ArrayList<>();
        for (Class<?> p : params) names.add(p.getSimpleName());
        return "(" + String.join(", ", names) + ")";
    }

    private static String describe(Object[] values) {
        List<String> parts = new ArrayList<>();
        for (Object v : values) parts.add(v == null ? "null" : v.getClass().getSimpleName());
        return String.join(", ", parts);
    }

    /** " Did you mean "experienceLevel"?" for a name that's close to a real one, else nothing. */
    static String guess(String wrong, Set<String> names) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String name : names) {
            int d = name.equalsIgnoreCase(wrong) ? 0 : distance(name.toLowerCase(), wrong.toLowerCase());
            if (d < bestDistance) {
                bestDistance = d;
                best = name;
            }
        }
        if (best == null || bestDistance > Math.max(2, wrong.length() / 3)) return "";
        return ". " + Lang.t("Did you mean \"{0}\"?", best);
    }

    /** How many letters need changing to turn one word into another. */
    private static int distance(String a, String b) {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int previous = row[0];
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int keep = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), previous + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                previous = keep;
            }
        }
        return row[b.length()];
    }

    /** Passes on what the field or method itself threw, as it is. */
    private static RuntimeException rethrow(Throwable e) {
        if (e instanceof RuntimeException r) return r;
        if (e instanceof Error error) throw error;
        return Reflect.<RuntimeException>sneaky(e);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable e) throws T {
        throw (T) e;
    }
}
