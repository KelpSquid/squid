package squid;

import squid.api.Hud;
import squid.api.ModInfo;
import squid.api.Squid;
import squid.api.SquidMod;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where Squid starts. The launcher runs this instead of Minecraft's own main class:
 * it finds the mods, lets each one set up its hooks, then starts Minecraft through Squid's class loader.
 *
 * The launcher passes two settings:
 *   -Dsquid.gameClasspath=...  Minecraft and its libraries (the normal classpath only holds Squid itself)
 *   -Dsquid.mainClass=...      Minecraft's real main class
 */
public final class Main {
    public static final String VERSION = "0.1";

    private static List<ModInfo> mods = List.of();
    private static List<Mods.Skipped> skipped = List.of();
    private static Report report;
    private static volatile ClassLoader gameLoader;
    private static volatile boolean gameStarted;
    private static String minecraftVersion;
    private static Path gameFolder = Path.of(".");

    private Main() {
    }

    /** Every mod that was loaded. */
    /** A mod reloaded (or added) while the game runs takes its old details' place in the list. */
    static synchronized void updateMod(ModInfo mod) {
        List<ModInfo> updated = new ArrayList<>(mods);
        updated.removeIf(m -> m.id().equals(mod.id()));
        updated.add(mod);
        mods = List.copyOf(updated);
    }

    /** A mod removed while the game runs leaves the list. */
    static synchronized void removeMod(String id) {
        MenuButtons.removeMod(id);
        List<ModInfo> updated = new ArrayList<>(mods);
        updated.removeIf(m -> m.id().equals(id));
        mods = List.copyOf(updated);
    }

    public static List<ModInfo> mods() {
        return mods;
    }

    /** The class loader Minecraft and the mods run in, or null before the game starts. */
    public static ClassLoader gameLoader() {
        return gameLoader;
    }

    /** The game folder: the instance's folder, where its mods, resource packs and worlds are. */
    public static Path gameFolder() {
        return gameFolder;
    }

    /** Safe mode: the game starts with no mods (only Squid's own parts), so a broken mod can't stop it. */
    public static boolean safeMode() {
        return Boolean.getBoolean("squid.safeMode");
    }

    /** Whether Squid is running a Minecraft server (started by {@link ServerLauncher}) rather than the game. */
    public static boolean isServer() {
        return "server".equals(System.getProperty("squid.side"));
    }

    /** The Minecraft version being played, like "26.3", or null if the launcher didn't say. */
    public static String minecraftVersion() {
        return minecraftVersion;
    }

    /** Where the game keeps its files. Only tests set this. */
    static void setGameFolder(Path folder) {
        gameFolder = folder;
    }

    static void setGameLoader(ClassLoader loader) {
        gameLoader = loader;
    }

    /**
     * Whether Minecraft has started. Before that, nothing may touch Minecraft's classes: loading one early
     * would load it before every mod has set up its hooks.
     */
    public static boolean gameStarted() {
        return gameStarted;
    }

    public static void main(String[] args) throws Throwable {
        String mainClass = System.getProperty("squid.mainClass", "net.minecraft.client.main.Main");
        String gameClasspath = System.getProperty("squid.gameClasspath");
        if (gameClasspath == null) throw new IllegalStateException("Squid needs -Dsquid.gameClasspath from the launcher");

        registerBuiltInHooks();

        gameFolder = gameFolder(args);
        minecraftVersion = argument(args, "--version");
        if (minecraftVersion == null) minecraftVersion = System.getProperty("squid.minecraftVersion"); // servers don't pass --version
        report = new Report(gameFolder);
        report.loading();
        ClassLoader loader;
        try {
            Path modsFolder = gameFolder.resolve("mods");
            // .java mods can use Squid (on the normal classpath) and Minecraft
            SourceMods sources = new SourceMods(modsFolder.resolve(".squid-cache"),
                    System.getProperty("java.class.path") + File.pathSeparator + gameClasspath);
            // Safe mode (Kelp's Play Without Mods, after a crash): no mods this time, only Squid's own parts
            Mods.Found found = safeMode() ? new Mods.Found(List.of(), List.of()) : Mods.find(modsFolder, minecraftVersion, sources);
            System.out.println("[Squid] Squid " + VERSION + " found " + found.mods().size() + " mod(s) in " + modsFolder);
            // Squid's own parts, like the Store, come with Squid in its builtin folder. They aren't counted as mods.
            List<ModInfo> builtIn = Mods.find(builtInFolder(), minecraftVersion).mods();
            // Squid's speed test with only some of Squid's other parts (or none), to see what each costs
            String benchParts = Boolean.getBoolean("squid.bench.bare") ? "" : System.getProperty("squid.bench.parts");
            if (benchParts != null) {
                List<String> keep = List.of(("squid-speed," + benchParts).split(","));
                builtIn = builtIn.stream().filter(m -> keep.contains(m.id())).toList();
            }

            if (FastBoot.active()) {
                // Fast boot: Kelp put Minecraft (already patched), the libraries and the mods on Java's own classpath
                loader = Main.class.getClassLoader();
            } else {
                List<URL> urls = new ArrayList<>();
                for (String entry : gameClasspath.split(File.pathSeparator)) urls.add(Path.of(entry).toUri().toURL());
                for (ModInfo mod : builtIn) urls.add(mod.jar().toUri().toURL());
                for (ModInfo mod : found.mods()) urls.add(mod.jar().toUri().toURL());
                loader = new SquidClassLoader(urls.toArray(URL[]::new));
            }
            Thread.currentThread().setContextClassLoader(loader);
            gameLoader = loader;

            List<Mods.Skipped> notStarted = new ArrayList<>(found.skipped());
            start(builtIn, loader, notStarted);
            mods = start(found.mods(), loader, notStarted);
            if (FastBoot.active()) {
                FastBoot.check(); // the pre-patched classes must match the hooks the mods just asked for
                if (FastBoot.training()) FastBoot.train(loader);
            } else if (!isServer() && !safeMode()) {
                FastBoot.prepare(gameFolder, gameClasspath, mainClass, builtIn, found.mods());
            }
            if (!safeMode()) Reloader.watch(modsFolder, sources, loader, minecraftVersion); // saving a mod's code reloads it while playing
            skipped = List.copyOf(notStarted);
            report.skipped(skipped);
        } catch (Throwable problem) {
            report.failed(mods, null, describe(problem));
            throw problem;
        }
        report.running(mods);

        Method main = loader.loadClass(mainClass).getMethod("main", String[].class);
        gameStarted = true;
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause(); // show Minecraft's own error, not the reflection wrapper
        }
    }

    /**
     * Starts each mod. A mod that breaks while starting is switched off and skipped, with the line where it broke,
     * and the game opens without it. Gives back the mods that started.
     */
    static List<ModInfo> start(List<ModInfo> mods, ClassLoader loader, List<Mods.Skipped> skipped) {
        List<ModInfo> started = new ArrayList<>();
        for (ModInfo mod : mods) {
            System.out.println("[Squid] Starting " + mod.name() + " " + mod.version());
            Class<?> main = null;
            try {
                // Mods Squid built from their code get a loader of their own, so a new version can replace them later
                ClassLoader modLoader = java.nio.file.Files.isDirectory(mod.jar()) ? new ModClassLoader(mod.jar(), loader) : loader;
                main = modLoader.loadClass(mod.main());
                Object instance = main.getDeclaredConstructor().newInstance();
                if (!(instance instanceof SquidMod squidMod)) {
                    throw new IllegalStateException(Lang.t("it isn't a Squid mod yet. Write \"extends EasyMod\" after its class name"));
                }
                squidMod.init(new Squid(mod));
                started.add(mod);
            } catch (Throwable problem) {
                Hooks.turnOff(mod.id()); // any hooks it set up before breaking do nothing now
                Events.remove(mod.id());
                String why = Mistakes.explain(problem, main);
                System.out.println("[Squid] Skipping " + mod.name() + ": " + why);
                problem.printStackTrace(System.out);
                skipped.add(new Mods.Skipped(mod.id(), mod.name(), why));
            }
        }
        return started;
    }

    /** Tells Kelp (through the report) that a mod's hook had to be turned off. */
    static void hookProblem(String modId, String error) {
        Report r = report;
        if (r == null) return; // only in tests, where there's no report
        String name = modId;
        for (ModInfo mod : mods) {
            if (mod.id().equals(modId)) name = mod.name();
        }
        r.problem(name, error);
    }

    /** A short explanation of an error for the report. Squid's own messages are already written for people. */
    static String describe(Throwable problem) {
        String message = problem.getMessage();
        if (problem instanceof java.io.IOException && message != null) return message;
        return problem.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    /** Squid's own hooks, set up before any mod's. */
    static void registerBuiltInHooks() {
        // Tell Minecraft it's modded, so the F3 screen and crash reports say "squid" instead of "vanilla"
        Transformers.add("net.minecraft.client.ClientBrandRetriever", new Transformers.HookPatch(
                "getClientModName", null, false, Hooks.register("squid", call -> call.setReturnValue("squid"))));
        // Put mods' keys on Minecraft's Controls screen
        KeyBindings.registerHooks();
        // Run every mod's onTick and onHud from one hook each, so mods reloaded while playing can use them too
        Events.registerHooks();
        // Breaking blocks, hitting mobs, picking things up and chat, for EasyMod's onBreak, onAttack, onPickup, onChat
        // and onCommand, hooked once here so mods made while playing (in the Mod Maker) can use them too
        squid.api.GameEvents.install();
        // Say on the title screen that Squid is on, and which mods it couldn't load
        Transformers.add("net.minecraft.client.gui.screens.TitleScreen", new Transformers.HookPatch(
                "extractRenderState", null, false, Hooks.register("squid", call -> drawTitleNotice(new Hud(call.args()[0])))));
    }

    /** Bottom-left of the title screen, above Minecraft's own version line. */
    static void drawTitleNotice(Hud hud) {
        int y = hud.height() - 20;
        String version = minecraftVersion != null ? " - Minecraft " + minecraftVersion : "";
        hud.text("Squid" + version, 2, y, 0xFF55FFFF);
        if (safeMode()) {
            y -= 10;
            hud.text(fit(hud, Lang.t("Safe mode: your mods are off this time. Play from Kelp again to have them back."), hud.width() - 170), 2, y, 0xFFFFFF55);
        }
        List<Mods.Skipped> problems = skipped;
        int maxWidth = hud.width() - 170; // leave room for Mojang's copyright line on the right
        int shown = Math.min(3, problems.size());
        if (problems.size() > shown) {
            y -= 10;
            hud.text(Lang.t("...and {0} more. Kelp shows them all.", problems.size() - shown), 2, y, 0xFFFFFF55);
        }
        for (int i = shown - 1; i >= 0; i--) {
            y -= 10;
            Mods.Skipped s = problems.get(i);
            hud.text(fit(hud, s.name() + ": " + s.reason(), maxWidth), 2, y, 0xFFFFFF55);
        }
    }

    /** Cuts text down with "..." until it fits. */
    private static String fit(Hud hud, String text, int maxWidth) {
        if (hud.textWidth(text) <= maxWidth) return text;
        while (text.length() > 4 && hud.textWidth(text + "...") > maxWidth) text = text.substring(0, text.length() - 1);
        return text + "...";
    }

    /** The builtin folder next to squid.jar, with Squid's own parts like the Store. */
    static Path builtInFolder() {
        try {
            Path squidJar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return squidJar.resolveSibling("builtin");
        } catch (Exception e) {
            return Path.of("builtin");
        }
    }

    /** The --gameDir Minecraft was given, or the current folder. */
    private static Path gameFolder(String[] args) {
        String folder = argument(args, "--gameDir");
        return Path.of(folder != null ? folder : ".");
    }

    /** What comes after a setting like --version in Minecraft's arguments, or null if it isn't there. */
    static String argument(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return null;
    }
}
