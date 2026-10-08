package squid.api;

import org.objectweb.asm.tree.ClassNode;
import squid.Hooks;
import squid.KeyBindings;
import squid.Lang;
import squid.Slots;
import squid.Transformers;

import java.util.List;
import java.util.function.Consumer;

/**
 * What a mod gets in {@link SquidMod#init}: the way to hook into Minecraft.
 * Class names are written the normal way, like "net.minecraft.client.gui.screens.TitleScreen".
 * Set hooks up before touching any Minecraft class, or that class will already be loaded without them.
 */
public final class Squid {
    private final ModInfo mod;

    public Squid(ModInfo mod) {
        this.mod = mod;
    }

    /** This mod's settings, which players change in Squid's Mods screen. See {@link ModSettings}. */
    public ModSettings settings() {
        return ModSettings.of(mod.id());
    }

    /**
     * Adds a button to the Squid menu (the one Squid button on Minecraft's title screen and pause menu), so a mod's
     * own screen is easy to find. label is in English (Squid translates it if it knows it). open gets the Squid menu
     * screen, to come back to. inWorldOnly hides it on the title screen, for things that need a world.
     */
    public void addMenuButton(String label, boolean inWorldOnly, Consumer<Object> open) {
        squid.MenuButtons.add(new squid.MenuButtons.Entry(mod.id(), label, inWorldOnly, open));
    }

    /** This mod's details from its squid.json. */
    public ModInfo mod() {
        return mod;
    }

    /** Every mod Squid loaded. */
    public List<ModInfo> mods() {
        return squid.Main.mods();
    }

    /** Runs the hook at the start of every method with this name. It can cancel the method. */
    public void atStart(String className, String methodName, Hook hook) {
        atStart(className, methodName, null, hook);
    }

    /**
     * Like {@link #atStart(String, String, Hook)} but only for the method with this descriptor,
     * like "(I)V" for a method that takes an int and returns nothing. Constructors can't have start hooks.
     */
    public void atStart(String className, String methodName, String descriptor, Hook hook) {
        hook(className, methodName, descriptor, true, hook);
    }

    /** Runs the hook every time a method with this name returns. It can change what gets returned. */
    public void atEnd(String className, String methodName, Hook hook) {
        atEnd(className, methodName, null, hook);
    }

    /** Like {@link #atEnd(String, String, Hook)} but only for the method with this descriptor. */
    public void atEnd(String className, String methodName, String descriptor, Hook hook) {
        hook(className, methodName, descriptor, false, hook);
    }

    /**
     * Sets a hook up. A mod reloaded while the game runs takes over its old hook in the same place, since Minecraft's
     * classes can't be patched again once they've loaded.
     */
    private void hook(String className, String methodName, String descriptor, boolean atStart, Hook hook) {
        int reused = Slots.reuse(mod.id(), className, methodName, descriptor, atStart);
        if (reused >= 0) {
            Hooks.replace(reused, mod.id(), hook);
            return;
        }
        if (Transformers.isLoaded(className)) {
            Slots.needsRestart(mod.id(), className);
            if (!squid.Main.gameStarted()) {
                // While Squid starts, nothing should have loaded it yet: something used it too early, and this hook
                // won't be in it. Said in the log, so it's found.
                System.out.println("[Squid] Warning: " + mod.id() + " hooks " + className + "." + methodName
                        + ", but that class had already loaded, so the hook can't go in. Something loaded it too early.");
            }
        }
        int id = Hooks.register(mod.id(), hook);
        Transformers.add(className, new Transformers.HookPatch(methodName, descriptor, atStart, id));
        Slots.record(mod.id(), className, methodName, descriptor, atStart, id);
    }

    /**
     * Wraps a method: the hook runs instead of it, and decides when (and whether, and how often) the original runs,
     * with which arguments, and what it gives back. Like this, to double every mob's health:
     *
     * <pre>
     * squid.around("net.minecraft.world.entity.LivingEntity", "getMaxHealth", (call, original) -&gt;
     *         (float) original.call() * 2);
     * </pre>
     *
     * If the hook breaks, the original runs instead, and after 3 breaks the hook is switched off. Constructors can't
     * be wrapped. If two mods wrap the same method, the one set up first runs outermost.
     */
    public void around(String className, String methodName, Around hook) {
        around(className, methodName, null, hook);
    }

    /** Like {@link #around(String, String, Around)} but only for the method with this descriptor, like "(I)V". */
    public void around(String className, String methodName, String descriptor, Around hook) {
        wrap(className, methodName, descriptor, "around", hook, id -> new Transformers.AroundPatch(methodName, descriptor, id));
    }

    /**
     * Wraps one call made inside a method: every time methodName (in className) calls calledMethod (of calledClass,
     * or a class that comes from it), the hook runs instead. {@link Call#self()} is the call's target (null for
     * static methods), {@link Call#args()} its arguments, and {@link Call#caller()} the object making the call. The
     * hook can change them and run the call ({@code original.call()}), skip it (give back a value without running
     * it), or change what it gives back:
     *
     * <pre>
     * // Fall damage is worked out from how far you fell: make every fall count half
     * squid.atCall("net.minecraft.world.entity.LivingEntity", "causeFallDamage",
     *         "net.minecraft.world.entity.LivingEntity", "calculateFallDamage",
     *         (call, original) -&gt; (int) original.call() / 2);
     * </pre>
     *
     * calledMethod can include a descriptor to pick one of a few methods with that name, like
     * "getValue(Ljava/lang/Object;)Ljava/lang/Object;". calledClass can be null for a call to any class.
     */
    public void atCall(String className, String methodName, String calledClass, String calledMethod, Around hook) {
        atCall(className, methodName, null, calledClass, calledMethod, hook);
    }

    /** Like {@link #atCall(String, String, String, String, Around)} but only inside the method with this descriptor. */
    public void atCall(String className, String methodName, String descriptor, String calledClass, String calledMethod, Around hook) {
        if (calledMethod == null || calledMethod.isBlank()) throw new IllegalArgumentException(Lang.t("atCall needs the name of the method that's called"));
        wrap(className, methodName, descriptor, "call " + calledClass + " " + calledMethod, hook,
                id -> new Transformers.CallPatch(methodName, descriptor, calledClass, calledMethod, id));
    }

    /** Sets an around or atCall hook up, the same way {@link #hook} does start and end hooks (reloads take over the old place). */
    private void wrap(String className, String methodName, String descriptor, String kind, Around hook,
                      java.util.function.IntFunction<Transformers.Patch> patch) {
        if (hook == null) throw new IllegalArgumentException(Lang.t("the hook is missing (null)"));
        int reused = Slots.reuse(mod.id(), className, methodName, descriptor, kind);
        if (reused >= 0) {
            Hooks.replace(reused, mod.id(), hook);
            return;
        }
        if (Transformers.isLoaded(className)) {
            Slots.needsRestart(mod.id(), className);
            if (!squid.Main.gameStarted()) {
                System.out.println("[Squid] Warning: " + mod.id() + " wraps " + className + "." + methodName
                        + ", but that class had already loaded, so the hook can't go in. Something loaded it too early.");
            }
        }
        int id = Hooks.register(mod.id(), hook);
        Transformers.add(className, patch.apply(id));
        Slots.record(mod.id(), className, methodName, descriptor, kind, id);
    }

    /**
     * Adds a key to Minecraft's Controls screen, in the Miscellaneous group, where players can change it.
     * defaultKey is one of Minecraft's key codes, like InputConstants.KEY_Z. Check it with {@link KeyBinding#isDown()}.
     */
    public KeyBinding addKeyBinding(String name, int defaultKey) {
        KeyBinding known = KeyBindings.find(name); // a reloaded mod keeps its keys, and the player's choices for them
        if (known != null) return known;
        KeyBinding binding = new KeyBinding(name, defaultKey);
        KeyBindings.add(binding);
        return binding;
    }

    /**
     * Runs every frame while the game's HUD is showing, so the mod can draw on it with the {@link Hud}.
     * It doesn't run while the HUD is hidden with F1.
     */
    public void onHud(Consumer<Hud> draw) {
        squid.Events.onHud(mod.id(), draw);
    }

    /**
     * Runs every frame while you're in a world, to draw in it with the {@link WorldDraw}: outlines around blocks,
     * boxes, lines, floating text and waypoints. Unlike {@link #onHud}, these sit in the world and you can walk around
     * them (they still show with the HUD hidden).
     */
    public void onWorldDraw(Consumer<WorldDraw> draw) {
        squid.Events.onWorldDraw(mod.id(), draw);
    }

    /**
     * Runs right as a cue in a playing .sqda sound is heard: its beats, bars, sections, named cues and light cues.
     * Good for lights or effects that follow the music.
     */
    public void onSoundCue(Consumer<SoundCue> cue) {
        squid.Events.onSoundCue(mod.id(), cue);
    }

    /** Runs when the player breaks a block, with its name, like "stone" or "diamond_ore". */
    public void onBreak(Consumer<String> block) {
        squid.Events.on("break", mod.id(), value -> block.accept((String) value));
    }

    /** Runs when the player hits a mob (or another player), with what it is, like "zombie". */
    public void onAttack(Consumer<String> mob) {
        squid.Events.on("attack", mod.id(), value -> mob.accept((String) value));
    }

    /** Runs when the player picks up items, with the item's name (like "diamond") and how many. */
    public void onPickup(java.util.function.BiConsumer<String, Integer> item) {
        squid.Events.on("pickup", mod.id(), value -> {
            Object[] picked = (Object[]) value;
            item.accept((String) picked[0], (Integer) picked[1]);
        });
    }

    /**
     * Runs for every chat message the player sees, as plain text: "<Steve> hi", "Alex joined the game". Messages mods
     * show with {@link Game#chat} aren't included, so a mod can answer without hearing itself.
     */
    public void onChat(Consumer<String> message) {
        squid.Events.on("chat", mod.id(), value -> message.accept((String) value));
    }

    /**
     * A chat command: typing "!name" in the chat runs it (with the words typed after it) and the message isn't sent.
     * If two mods have the same command, both run.
     */
    public void onChatCommand(String name, Consumer<String> words) {
        squid.Events.onCommand(mod.id(), name, words);
    }

    /** Runs 20 times a second, all the time the game is open (in menus too). */
    public void onTick(Runnable tick) {
        squid.Events.onTick(mod.id(), tick);
    }

    /**
     * A screen of this mod's own, with this title: add buttons, switches, sliders, text boxes and labels to it, then
     * {@link ModScreen#open()} it. No need to know Minecraft's screen classes.
     */
    public ModScreen screen(String title) {
        return new ModScreen(mod.id(), title);
    }

    // ---- Messages between the game and a Squid server ----

    /**
     * Sends something to the copy of this mod on the server: text, a number, true/false, or a list or map of those,
     * up to 30,000 bytes. The server's copy gets it in {@link #onMessage}. Gives back false if it couldn't go (the
     * game isn't on a server with Squid, or the mod sent more than about 40 a second, which is held back so no mod can
     * flood a server). In single player your world's server is right there in your game, so it works there too.
     */
    public boolean send(String channel, Object data) {
        return squid.ModNet.send(mod.id(), channel, data);
    }

    /**
     * Runs when the other copy of this mod sends something on a channel: from the server (in the game) or from a
     * player (on the server). {@code from} says who, and can {@link Sender#reply} straight back:
     *
     * <pre>
     * squid.onMessage("ping", (from, data) -&gt; from.reply("pong", "Hi " + from.name()));
     * </pre>
     *
     * It runs on the game's (or the server's) own thread, so it can use Minecraft like a hook does.
     */
    public void onMessage(String channel, java.util.function.BiConsumer<Sender, Object> handler) {
        squid.ModNet.on(mod.id(), channel, handler);
    }

    /**
     * On a server: sends something to the copy of this mod in one player's game. player is their name, their id
     * (a UUID), Minecraft's ServerPlayer, or a {@link Sender}. False if they aren't on, or don't have Squid.
     */
    public boolean sendTo(Object player, String channel, Object data) {
        return squid.ModNet.sendTo(mod.id(), player, channel, data);
    }

    /** On a server: sends something to every player whose game has Squid. Gives back how many it went to. */
    public int sendToAll(String channel, Object data) {
        return squid.ModNet.sendToAll(mod.id(), channel, data);
    }

    // ---- Mods talking to each other ----

    /**
     * Sends an event to every mod listening for it with {@link #on}, with a value (or null). The mods don't need each
     * other's code: they only agree on the event's name, like "treasure-found". Gives back how many listened.
     */
    public int emit(String event, Object value) {
        return squid.ModBus.emit(mod.id(), event, value);
    }

    /** Runs whenever any mod sends this event with {@link #emit}, with its value. */
    public void on(String event, Consumer<Object> listener) {
        squid.ModBus.on(mod.id(), event, listener);
    }

    /**
     * Whether a mod with this id is running, like hasMod("minimap"). While the game starts, mods starting with this
     * one count too, so init can already ask.
     */
    public boolean hasMod(String id) {
        return squid.Main.hasMod(id);
    }

    /**
     * Shares a value under a name, for other mods to get with {@link #shared} (sharing again replaces it). It can be
     * a Function or Supplier for other mods to {@link #ask}. Plain Java things work best: text, numbers, lists, maps.
     */
    public void share(String name, Object value) {
        squid.ModBus.share(mod.id(), name, value);
    }

    /** A function other mods can {@link #ask}: {@code squid.offer("price", item -> 5);} */
    public void offer(String name, java.util.function.Function<Object, Object> answer) {
        squid.ModBus.share(mod.id(), name, answer);
    }

    /** A value some mod shared under this name, or null if none did. */
    @SuppressWarnings("unchecked")
    public <T> T shared(String name) {
        return (T) squid.ModBus.shared(name);
    }

    /** A value some mod shared under this name, or the fallback if none did. */
    @SuppressWarnings("unchecked")
    public <T> T shared(String name, T fallback) {
        Object value = squid.ModBus.shared(name);
        return value == null ? fallback : (T) value;
    }

    /**
     * Asks a function another mod offered (or shared) under this name, with a question. Null if no mod offers it, or
     * if its code breaks.
     */
    @SuppressWarnings("unchecked")
    public <T> T ask(String name, Object question) {
        return (T) squid.ModBus.ask(name, question);
    }

    /** For advanced mods: change a class's bytecode directly with ASM before it loads. */
    public void patch(String className, Consumer<ClassNode> patch) {
        if (Transformers.isLoaded(className)) Slots.needsRestart(mod.id(), className);
        Transformers.add(className, new Transformers.RawPatch(mod.id(), patch));
    }

    /** Prints a line to the game's output, labeled with this mod's id. */
    public void log(String message) {
        System.out.println("[" + mod.id() + "] " + message);
    }
}
