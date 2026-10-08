package squid.api;

import squid.Lang;
import squid.Main;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The bits of Minecraft that {@link EasyMod}'s commands use. Squid is built without Minecraft,
 * so it finds them by name the first time they're needed. Only call these on the game's own thread (in hooks).
 */
public final class Game {
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

    /** True while a mod's own message is being shown, so onChat doesn't hear mods talking (and loop forever). */
    static boolean saying;

    /** A chat message only this player sees, in one of Minecraft's colors like "GREEN". Squid uses it too. */
    public static void chat(String message, String color) {
        saying = true;
        try {
            call(chatListener(), "handleSystemMessage", text(message, color), false);
        } finally {
            saying = false;
        }
    }

    /** A chat message's words: Minecraft's text turned into plain letters. */
    static String plain(Object component) {
        return component == null ? "" : (String) call(component, "getString");
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

    // ---- More things to do and know (EasyMod's newer commands) ----

    private static Object field(Object target, String name) {
        try {
            return target.getClass().getField(name).get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't use Minecraft's {0}", name), e);
        }
    }

    /** A name's last part, like "creeper" for minecraft:creeper, from a registry id. */
    private static String path(Object identifier) {
        return identifier == null ? "" : (String) call(identifier, "getPath");
    }

    /** Big text in the middle of the screen, with smaller text under it (either can be ""). */
    static void title(String big, String small) {
        Object hud;
        try {
            hud = field(gui.get(minecraft()), "hud"); // Minecraft's titles live on the Gui's Hud
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        call(hud, "setTimes", 10, 60, 20);
        call(hud, "setSubtitle", text(small, "WHITE"));
        call(hud, "setTitle", text(big, "WHITE"));
    }

    /** Adds to the player's speed: x east, y up, z south (blocks per tick). */
    static void push(double x, double y, double z) {
        Object player = player();
        Object speed = call(player, "getDeltaMovement");
        call(player, "setDeltaMovement", (double) field(speed, "x") + x, (double) field(speed, "y") + y, (double) field(speed, "z") + z);
    }

    /** Which way the player looks, as a direction 1 long: x, y, z. */
    static double[] look() {
        Object look = call(player(), "getLookAngle");
        return new double[] {(double) field(look, "x"), (double) field(look, "y"), (double) field(look, "z")};
    }

    /** Puts particles around the player (only this player sees them), by name like "heart" or "flame". */
    static void particles(String name, int count) {
        try {
            Object id = type("net.minecraft.resources.Identifier").getMethod("tryParse", String.class).invoke(null, name);
            Object registry = type("net.minecraft.core.registries.BuiltInRegistries").getField("PARTICLE_TYPE").get(null);
            Object kind = id == null ? null : call(registry, "getValue", id);
            if (kind == null || !type("net.minecraft.core.particles.ParticleOptions").isInstance(kind)) {
                throw new IllegalArgumentException(Lang.t("Minecraft has no simple particle called \"{0}\". Try \"heart\", \"flame\", \"happy_villager\" or \"note\"", name));
            }
            Object level = world();
            double px = position(0);
            double py = position(1);
            double pz = position(2);
            java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
            for (int i = 0; i < Math.min(count, 200); i++) {
                call(level, "addParticle", kind, px + random.nextDouble(-1, 1), py + random.nextDouble(0.2, 2), pz + random.nextDouble(-1, 1),
                        random.nextDouble(-0.05, 0.05), random.nextDouble(0, 0.1), random.nextDouble(-0.05, 0.05));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't make the particles"), e);
        }
    }

    /** How many mobs of a kind (like "creeper") are within this many blocks. "" counts every mob. */
    static int nearby(String mob, double distance) {
        try {
            Object player = player();
            Object box = call(call(player, "getBoundingBox"), "inflate", distance);
            Class<?> entityType = type("net.minecraft.world.entity.EntityType");
            java.lang.reflect.Method key = entityType.getMethod("getKey", entityType);
            String wanted = mob.contains(":") ? mob.substring(mob.indexOf(':') + 1) : mob;
            int count = 0;
            for (Object entity : (java.util.List<?>) call(world(), "getEntities", player, box, (java.util.function.Predicate<Object>) e -> true)) {
                if (!type("net.minecraft.world.entity.LivingEntity").isInstance(entity)) continue;
                if (!wanted.isEmpty() && !path(key.invoke(null, call(entity, "getType"))).equals(wanted)) continue;
                count++;
            }
            if (count == 0 && !wanted.isEmpty() && !isMob(mob)) throw noSuchMob(mob);
            return count;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't look around"), e);
        }
    }

    /** Whether Minecraft has a kind of mob (or other entity) by this name, like "creeper". */
    static boolean isMob(String name) {
        try {
            Object id = type("net.minecraft.resources.Identifier").getMethod("tryParse", String.class).invoke(null, name);
            Object registry = type("net.minecraft.core.registries.BuiltInRegistries").getField("ENTITY_TYPE").get(null);
            return id != null && (boolean) call(registry, "containsKey", id);
        } catch (ReflectiveOperationException e) {
            return true; // can't tell: don't complain
        }
    }

    /** A clear message for a mob name Minecraft doesn't have, so a typo isn't silently ignored. */
    static IllegalArgumentException noSuchMob(String name) {
        return new IllegalArgumentException(Lang.t("Minecraft has no mob called \"{0}\". Try \"creeper\", \"zombie\" or \"pig\"", name));
    }

    /** The item in the player's main hand, like "diamond_sword", or "" for an empty hand. */
    static String holding() {
        try {
            Object stack = call(player(), "getMainHandItem");
            if ((boolean) call(stack, "isEmpty")) return "";
            Object registry = type("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM").get(null);
            return path(call(registry, "getKey", call(stack, "getItem")));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the player's crosshair is on: a block like "oak_log", a mob like "cow", or "" for nothing. */
    static String lookingAt() {
        Object hit = field(minecraft(), "hitResult");
        if (hit == null) return "";
        String kind = ((Enum<?>) call(hit, "getType")).name();
        if (kind.equals("BLOCK")) return blockAt(call(hit, "getBlockPos"));
        if (kind.equals("ENTITY")) return entityName(call(hit, "getEntity"));
        return "";
    }

    /**
     * The item this player just picked up and how many, like {"diamond", 3}, from Minecraft's "item taken" message; null if it's
     * someone else picking something up, an experience orb, or the message is still on its way in (Minecraft first
     * sees it on the network thread, then hands it to the game's own thread, where it counts).
     */
    static Object[] pickedUp(Object packet) {
        Object mc = minecraft();
        Object player = player();
        Object level = world();
        if (mc == null || player == null || level == null || !(boolean) call(mc, "isSameThread")) return null;
        if ((int) call(packet, "getPlayerId") != (int) call(player, "getId")) return null;
        Object entity = call(level, "getEntity", call(packet, "getItemId"));
        try {
            if (entity == null || !type("net.minecraft.world.entity.item.ItemEntity").isInstance(entity)) return null;
            Object item = call(call(entity, "getItem"), "getItem");
            Object registry = type("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM").get(null);
            return new Object[] {path(call(registry, "getKey", item)), (Integer) call(packet, "getAmount")};
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What's being played right now, as the lines Squid keeps in squid-last-played.txt for Kelp's Continue button:
     * "world" and the world's folder name, or "server", its address and its name. Null for Realms (Kelp can't join
     * those by itself) or when it can't tell.
     */
    static String lastPlayed() {
        Object mc = minecraft();
        if (mc == null) return null;
        Object local = call(mc, "getSingleplayerServer");
        if (local != null) {
            try {
                Object root = type("net.minecraft.world.level.storage.LevelResource").getField("ROOT").get(null);
                java.nio.file.Path folder = ((java.nio.file.Path) call(local, "getWorldPath", root)).toAbsolutePath().normalize();
                return folder.getFileName() == null ? null : "world\n" + folder.getFileName() + "\n";
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
        Object server = call(mc, "getCurrentServer");
        // Not Realms (Kelp can't join those by itself), and not LAN games (their address changes every time)
        String kind = server == null ? "" : ((Enum<?>) call(server, "type")).name();
        if (server == null || kind.equals("REALM") || kind.equals("LAN")) return null;
        String address = String.valueOf(field(server, "ip")).strip();
        String name = String.valueOf(field(server, "name")).strip().replace('\n', ' ');
        return address.isEmpty() ? null : "server\n" + address + "\n" + name + "\n";
    }

    /** Each kind of entity's short name ("creeper"), worked out once per kind, for kindOf. */
    private static final java.util.Map<Object, String> KIND_NAMES = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private static final ClassValue<Method> GET_TYPE = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                return type.getMethod("getType");
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    };

    /** What kind of entity this is ("creeper"), quickly enough to ask for every mob every frame. Null if unknown. */
    static String kindOf(Object entity) {
        try {
            Method getType = GET_TYPE.get(entity.getClass());
            if (getType == null) return null;
            Object type = getType.invoke(entity);
            String name = KIND_NAMES.get(type);
            if (name == null) {
                name = entityName(entity);
                KIND_NAMES.put(type, name);
            }
            return name;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** What kind of mob (or player, or thing) an entity is, like "zombie" or "player". */
    static String entityName(Object entity) {
        try {
            Class<?> entityType = type("net.minecraft.world.entity.EntityType");
            return path(entityType.getMethod("getKey", entityType).invoke(null, call(entity, "getType")));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The name of the block at a spot in the world (a BlockPos), like "stone" or "diamond_ore". */
    static String blockAt(Object pos) {
        try {
            Object state = call(world(), "getBlockState", pos);
            Object registry = type("net.minecraft.core.registries.BuiltInRegistries").getField("BLOCK").get(null);
            return path(call(registry, "getKey", call(state, "getBlock")));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The dimension the player is in: "overworld", "the_nether" or "the_end". */
    static String dimension() {
        return path(call(call(world(), "dimension"), "identifier"));
    }

    /** The biome the player is in, like "plains" or "deep_dark". */
    static String biome() {
        Object holder = call(world(), "getBiome", call(player(), "blockPosition"));
        Object key = ((java.util.Optional<?>) call(holder, "unwrapKey")).orElse(null);
        return key == null ? "" : path(call(key, "identifier"));
    }

    /** The player's experience level (the green number above the hotbar). */
    static int xpLevel() {
        return (int) field(player(), "experienceLevel");
    }

    /** Whether it's raining (or snowing) in the world. */
    static boolean raining() {
        return (boolean) call(world(), "isRaining");
    }

    /** Whether it's dark outside (night, or a storm). */
    static boolean dark() {
        return (boolean) call(world(), "isDarkOutside");
    }
}
