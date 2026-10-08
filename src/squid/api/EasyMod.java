package squid.api;

import squid.Lang;
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
    private final List<Action> hurts = new ArrayList<>();
    private final List<Action> deaths = new ArrayList<>();
    private double lastHealth = -1; // health last tick, to notice getting hurt and dying
    private int ticksInWorld; // since joining: your real health arrives from the server a moment after you join
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
    /** Chat commands like "!dance", and the mod that has each one. */
    private static final java.util.Map<String, String> COMMANDS = new java.util.concurrent.ConcurrentHashMap<>();

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
                if (failures == 3) problem(Lang.t("turned off {0} because it kept going wrong.", what));
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
            throw new IllegalStateException(Lang.t("your mod needs a start() method, like: void start() { say(\"Hi!\"); }"));
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
        if (!starting) throw new IllegalStateException(Lang.t("onKey only works inside start()"));
        int code = Keys.code(key, Main.gameLoader());
        KeyBinding binding = squid.addKeyBinding(squid.mod().name() + " (" + key.toUpperCase() + ")", code);
        keys.add(new KeyAction(binding, new Action("onKey(\"" + key + "\")", action)));
    }

    /** Runs when you get hurt (your health goes down). */
    protected void onHurt(Runnable action) {
        hurts.add(new Action("onHurt", action));
    }

    /** Runs when you die. */
    protected void onDeath(Runnable action) {
        deaths.add(new Action("onDeath", action));
    }

    /**
     * Runs on every beat of the music playing: a Jukebox song, or a .sqda with beats in it. Like
     * onBeat(() -> particles("note", 3)) for notes that dance to the music.
     */
    protected void onBeat(Runnable action) {
        if (!starting) throw new IllegalStateException(Lang.t("onBeat only works inside start()"));
        Action beat = new Action("onBeat", action);
        squid.onSoundCue(cue -> {
            if (Game.inWorld() && (cue.kind().equals("beat") || cue.kind().equals("bar"))) beat.run();
        });
    }

    /**
     * Your own chat command: typing "!name" in the chat runs it, and the message isn't sent to anyone. Like
     * onCommand("dance", () -> particles("note", 10)) for typing !dance.
     */
    protected void onCommand(String name, Runnable action) {
        onCommand(name, words -> action.run());
    }

    /**
     * A chat command that gets the words typed after it: onCommand("say", words -> title(words)) shows a title for
     * "!say Hello there".
     */
    protected void onCommand(String name, java.util.function.Consumer<String> action) {
        if (!starting) throw new IllegalStateException(Lang.t("onCommand only works inside start()"));
        String command = "!" + name.toLowerCase(java.util.Locale.ROOT).replaceFirst("^!", "");
        // The first mod to claim a command gets it (the message stops there), so a second one is told why it's quiet
        String owner = COMMANDS.putIfAbsent(command, squid.mod().id());
        if (owner != null && !owner.equals(squid.mod().id())) {
            problem(Lang.t("{0} is already a command in {1}, so this one won't run. Pick another name.", command, owner));
        }
        String[] words = {""}; // what was typed after the command, handed to the action
        Action run = new Action("onCommand(\"" + name + "\")", () -> action.accept(words[0]));
        squid.atStart("net.minecraft.client.multiplayer.ClientPacketListener", "sendChat", call -> {
            String typed = String.valueOf(call.args()[0]).strip();
            String first = typed.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
            if (!first.equals(command)) return;
            call.cancel(); // it's yours: it doesn't go to the server
            words[0] = typed.length() > first.length() ? typed.substring(first.length()).strip() : "";
            run.run();
        });
    }

    /**
     * Runs for every chat message you see: other players' ("<Name> hi!") and the game's ("Steve joined the game").
     * Messages from mods (like say()) aren't included, so a mod can answer without hearing itself. Messages from
     * players you've blocked never show, so they don't count either.
     */
    protected void onChat(java.util.function.Consumer<String> action) {
        if (!starting) throw new IllegalStateException(Lang.t("onChat only works inside start()"));
        String[] text = {""};
        Action run = new Action("onChat", () -> action.accept(text[0]));
        // Every message the chat box shows ends up here, just as it's shown: players', the server's, /say...
        squid.atEnd("net.minecraft.client.gui.components.ChatComponent", "addMessage", call -> {
            if (Game.saying) return; // a mod talking
            text[0] = Game.plain(call.args()[0]);
            run.run();
        });
    }

    /**
     * Runs when you break a block, with the block's name: onBreak(block -> { if (block.equals("diamond_ore"))
     * say("Diamonds!"); }). Names are like "stone", "oak_log" and "deepslate_diamond_ore".
     */
    protected void onBreak(java.util.function.Consumer<String> action) {
        if (!starting) throw new IllegalStateException(Lang.t("onBreak only works inside start()"));
        String[] block = {""};
        Action run = new Action("onBreak", () -> action.accept(block[0]));
        // The block's name is read just before it's broken (after, it's air)
        squid.atStart("net.minecraft.client.multiplayer.MultiPlayerGameMode", "destroyBlock", call -> {
            try {
                block[0] = Game.blockAt(call.args()[0]);
            } catch (RuntimeException e) {
                block[0] = "";
            }
        });
        squid.atEnd("net.minecraft.client.multiplayer.MultiPlayerGameMode", "destroyBlock", call -> {
            if (Boolean.TRUE.equals(call.returnValue()) && !block[0].isEmpty()) run.run();
        });
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
        if (!Game.inWorld()) throw new IllegalStateException(Lang.t("command() only works while you're in a world. Try it in onJoin or onKey"));
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

    /** Shows big text in the middle of the screen for a few seconds, like title("Level up!"). */
    protected void title(Object text) {
        title(text, "");
    }

    /** Big text in the middle of the screen, with smaller text under it: title("Boss fight!", "Good luck"). */
    protected void title(Object text, Object smaller) {
        if (Game.inWorld()) Game.title(String.valueOf(text), String.valueOf(smaller));
    }

    /** Shoots you up into the air: boost(1) is a big jump, boost(3) reaches the clouds. Falling still hurts! */
    protected void boost(double up) {
        if (Game.inWorld()) Game.push(0, up, 0);
    }

    /** Dashes you forward, the way you're looking: dash(1) is quick, dash(3) is very far. */
    protected void dash(double strength) {
        if (!Game.inWorld()) return;
        double[] look = Game.look();
        Game.push(look[0] * strength, look[1] * strength * 0.5, look[2] * strength);
    }

    /** Puts particles around you that only you see, like particles("heart", 10). Try "flame", "note", "happy_villager". */
    protected void particles(String name, int count) {
        if (Game.inWorld()) Game.particles(name, count);
    }

    /** Changes the yellow text on the title screen. Use it inside start(). */
    protected void splash(String text) {
        if (!starting) throw new IllegalStateException(Lang.t("splash only works inside start()"));
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

    /** How many of a mob are near you, like nearby("creeper", 16). nearby("", 16) counts every mob. */
    protected int nearby(String mob, double distance) {
        return Game.inWorld() ? Game.nearby(mob, distance) : 0;
    }

    /** What's in your hand, like "diamond_sword", or "" if it's empty. */
    protected String holding() {
        return Game.inWorld() ? Game.holding() : "";
    }

    /** What you're looking at: a block like "oak_log", a mob like "cow", or "" for nothing. */
    protected String lookingAt() {
        return Game.inWorld() ? Game.lookingAt() : "";
    }

    /** The biome you're in, like "plains", "desert" or "deep_dark". */
    protected String biome() {
        return Game.inWorld() ? Game.biome() : "";
    }

    /** Whether it's night (or dark from a storm) where you are. */
    protected boolean isNight() {
        return Game.inWorld() && Game.dark();
    }

    /** How loud the music playing is right now, from 0 (quiet, or none) to 1. */
    protected double musicLevel() {
        return SquidAudio.level();
    }

    /** What's playing: a Jukebox song like "Pigstep - Lena Raine", a .sqda's name, or "" when nothing is. */
    protected String nowPlaying() {
        return SquidAudio.playing();
    }

    /** A random whole number from min to max, both included. */
    protected int random(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * A setting players can switch in Squid's Mods screen, like setting("Show map", true). Ask for it whenever you need
     * it: it gives back what the player picked, or the default.
     */
    protected boolean setting(String name, boolean defaultValue) {
        return squid.settings().toggle(name, defaultValue);
    }

    /** A number setting players pick with a slider, from min to max, like setting("Zoom", 4, 1, 10). */
    protected int setting(String name, int defaultValue, int min, int max) {
        return squid.settings().number(name, defaultValue, min, max);
    }

    /** A setting with a few choices, like setting("Corner", "Top left", "Top left", "Top right"). */
    protected String setting(String name, String defaultValue, String... choices) {
        return squid.settings().choice(name, defaultValue, choices);
    }

    /**
     * Keeps a number for next time you play, like remember("diamonds", diamonds). Get it back with remembered().
     */
    protected void remember(String name, int value) {
        squid.settings().remember(name, value);
    }

    /** A number kept with remember(), or startingValue the first time: int diamonds = remembered("diamonds", 0); */
    protected int remembered(String name, int startingValue) {
        return squid.settings().remembered(name, startingValue);
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
            ticksInWorld = 0;
            if (now != null) {
                for (String[] message : waitingMessages) Game.chat(message[0], message[1]);
                waitingMessages.clear();
                joins.forEach(Action::run);
            }
        }
        for (KeyAction key : keys) {
            while (key.key().pressed()) key.action().run(); // each press counts once
        }
        if (now == null) {
            lastHealth = -1;
            return;
        }
        if (Game.paused()) return;
        // Getting hurt and dying, noticed from your health going down
        ticksInWorld++;
        if (!hurts.isEmpty() || !deaths.isEmpty()) {
            double health = Game.health();
            // The first two seconds don't count: joining starts at full health until the server says the real one
            if (ticksInWorld > 40 && lastHealth > 0 && health < lastHealth) {
                hurts.forEach(Action::run);
                if (health <= 0) deaths.forEach(Action::run);
            }
            lastHealth = health;
        }
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
