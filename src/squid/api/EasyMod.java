package squid.api;

import squid.Main;
import squid.Mistakes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The easy way to make a mod. Write a start() method, and use these commands in it:
 *
 * <pre>
 * public class Hello extends EasyMod {
 *     void start() {
 *         say("Hello!");
 *         onKey("H", () -> giveItem("diamond", 1));
 *     }
 * }
 * </pre>
 *
 * Save it as Hello.java in the mods folder and play. Squid compiles it by itself.
 * If something goes wrong while playing, the mod says what and on which line in the chat, and the game keeps going.
 */
public abstract class EasyMod implements SquidMod {
    private Squid squid;
    private boolean starting;
    private final List<Action> ticks = new ArrayList<>();
    private final List<Action> joins = new ArrayList<>();
    private final List<Action> leaves = new ArrayList<>();
    private final List<Timer> timers = new ArrayList<>();
    private final List<KeyAction> keys = new ArrayList<>();
    private final List<String[]> waitingMessages = new ArrayList<>(); // text and color, said before joining a world
    private Object world; // the world the player was in last tick, to notice joining and leaving
    private long worldTicks; // ticks spent in a world, not counting pauses

    private record Timer(int everyTicks, Action action) {
    }

    private record KeyAction(KeyBinding key, Action action) {
    }

    /** Something the mod asked to run later. If it keeps going wrong, it's switched off so the game stays fine. */
    private final class Action {
        private final String what;
        private final Runnable run;
        private int failures;

        Action(String what, Runnable run) {
            this.what = what;
            this.run = run;
        }

        void run() {
            if (failures >= 3) return;
            try {
                run.run();
            } catch (RuntimeException | LinkageError e) {
                failures++;
                if (failures == 1) {
                    System.out.println("[" + squid.mod().id() + "] " + what + " went wrong:");
                    e.printStackTrace(System.out);
                    problem(what + ": " + Mistakes.explain(e, EasyMod.this.getClass()));
                }
                if (failures == 3) problem("turned off " + what + " because it kept going wrong.");
            }
        }
    }

    @Override
    public final void init(Squid squid) {
        this.squid = squid;
        squid.onTick(this::tick);
        Method start;
        try {
            start = getClass().getDeclaredMethod("start");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("your mod needs a start() method, like: void start() { say(\"Hi!\"); }");
        }
        start.setAccessible(true);
        starting = true;
        try {
            start.invoke(this);
        } catch (InvocationTargetException e) {
            // Pass the mod's own error on, so Squid can say which line it came from
            if (e.getCause() instanceof RuntimeException problem) throw problem;
            if (e.getCause() instanceof Error problem) throw problem;
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } finally {
            starting = false;
        }
    }

    // ---- When things happen ----

    /** Runs when you join a world or a server, and again each time you switch worlds. */
    protected void onJoin(Runnable action) {
        joins.add(new Action("onJoin", action));
    }

    /** Runs when you leave a world or a server. */
    protected void onLeave(Runnable action) {
        leaves.add(new Action("onLeave", action));
    }

    /**
     * Runs when you press a key: a letter like "G", a number like "5", or a key like "SPACE", "F6" or "LEFT_SHIFT".
     * The key shows up in Options > Controls, where anyone can change it. Use it inside start().
     */
    protected void onKey(String key, Runnable action) {
        if (!starting) throw new IllegalStateException("onKey only works inside start()");
        int code = Keys.code(key, Main.gameLoader());
        KeyBinding binding = squid.addKeyBinding(squid.mod().name() + " (" + key.toUpperCase() + ")", code);
        keys.add(new KeyAction(binding, new Action("onKey(\"" + key + "\")", action)));
    }

    /** Runs 20 times a second while you're in a world. */
    protected void onTick(Runnable action) {
        ticks.add(new Action("onTick", action));
    }

    /** Runs every so many seconds while you're in a world (and the game isn't paused), like every(5, ...) for every 5 seconds. */
    protected void every(double seconds, Runnable action) {
        int everyTicks = Math.max(1, (int) Math.round(seconds * 20));
        timers.add(new Timer(everyTicks, new Action("every(" + seconds + ")", action)));
    }

    // ---- Things to do ----

    /** Shows a message in the chat that only you can see. Said before you're in a world, it waits until you join. */
    protected void say(Object message) {
        String text = String.valueOf(message);
        if (!Game.inWorld()) {
            waitingMessages.add(new String[] {text, "WHITE"});
            return;
        }
        Game.chat(text, "WHITE");
    }

    /** Shows text just above your hotbar for a few seconds. */
    protected void showText(Object text) {
        if (Game.inWorld()) Game.overlay(String.valueOf(text));
    }

    /** Runs a command like you typed it in chat: command("time set day"). The / at the start is optional. */
    protected void command(String command) {
        if (!Game.inWorld()) throw new IllegalStateException("command() only works while you're in a world. Try it in onJoin or onKey");
        Game.command(command.startsWith("/") ? command.substring(1) : command);
    }

    /** Gives you items, like giveItem("diamond", 3). It uses the /give command, so cheats need to be on. */
    protected void giveItem(String item, int count) {
        command("give @s " + item + " " + count);
    }

    /** Gives you one of an item, like giveItem("apple"). Cheats need to be on. */
    protected void giveItem(String item) {
        giveItem(item, 1);
    }

    /** Plays a sound only you hear, by its Minecraft name, like playSound("entity.experience_orb.pickup"). */
    protected void playSound(String sound) {
        if (Game.inWorld()) Game.playSound(sound);
    }

    /** Changes the yellow text on the title screen. Use it inside start(). */
    protected void splash(String text) {
        if (!starting) throw new IllegalStateException("splash only works inside start()");
        squid.atStart("net.minecraft.client.resources.SplashManager", "getSplash", call -> call.cancel(Game.splash(text)));
    }

    /** Writes a line in the game's log (kelp-output.log), for checking what your mod is doing. */
    protected void log(Object message) {
        squid.log(String.valueOf(message));
    }

    // ---- Things to know ----

    /** Whether you're in a world right now. */
    protected boolean inWorld() {
        return Game.inWorld();
    }

    /** Your player name. */
    protected String playerName() {
        return Game.inWorld() ? Game.playerName() : "";
    }

    /** Where you are: east-west. */
    protected int x() {
        return (int) Math.floor(Game.position(0));
    }

    /** Where you are: up-down. */
    protected int y() {
        return (int) Math.floor(Game.position(1));
    }

    /** Where you are: north-south. */
    protected int z() {
        return (int) Math.floor(Game.position(2));
    }

    /** Your health, from 0 to 20. Each heart is 2. */
    protected double health() {
        return Game.inWorld() ? Game.health() : 0;
    }

    /** A random whole number from min to max, both included. */
    protected int random(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /** For bigger mods: everything Squid can do, like hooks and drawing on the screen. */
    protected Squid squid() {
        return squid;
    }

    // ---- Squid runs these ----

    private void tick() {
        Object now = Game.inWorld() ? Game.world() : null;
        if (now != world) {
            if (world != null) leaves.forEach(Action::run);
            world = now;
            if (now != null) {
                for (String[] message : waitingMessages) Game.chat(message[0], message[1]);
                waitingMessages.clear();
                joins.forEach(Action::run);
            }
        }
        for (KeyAction key : keys) {
            while (key.key().pressed()) key.action().run(); // each press counts once
        }
        if (now == null || Game.paused()) return;
        worldTicks++;
        ticks.forEach(Action::run);
        for (Timer timer : timers) {
            if (worldTicks % timer.everyTicks() == 0) timer.action().run();
        }
    }

    /** Tells the player in red that something in this mod went wrong. */
    private void problem(String message) {
        String text = "[" + squid.mod().name() + "] " + message;
        if (Game.inWorld()) Game.chat(text, "RED");
        else waitingMessages.add(new String[] {text, "RED"});
    }
}
