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

        Path modsFolder = gameFolder(args).resolve("mods");
        mods = Mods.find(modsFolder);
        System.out.println("[Squid] Squid " + VERSION + " found " + mods.size() + " mod(s) in " + modsFolder);

        List<URL> urls = new ArrayList<>();
        for (String entry : gameClasspath.split(File.pathSeparator)) urls.add(Path.of(entry).toUri().toURL());
        for (ModInfo mod : mods) urls.add(mod.jar().toUri().toURL());
        SquidClassLoader loader = new SquidClassLoader(urls.toArray(URL[]::new));
        Thread.currentThread().setContextClassLoader(loader);

        for (ModInfo mod : mods) {
            System.out.println("[Squid] Starting " + mod.name() + " " + mod.version());
            Object instance = loader.loadClass(mod.main()).getDeclaredConstructor().newInstance();
            if (!(instance instanceof SquidMod squidMod)) {
                throw new IllegalStateException(mod.main() + " (from " + mod.id() + ") doesn't implement SquidMod");
            }
            squidMod.init(new Squid(mod));
        }

        Method main = loader.loadClass(mainClass).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause(); // show Minecraft's own error, not the reflection wrapper
        }
    }

    /** Squid's own hooks, set up before any mod's. */
    static void registerBuiltInHooks() {
        // Tell Minecraft it's modded, so the F3 screen and crash reports say "squid" instead of "vanilla"
        Transformers.add("net.minecraft.client.ClientBrandRetriever", new Transformers.HookPatch(
                "getClientModName", null, false, Hooks.register("squid", call -> call.setReturnValue("squid"))));
    }

    /** The --gameDir Minecraft was given, or the current folder. */
    private static Path gameFolder(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--gameDir")) return Path.of(args[i + 1]);
        }
        return Path.of(".");
    }
}
