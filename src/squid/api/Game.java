package squid.api;

import squid.Lang;
import squid.Main;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The bits of Minecraft that {@link EasyMod}'s commands use. Squid is built without Minecraft,
 * so it finds them by name the first time they're needed. Only call these on the game's own thread (in hooks).
 */
final class Game {
    private static Object minecraft;
    private static Field player;
    private static Field level;
    private static Field gui;

    private Game() {
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, true, Main.gameLoader());
    }

    /** The running Minecraft, or null while it's still starting up. */
    private static Object minecraft() {
        if (minecraft == null && Main.gameStarted()) {
            try {
                Class<?> type = type("net.minecraft.client.Minecraft");
                player = type.getField("player");
                level = type.getField("level");
                gui = type.getField("gui");
                minecraft = type.getMethod("getInstance").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(Lang.t("Squid couldn't find Minecraft"), e);
            }
        }
        return minecraft;
    }

    /** Calls the method with this name whose parameters fit the arguments (Minecraft often has a few with one name). */
    private static Object call(Object target, String method, Object... args) {
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(method) && fits(m.getParameterTypes(), args)) {
                    m.setAccessible(true); // the method can be public in a class that isn't
                    return m.invoke(target, args);
                }
            }
            throw new NoSuchMethodException(method);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't use Minecraft's {0}", method), e);
        }
    }

    private static boolean fits(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (args[i] == null) {
                if (type.isPrimitive()) return false;
                continue;
            }
            if (type == boolean.class) type = Boolean.class;
            else if (type == int.class) type = Integer.class;
            else if (type == float.class) type = Float.class;
            else if (type == double.class) type = Double.class;
            else if (type == long.class) type = Long.class;
            if (!type.isInstance(args[i])) return false;
        }
        return true;
    }

    static Object player() {
        Object mc = minecraft();
        if (mc == null) return null;
        try {
            return player.get(mc);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object world() {
        Object mc = minecraft();
        if (mc == null) return null;
        try {
            return level.get(mc);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean inWorld() {
        return player() != null && world() != null;
    }

    static boolean paused() {
        return (boolean) call(minecraft(), "isPaused");
    }

    /** Minecraft's text, in one of its colors like "RED" or "YELLOW". */
    static Object text(String text, String color) {
        try {
            Object literal = type("net.minecraft.network.chat.Component").getMethod("literal", String.class).invoke(null, text);
            Class<?> formatting = type("net.minecraft.ChatFormatting");
            Object style = formatting.getMethod("valueOf", String.class).invoke(null, color);
            return literal.getClass().getMethod("withStyle", formatting).invoke(literal, style);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't make text"), e);
        }
    }

    private static Object chatListener() {
        try {
            return call(gui.get(minecraft()), "chatListener");
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void chat(String message, String color) {
        call(chatListener(), "handleSystemMessage", text(message, color), false);
    }

    static void overlay(String message) {
        call(chatListener(), "handleOverlay", text(message, "WHITE"));
    }

    static void command(String command) {
        try {
            Object connection = player().getClass().getField("connection").get(player());
            call(connection, "sendCommand", command);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't send the command"), e);
        }
    }

    static void playSound(String name) {
        try {
            Object id = type("net.minecraft.resources.Identifier").getMethod("tryParse", String.class).invoke(null, name);
            Object registry = type("net.minecraft.core.registries.BuiltInRegistries").getField("SOUND_EVENT").get(null);
            Object sound = id == null ? null : call(registry, "getValue", id);
            if (sound == null) {
                throw new IllegalArgumentException(Lang.t("Minecraft has no sound called \"{0}\"."
                        + " Sound names look like \"entity.experience_orb.pickup\"", name));
            }
            Class<?> soundEvent = type("net.minecraft.sounds.SoundEvent");
            Object instance = type("net.minecraft.client.resources.sounds.SimpleSoundInstance")
                    .getMethod("forUI", soundEvent, float.class).invoke(null, sound, 1f);
            call(call(minecraft(), "getSoundManager"), "play", instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't play the sound"), e);
        }
    }

    /** The yellow title screen text Minecraft shows, made from our own text. */
    static Object splash(String text) {
        try {
            Class<?> component = type("net.minecraft.network.chat.Component");
            return type("net.minecraft.client.gui.components.SplashRenderer").getConstructor(component)
                    .newInstance(text(text, "YELLOW"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't change the splash text"), e);
        }
    }

    static String playerName() {
        return (String) call(call(player(), "getName"), "getString");
    }

    /** 0 = x, 1 = y, 2 = z. Zero when you're not in a world. */
    static double position(int axis) {
        if (!inWorld()) return 0;
        return (double) call(player(), axis == 0 ? "getX" : axis == 1 ? "getY" : "getZ");
    }

    static double health() {
        return (float) call(player(), "getHealth");
    }
}
