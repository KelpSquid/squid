package squid;

import squid.api.Game;
import squid.api.ModInfo;
import squid.api.Squid;
import squid.api.SquidMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Live reload: while the game runs, Squid watches the mods it builds from code (easy mods, project folders and .squid
 * files). Save one, and Squid builds it again and swaps the new version in, without restarting. A mistake says so in the
 * chat and the old version keeps running. New ones dropped in start right away, and removed ones stop.
 *
 * A reloaded mod takes over its old hooks (see {@link Slots}) and its onTick and onHud run from {@link Events}, so most
 * changes work at once. A change that hooks a part of Minecraft that wasn't hooked before needs a restart, and Squid says so.
 */
final class Reloader {
    private final Path mods;
    private final SourceMods sources;
    private final ClassLoader game;
    private final String minecraftVersion;
    private final Map<Path, String> fingerprints = new HashMap<>();

    Reloader(Path mods, SourceMods sources, ClassLoader game, String minecraftVersion) {
        this.mods = mods;
        this.sources = sources;
        this.game = game;
        this.minecraftVersion = minecraftVersion;
    }

    /** Starts watching the mods folder, once a second, in the background. */
    static void watch(Path mods, SourceMods sources, ClassLoader game, String minecraftVersion) {
        Reloader reloader = new Reloader(mods, sources, game, minecraftVersion);
        reloader.fingerprints.putAll(reloader.scan()); // what's there now is what just loaded
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    reloader.check();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    System.out.println("[Squid] Live reload had a problem: " + e);
                }
            }
        }, "Squid live reload");
        thread.setDaemon(true);
        thread.start();
    }

    /** Looks for mods that changed, appeared or went away since last time, and deals with each. */
    void check() {
        Map<Path, String> now = scan();
        for (Map.Entry<Path, String> entry : now.entrySet()) {
            String before = fingerprints.get(entry.getKey());
            if (!entry.getValue().equals(before)) rebuild(entry.getKey());
        }
        for (Path gone : fingerprints.keySet()) {
            if (!now.containsKey(gone)) unload(gone);
        }
        fingerprints.clear();
        fingerprints.putAll(now);
    }

    /** Every mod built from code in the folder, with a fingerprint of its files (their names, sizes and times). */
    Map<Path, String> scan() {
        Map<Path, String> found = new HashMap<>();
        if (!Files.isDirectory(mods)) return found;
        try (Stream<Path> list = Files.list(mods)) {
            for (Path p : list.toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".java") || name.endsWith(".squid") || Mods.isProject(p)) {
                    found.put(p.toAbsolutePath().normalize(), fingerprint(p));
                }
            }
        } catch (IOException e) {
            // can't look right now: try again next second
        }
        return found;
    }

    private static String fingerprint(Path path) {
        StringBuilder print = new StringBuilder();
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                print.append(path.relativize(p)).append(Files.size(p)).append(Files.getLastModifiedTime(p).toMillis()).append(';');
            }
        } catch (IOException e) {
            return "unreadable";
        }
        return print.toString();
    }

    /** Builds a changed mod again and, if that works, swaps it in on the game's own thread. */
    void rebuild(Path path) {
        String name = path.getFileName().toString();
        ModInfo info;
        try {
            if (Files.isDirectory(path)) info = sources.compileProject(path, Mods.readProject(path));
            else if (name.endsWith(".squid")) info = sources.compilePacked(path, Mods.readPacked(path));
            else info = sources.compile(path);
        } catch (IOException mistake) {
            tell(Lang.t("{0} has a mistake, so its old version keeps running: {1}", name, mistake.getMessage()), "RED");
            return;
        }
        if (!info.worksOn(minecraftVersion)) {
            tell(Lang.t("{0} was made for Minecraft {1}, so Squid didn't load it.", info.name(), String.join(", ", info.minecraft())), "YELLOW");
            return;
        }
        ModInfo ready = info;
        onGameThread(() -> swapIn(ready));
    }

    /** Starts the new version of a mod in place of its old one (or for the first time, for a new mod). */
    void swapIn(ModInfo info) {
        boolean wasRunning = Main.mods().stream().anyMatch(m -> m.id().equals(info.id()));
        Slots.beginReload(info.id());
        Class<?> main = null;
        try {
            main = new ModClassLoader(info.jar(), game).loadClass(info.main());
            Object mod = main.getDeclaredConstructor().newInstance();
            if (!(mod instanceof SquidMod squidMod)) {
                throw new IllegalStateException(Lang.t("it isn't a Squid mod yet. Write \"extends EasyMod\" after its class name"));
            }
            squidMod.init(new Squid(info));
            KeyBindings.addToRunningGame(game); // keys it added are usable straight away
            Set<String> restart = Slots.endReload(info.id());
            Main.updateMod(info);
            tell(wasRunning ? Lang.t("Reloaded {0}!", info.name()) : Lang.t("Started {0}!", info.name()), "GREEN");
            if (!restart.isEmpty()) {
                tell(Lang.t("Part of {0} needs a restart to work: it changes {1}, which is already loaded.", info.name(),
                        String.join(", ", restart)), "YELLOW");
            }
        } catch (Throwable problem) {
            Hooks.turnOff(info.id());
            Events.remove(info.id());
            Slots.endReload(info.id());
            tell(Lang.t("{0} broke while reloading: {1}", info.name(), Mistakes.explain(problem, main)), "RED");
            problem.printStackTrace(System.out);
        }
    }

    /** A mod's file or folder is gone (or turned off): it stops. */
    void unload(Path path) {
        String id = sources.built.remove(path);
        if (id == null) return;
        Hooks.turnOff(id);
        Events.remove(id);
        String name = Main.mods().stream().filter(m -> m.id().equals(id)).map(ModInfo::name).findFirst().orElse(id);
        Main.removeMod(id);
        tell(Lang.t("Turned off {0}.", name), "YELLOW");
    }

    /** Runs it on Minecraft's own thread, where mods normally run, or right here if the game isn't up. */
    private void onGameThread(Runnable task) {
        try {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, game);
            Object instance = minecraft.getMethod("getInstance").invoke(null);
            if (instance instanceof Executor executor) {
                executor.execute(task);
                return;
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            // no game running (like in tests)
        }
        task.run();
    }

    /** A chat message for the player (or the log, before they're in a world). */
    private static void tell(String message, String color) {
        System.out.println("[Squid] " + message);
        try {
            if (Main.gameStarted()) Game.chat(message, color);
        } catch (RuntimeException e) {
            // not in a world yet: the log has it
        }
    }
}
