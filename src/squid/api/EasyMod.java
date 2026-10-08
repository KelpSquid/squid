package squid.api;

import squid.Events;
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
    private final List<Later> laters = new ArrayList<>();
    private final List<java.util.function.IntConsumer> levelUps = new ArrayList<>();
    private final List<Action> nights = new ArrayList<>();
    private final List<Action> days = new ArrayList<>();
    private int lastLevel = -1;     // the experience level last tick (-1: not known yet)
    private int lastNight = -1;     // 1 night, 0 day, -1 not known yet
    private int nightCandidate = -1; // what the clock says now, waiting to have held for a few seconds
    private int nightHeld;          // how many ticks it has
    private Object lastPlayer;      // a new player object (after respawning) starts its level from scratch
    private final List<KeyAction> keys = new ArrayList<>();
    private final List<String[]> waitingMessages = new ArrayList<>(); // text and color, said before joining a world
    private Object world; // the world the player was in last tick, to notice joining and leaving
    private long worldTicks; // ticks spent in a world, not counting pauses

    private record Timer(int everyTicks, Action action) {
    }

    private record KeyAction(KeyBinding key, Action action) {
    }

    /** Something to run once, at a tick in the world (see after()). */
    private record Later(long dueTick, Action action) {
    }

    /** The frame keepShowing() lines are being drawn on, and how many lines are on it so far (from every mod). */
    private static Object cornerFrame;
    private static int cornerLines;

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
        String[] words = {""}; // what was typed after the command, handed to the action
        Action run = new Action("onCommand(\"" + name + "\")", () -> action.accept(words[0]));
        squid.onChatCommand(name, typed -> {
            words[0] = typed;
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
        squid.onChat(message -> {
            text[0] = message;
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
        squid.onBreak(name -> {
            block[0] = name;
            run.run();
        });
    }

    /** Runs when you pick up an item, with its name: onPickup(item -> { if (item.equals("diamond")) say("Shiny!"); }). */
    protected void onPickup(java.util.function.Consumer<String> action) {
        onPickup((item, amount) -> action.accept(item));
    }

    /**
     * Runs when you pick up items, with the item's name and how many: onPickup((item, amount) -> { if
     * (item.equals("diamond")) diamonds = diamonds + amount; }).
     */
    protected void onPickup(java.util.function.BiConsumer<String, Integer> action) {
        if (!starting) throw new IllegalStateException(Lang.t("onPickup only works inside start()"));
        Object[] picked = {"", 0};
        Action run = new Action("onPickup", () -> action.accept((String) picked[0], (Integer) picked[1]));
        squid.onPickup((item, amount) -> {
            picked[0] = item;
            picked[1] = amount;
            run.run();
        });
    }

    /** Runs when you hit a mob (or a player), with what it is, like onAttack(mob -> { if (mob.equals("zombie")) ... }). */
    protected void onAttack(java.util.function.Consumer<String> action) {
        if (!starting) throw new IllegalStateException(Lang.t("onAttack only works inside start()"));
        String[] mob = {""};
        Action run = new Action("onAttack", () -> action.accept(mob[0]));
        squid.onAttack(name -> {
            mob[0] = name;
            run.run();
        });
    }

    /**
     * Text that stays in the top-left corner while you play, kept up to date: keepShowing(() -> "Diamonds: " +
     * diamonds). Give back "" to hide it for now. Lines from different mods go under each other.
     */
    protected void keepShowing(java.util.function.Supplier<Object> text) {
        if (!starting) throw new IllegalStateException(Lang.t("keepShowing only works inside start()"));
        String[] shown = {""};
        Action read = new Action("keepShowing", () -> shown[0] = String.valueOf(text.get()));
        squid.onHud(hud -> {
            if (hud != cornerFrame) { // a new frame: the lines start at the top again
                cornerFrame = hud;
                cornerLines = 0;
            }
            if (!Game.inWorld()) return;
            shown[0] = "";
            read.run();
            if (shown[0].isEmpty() || shown[0].equals("null")) return;
            int y = 4 + cornerLines++ * 12;
            hud.box(2, y - 2, hud.textWidth(shown[0]) + 4, 12, 0x80000000);
            hud.text(shown[0], 4, y, 0xFFFFFFFF);
        });
    }

    /**
     * Outlines every mob of a kind, like glow("creeper"), so you can see them through walls. stopGlowing("creeper")
     * turns it off again. Only you see the outlines.
     */
    protected void glow(String mob) {
        String full = mob.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        String kind = full.substring(full.indexOf(':') + 1); // mobs are matched by their short name, like "creeper"
        if (kind.equals("player")) throw new IllegalArgumentException(Lang.t("glow can't outline players, only mobs"));
        if (Game.inWorld() && !Game.isMob(full)) throw Game.noSuchMob(mob); // a typo says so instead of doing nothing
        Events.glow(squid.mod().id(), kind, true);
    }

    /** Stops outlining a kind of mob (see glow). */
    protected void stopGlowing(String mob) {
        String full = mob.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        Events.glow(squid.mod().id(), full.substring(full.indexOf(':') + 1), false);
    }

    /** Runs when your experience level goes up, with the new level: onLevelUp(level -> title("Level " + level)). */
    protected void onLevelUp(java.util.function.IntConsumer action) {
        if (!starting) throw new IllegalStateException(Lang.t("onLevelUp only works inside start()"));
        int[] level = {0};
        Action run = new Action("onLevelUp", () -> action.accept(level[0]));
        levelUps.add(value -> {
            level[0] = value;
            run.run();
        });
    }

    /** Runs when night falls (by the clock: a daytime storm doesn't count). Only in the Overworld. */
    protected void onNight(Runnable action) {
        if (!starting) throw new IllegalStateException(Lang.t("onNight only works inside start()"));
        nights.add(new Action("onNight", action));
    }

    /** Runs when the day starts again. */
    protected void onDay(Runnable action) {
        if (!starting) throw new IllegalStateException(Lang.t("onDay only works inside start()"));
        days.add(new Action("onDay", action));
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

    /**
     * Runs once, so many seconds from now (counting time in a world, not paused): after(3, () -> say("Boom!")).
     * Works anywhere, like inside onKey, so a key can start a countdown.
     */
    protected void after(double seconds, Runnable action) {
        long ticks = Math.max(1, Math.round(seconds * 20));
        laters.add(new Later(worldTicks + ticks, new Action("after(" + seconds + ")", action)));
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

    /** Which world you're in: a single player world's name, or a server's address. */
    protected String worldName() {
        return Game.inWorld() ? Game.worldName() : "";
    }

    /** The dimension you're in: "overworld", "the_nether" or "the_end". */
    protected String dimension() {
        return Game.inWorld() ? Game.dimension() : "";
    }

    /** Your experience level, the green number above the hotbar. */
    protected int level() {
        return Game.inWorld() ? Game.xpLevel() : 0;
    }

    /** Whether it's raining (or snowing) in the world right now. */
    protected boolean isRaining() {
        return Game.inWorld() && Game.raining();
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

    // ---- Talking to the server, and to other mods ----

    /**
     * Sends something to this mod's copy on the server: send("score", 10). Text, numbers, true/false, or lists of
     * those. In single player it always works; on a server, the server needs Squid and this mod too.
     */
    protected boolean send(String channel, Object data) {
        return squid.send(channel, data);
    }

    /**
     * Runs when the other copy of this mod sends something: onMessage("score", (from, data) -&gt; say(from + " scored "
     * + data)). from is who sent it: "server", or a player's name when this copy is the server's.
     */
    protected void onMessage(String channel, java.util.function.BiConsumer<Sender, Object> action) {
        if (!starting) throw new IllegalStateException(Lang.t("onMessage only works inside start()"));
        Object[] got = {null, null};
        Action run = new Action("onMessage(\"" + channel + "\")", () -> action.accept((Sender) got[0], got[1]));
        squid.onMessage(channel, (from, data) -> {
            got[0] = from;
            got[1] = data;
            run.run();
        });
    }

    /** Server side: sends something to one player's copy of this mod, by their name: sendTo("Steve", "score", 10). */
    protected boolean sendTo(Object player, String channel, Object data) {
        return squid.sendTo(player, channel, data);
    }

    /** Server side: sends something to every player's copy of this mod: sendToAll("start", "Go!"). */
    protected int sendToAll(String channel, Object data) {
        return squid.sendToAll(channel, data);
    }

    /** Tells every other mod something happened, by name: signal("treasure-found"). */
    protected int signal(String name) {
        return squid.emit(name, null);
    }

    /** Tells every other mod something happened, with a value: signal("treasure-found", 5). */
    protected int signal(String name, Object value) {
        return squid.emit(name, value);
    }

    /** Runs when any mod signals this: onSignal("treasure-found", value -&gt; say("Treasure! " + value)). */
    protected void onSignal(String name, java.util.function.Consumer<Object> action) {
        if (!starting) throw new IllegalStateException(Lang.t("onSignal only works inside start()"));
        Object[] got = {null};
        Action run = new Action("onSignal(\"" + name + "\")", () -> action.accept(got[0]));
        squid.on(name, value -> {
            got[0] = value;
            run.run();
        });
    }

    /** Whether another mod is running, by its id: if (hasMod("minimap")) ... */
    protected boolean hasMod(String id) {
        return squid.hasMod(id);
    }

    /** Shares a value with other mods by name: share("diamonds", diamonds). They get it with shared(). */
    protected void share(String name, Object value) {
        squid.share(name, value);
    }

    /** A value another mod shared, or startingValue if none did: int theirs = shared("diamonds", 0); */
    protected <T> T shared(String name, T startingValue) {
        return squid.shared(name, startingValue);
    }

    // ---- Drawing in the world ----

    /** Something drawn in the world until it's taken away (see markBlock, waypoint, floatingText, drawLine). */
    private record Mark(String kind, String name, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
    }

    private final List<Mark> marks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean drawingMarks;

    private void addMark(Mark mark) {
        if (!drawingMarks) {
            drawingMarks = true;
            squid.onWorldDraw(draw -> {
                for (Mark m : marks) {
                    switch (m.kind()) {
                        case "block" -> draw.throughWalls(true).block((int) m.x1(), (int) m.y1(), (int) m.z1(), m.color());
                        case "waypoint" -> draw.waypoint(m.name(), (int) m.x1(), (int) m.y1(), (int) m.z1(), m.color());
                        case "text" -> draw.throughWalls(false).text(m.name(), m.x1(), m.y1(), m.z1(), m.color());
                        default -> draw.throughWalls(false).line(m.x1(), m.y1(), m.z1(), m.x2(), m.y2(), m.z2(), m.color());
                    }
                }
            });
        }
        marks.add(mark);
    }

    /**
     * Outlines a block in a color, so you can find it again (even through walls): markBlock(x(), y() - 1, z(), "gold").
     * Colors are names like "red", "lime" or "light_blue", or numbers like 0xFF8800. It stays until unmarkBlock or
     * clearMarks.
     */
    protected void markBlock(int x, int y, int z, Object color) {
        addMark(new Mark("block", "", x, y, z, 0, 0, 0, Colors.of(color)));
    }

    /** Takes the outline off a block marked with markBlock. */
    protected void unmarkBlock(int x, int y, int z) {
        marks.removeIf(m -> m.kind().equals("block") && m.x1() == x && m.y1() == y && m.z1() == z);
    }

    /** A gold waypoint: a beam of light and its name with how far away it is, seen from anywhere: waypoint("Home", 0, 64, 0). */
    protected void waypoint(String name, int x, int y, int z) {
        waypoint(name, x, y, z, "gold");
    }

    /** A waypoint in a color: waypoint("Base", x(), y(), z(), "aqua"). A new one with the same name moves it. */
    protected void waypoint(String name, int x, int y, int z, Object color) {
        int argb = Colors.of(color);
        removeWaypoint(name);
        addMark(new Mark("waypoint", String.valueOf(name), x, y, z, 0, 0, 0, argb));
    }

    /** Takes a waypoint away, by its name. */
    protected void removeWaypoint(String name) {
        marks.removeIf(m -> m.kind().equals("waypoint") && m.name().equals(String.valueOf(name)));
    }

    /** White text floating in the world: floatingText("Treasure here!", x(), y() + 2, z()). */
    protected void floatingText(Object text, double x, double y, double z) {
        floatingText(text, x, y, z, "white");
    }

    /** Floating text in a color. */
    protected void floatingText(Object text, double x, double y, double z, Object color) {
        addMark(new Mark("text", String.valueOf(text), x, y, z, 0, 0, 0, Colors.of(color)));
    }

    /** A line in the world from one spot to another: drawLine(0, 64, 0, 10, 64, 10, "red"). */
    protected void drawLine(double x1, double y1, double z1, double x2, double y2, double z2, Object color) {
        addMark(new Mark("line", "", x1, y1, z1, x2, y2, z2, Colors.of(color)));
    }

    /** Takes away everything this mod marked: blocks, waypoints, floating text and lines. */
    protected void clearMarks() {
        marks.clear();
    }

    /**
     * Draws in the world every frame, kept up to date like keepShowing: keepDrawing(draw -&gt; draw.block(x(), y() - 1,
     * z(), "lime")). draw can do block, filledBlock, box, line, text and waypoint.
     */
    protected void keepDrawing(java.util.function.Consumer<WorldDraw> drawing) {
        if (!starting) throw new IllegalStateException(Lang.t("keepDrawing only works inside start()"));
        WorldDraw[] now = {null};
        Action run = new Action("keepDrawing", () -> drawing.accept(now[0]));
        squid.onWorldDraw(draw -> {
            now[0] = draw;
            run.run();
        });
    }

    // ---- Screens ----

    /**
     * A screen of your own: screen("My Menu").button("Day", () -&gt; command("time set day")).toggle("Fly", false, on
     * -&gt; say("Fly: " + on)).open(). It can have buttons, toggles, sliders, textBoxes and labels.
     */
    protected ModScreen screen(String title) {
        return squid.screen(title);
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
            lastLevel = -1; // a new world: what it starts with isn't a change
            lastNight = -1;
            nightCandidate = -1;
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
        // Levelling up and night falling, noticed from how they were last tick (after the first two seconds,
        // while the server says the real values)
        Object player = Game.player();
        if (player != lastPlayer) { // respawned: the new player's level arrives from the server a moment later
            lastPlayer = player;
            lastLevel = -1;
        }
        if (!levelUps.isEmpty() && ticksInWorld > 40) {
            int level = Game.xpLevel();
            if (lastLevel >= 0 && level > lastLevel) for (java.util.function.IntConsumer up : levelUps) up.accept(level);
            lastLevel = level;
        }
        if ((!nights.isEmpty() || !days.isEmpty()) && ticksInWorld > 40) {
            Boolean clock = Game.nightTime();
            int night = clock == null ? -1 : clock ? 1 : 0;
            // It has to stay that way for 5 seconds first, so a server setting the time back a little doesn't
            // make it night, day and night again
            if (night != nightCandidate) {
                nightCandidate = night;
                nightHeld = 0;
            } else if (nightHeld < 100 && ++nightHeld == 100 && night >= 0) {
                if (lastNight == 0 && night == 1) nights.forEach(Action::run);
                if (lastNight == 1 && night == 0) days.forEach(Action::run);
                lastNight = night;
            }
        }
        ticks.forEach(Action::run);
        for (Timer timer : timers) {
            if (worldTicks % timer.everyTicks() == 0) timer.action().run();
        }
        if (!laters.isEmpty()) {
            // Taken out first, so an after() inside one (a countdown) is kept for later instead of changing the list
            List<Later> due = new ArrayList<>();
            laters.removeIf(later -> later.dueTick() <= worldTicks && due.add(later));
            for (Later later : due) later.action().run();
        }
    }

    /** Tells the player in red that something in this mod went wrong. */
    private void problem(String message) {
        String text = "[" + squid.mod().name() + "] " + message;
        if (Game.inWorld()) Game.chat(text, "RED");
        else waitingMessages.add(new String[] {text, "RED"});
    }
}
