package squid;

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
    private static Report report;

    private Main() {
    }

    /** Every mod that was loaded. */
    public static List<ModInfo> mods() {
        return mods;
    }

    public static void main(String[] args) throws Throwable {
        String mainClass = System.getProperty("squid.mainClass", "net.minecraft.client.main.Main");
        String gameClasspath = System.getProperty("squid.gameClasspath");
        if (gameClasspath == null) throw new IllegalStateException("Squid needs -Dsquid.gameClasspath from the launcher");

        registerBuiltInHooks();

        Path gameFolder = gameFolder(args);
        report = new Report(gameFolder);
        report.loading();
        ModInfo starting = null; // the mod being started right now, to blame if something breaks
        SquidClassLoader loader;
        try {
            Path modsFolder = gameFolder.resolve("mods");
            Mods.Found found = Mods.find(modsFolder, argument(args, "--version"));
            mods = found.mods();
            report.skipped(found.skipped());
            System.out.println("[Squid] Squid " + VERSION + " found " + mods.size() + " mod(s) in " + modsFolder);

            List<URL> urls = new ArrayList<>();
            for (String entry : gameClasspath.split(File.pathSeparator)) urls.add(Path.of(entry).toUri().toURL());
            for (ModInfo mod : mods) urls.add(mod.jar().toUri().toURL());
            loader = new SquidClassLoader(urls.toArray(URL[]::new));
            Thread.currentThread().setContextClassLoader(loader);

            for (ModInfo mod : mods) {
                starting = mod;
                System.out.println("[Squid] Starting " + mod.name() + " " + mod.version());
                Object instance = loader.loadClass(mod.main()).getDeclaredConstructor().newInstance();
                if (!(instance instanceof SquidMod squidMod)) {
                    throw new IllegalStateException(mod.main() + " (from " + mod.id() + ") doesn't implement SquidMod");
                }
                squidMod.init(new Squid(mod));
            }
            starting = null;
        } catch (Throwable problem) {
            if (problem instanceof InvocationTargetException wrapped) problem = wrapped.getCause(); // the mod's own error
            report.failed(mods, starting == null ? null : starting.name(), describe(problem));
            throw problem;
        }
        report.running(mods);

        Method main = loader.loadClass(mainClass).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause(); // show Minecraft's own error, not the reflection wrapper
        }
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
