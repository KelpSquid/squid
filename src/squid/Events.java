package squid;

import squid.api.Hud;
import squid.api.SoundCue;

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
    private static final List<Listener<SoundCue>> CUES = new CopyOnWriteArrayList<>();
    private static final List<Listener<squid.api.WorldDraw>> WORLD = new CopyOnWriteArrayList<>();
    private static final java.util.Map<String, List<Listener<Object>>> GAME = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<Command> COMMANDS = new CopyOnWriteArrayList<>();

    /** A mod's chat command, like "!dance". */
    private record Command(String name, Listener<String> listener) {
    }

    /**
     * A mod listens for something happening in the game: "break" (a block's name), "attack" (a mob's name), "pickup"
     * (an item's name and how many, as an Object[]) or "chat" (a message's text). Squid hooks each of these once at
     * startup (see squid.api.GameEvents), so mods made or reloaded while playing hear them too.
     */
    public static void on(String event, String modId, Consumer<Object> run) {
        GAME.computeIfAbsent(event, e -> new CopyOnWriteArrayList<>()).add(new Listener<>(modId, run, new AtomicInteger()));
    }

    /** Whether any mod listens for this event, so Squid only works out what happened when someone wants to know. */
    public static boolean listening(String event) {
        List<Listener<Object>> listeners = GAME.get(event);
        return listeners != null && !listeners.isEmpty();
    }

    /** Hands something that happened to every mod listening for it. */
    public static void fire(String event, Object value) {
        List<Listener<Object>> listeners = GAME.get(event);
        if (listeners != null) run(listeners, value);
    }

    /** The kinds of mobs each mod wants outlined through walls (like "creeper"), by mod id. */
    private static final java.util.Map<String, java.util.Set<String>> GLOWING = new java.util.concurrent.ConcurrentHashMap<>();

    /** A mod starts (or stops) outlining a kind of mob, like "creeper", so it shows through walls. */
    public static void glow(String modId, String mob, boolean on) {
        java.util.Set<String> kinds = GLOWING.computeIfAbsent(modId, id -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        if (on) kinds.add(mob);
        else kinds.remove(mob);
    }

    /** Whether any mod outlines anything right now (most of the time nothing does, and this is all that's checked). */
    public static boolean anyGlowing() {
        for (java.util.Set<String> kinds : GLOWING.values()) if (!kinds.isEmpty()) return true;
        return false;
    }

    /** Whether some mod outlines this kind of mob. */
    public static boolean glowing(String mob) {
        for (java.util.Set<String> kinds : GLOWING.values()) if (kinds.contains(mob)) return true;
        return false;
    }

    /** A mod's chat command: typing "!name" (and maybe more words) in the chat runs it instead of sending it. */
    public static void onCommand(String modId, String name, Consumer<String> run) {
        String command = "!" + name.toLowerCase(java.util.Locale.ROOT).replaceFirst("^!", "");
        COMMANDS.add(new Command(command, new Listener<>(modId, run, new AtomicInteger())));
    }

    /**
     * Runs every mod's command for what was typed, like "!shout hello" (each gets the words after it, "hello").
     * True if there was one, so the message isn't sent to the server.
     */
    public static boolean command(String typed) {
        String text = typed.strip();
        String first = text.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        List<Listener<String>> found = new java.util.ArrayList<>();
        for (Command command : COMMANDS) if (command.name().equals(first)) found.add(command.listener());
        if (found.isEmpty()) return false;
        run(found, text.length() > first.length() ? text.substring(first.length()).strip() : "");
        return true;
    }

    /**
     * Squid's own hook into one of Minecraft's methods, for the events above. It only works while Squid starts up,
     * before Minecraft's classes load.
     */
    public static void hookAtStartup(String className, String method, boolean atStart, squid.api.Hook hook) {
        // Mods hook with their own Squid (so their hooks can be turned off and reloaded); this is Squid's alone
        if (Main.gameStarted() || !Thread.currentThread().getStackTrace()[2].getClassName().startsWith("squid.")) {
            throw new IllegalStateException("Events.hookAtStartup is Squid's own; mods use Squid.atStart and atEnd");
        }
        Transformers.add(className, new Transformers.HookPatch(method, null, atStart, Hooks.register("squid", hook)));
    }

    public static void onTick(String modId, Runnable tick) {
        TICKS.add(new Listener<>(modId, nothing -> tick.run(), new AtomicInteger()));
    }

    public static void onHud(String modId, Consumer<Hud> draw) {
        HUDS.add(new Listener<>(modId, draw, new AtomicInteger()));
    }

    /** A mod draws in the world every frame (see squid.api.Squid#onWorldDraw). */
    public static void onWorldDraw(String modId, Consumer<squid.api.WorldDraw> draw) {
        WORLD.add(new Listener<>(modId, draw, new AtomicInteger()));
    }

    /** Whether any mod draws in the world, so Squid only gets ready to when one does. */
    public static boolean drawingWorld() {
        return !WORLD.isEmpty();
    }

    /** Lets every mod draw in the world for this frame (Squid Mods calls this while Minecraft gathers what to draw). */
    public static void drawWorld(WorldPainter painter) {
        // Each mod gets its own WorldDraw, so one mod's throughWalls(true) doesn't carry over to the next
        for (Listener<squid.api.WorldDraw> listener : WORLD) runOne(listener, new squid.api.WorldDraw(painter));
    }

    public static void onSoundCue(String modId, Consumer<SoundCue> cue) {
        CUES.add(new Listener<>(modId, cue, new AtomicInteger()));
    }

    /** Hands a .sqda cue to every mod listening (Squid Sounds calls this as cues are heard). */
    public static void soundCue(SoundCue cue) {
        run(CUES, cue);
    }

    /** Removes a mod's listeners, like when it's reloaded or turned off. */
    static void remove(String modId) {
        TICKS.removeIf(l -> l.modId().equals(modId));
        HUDS.removeIf(l -> l.modId().equals(modId));
        CUES.removeIf(l -> l.modId().equals(modId));
        WORLD.removeIf(l -> l.modId().equals(modId));
        for (List<Listener<Object>> listeners : GAME.values()) listeners.removeIf(l -> l.modId().equals(modId));
        COMMANDS.removeIf(c -> c.listener().modId().equals(modId));
        GLOWING.remove(modId);
        ModNet.remove(modId);
        ModBus.remove(modId);
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
        for (Listener<T> listener : listeners) runOne(listener, value);
    }

    private static <T> void runOne(Listener<T> listener, T value) {
        if (listener.failures().get() >= 3) return;
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
