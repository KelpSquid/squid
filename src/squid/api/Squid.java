package squid.api;

import org.objectweb.asm.tree.ClassNode;
import squid.Hooks;
import squid.KeyBindings;
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
        if (Transformers.isLoaded(className)) Slots.needsRestart(mod.id(), className);
        int id = Hooks.register(mod.id(), hook);
        Transformers.add(className, new Transformers.HookPatch(methodName, descriptor, atStart, id));
        Slots.record(mod.id(), className, methodName, descriptor, atStart, id);
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
