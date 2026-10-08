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
    /** Copies of a mod that didn't start because another copy was running, by the mod's id: they start when it goes. */
    private final Map<Path, String> waiting = new HashMap<>();

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
            if (!now.containsKey(gone)) {
                waiting.remove(gone);
                unload(gone);
            }
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

    /**
     * A project folder's fingerprint only looks at what Squid builds from (squid.json, src and resources), so an
     * editor saving its own files there (like IntelliJ's .idea folder) doesn't restart the mod.
     */
    private static String fingerprint(Path path) {
        if (!Files.isDirectory(path)) return fingerprintOf(path, path);
        return fingerprintOf(path, path.resolve("squid.json")) + fingerprintOf(path, path.resolve("src"))
                + fingerprintOf(path, path.resolve("resources"));
    }

    private static String fingerprintOf(Path base, Path path) {
        if (!Files.exists(path)) return "";
        StringBuilder print = new StringBuilder();
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                print.append(base.relativize(p)).append(Files.size(p)).append(Files.getLastModifiedTime(p).toMillis()).append(';');
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            return "unreadable";
        }
        return print.toString();
    }

    /** Builds a changed mod again and, if that works, swaps it in on the game's own thread. */
    void rebuild(Path path) {
        LiveReload.building(path);
        waiting.remove(path);
        String name = path.getFileName().toString();
        // What this file was before, in case its squid.json now gives it another id
        String before = sources.built.get(path);
        ModInfo info;
        try {
            ModInfo described = Mods.describe(path);
            String taken = Mods.reservedReason(described);
            if (taken != null) {
                tell(path, false, Lang.t("Squid didn't load {0}: {1}", name, taken), "YELLOW");
                return;
            }
            // Another file that's already running this mod: two copies can't run at once
            for (Map.Entry<Path, String> other : sources.built.entrySet()) {
                if (other.getValue().equals(described.id()) && !other.getKey().equals(path) && Files.exists(other.getKey())) {
                    waiting.put(path, described.id());
                    tell(path, false, Lang.t("Squid didn't load {0}: it's another copy of {1}. You can delete {2}.", name,
                            other.getKey().getFileName(), name), "YELLOW");
                    return;
                }
            }
            info = sources.build(path, described);
        } catch (IOException | RuntimeException mistake) {
            // Even an odd problem (not just a mistake in the code) only says so once, and never stops live reload
            tell(path, false, Lang.t("{0} has a mistake, so its old version keeps running: {1}", name, Mods.reason(mistake)), "RED");
            return;
        }
        if (!info.worksOn(minecraftVersion)) {
            tell(path, false, Lang.t("{0} was made for Minecraft {1}, so Squid didn't load it.", info.name(), String.join(", ", info.minecraft())), "YELLOW");
            return;
        }
        // Nothing that matters changed (the same build as the one running), so it keeps running as it is
        boolean same = Main.mods().stream().anyMatch(m -> m.id().equals(info.id()) && m.jar().equals(info.jar()));
        if (same) {
            LiveReload.record(path, true, Lang.t("{0} is running this version already.", info.name()));
            return;
        }
        ModInfo ready = info;
        onGameThread(() -> {
            boolean started = swapIn(ready, path);
            if (before == null || before.equals(ready.id())) return;
            // Its id changed (squid.json says another one now): the mod it used to be stops, unless another file
            // is still that mod. If the new one broke, the old one keeps running, and removing the file stops it.
            if (!started) sources.built.put(path, before);
            else if (!sources.built.containsValue(before)) stop(before);
        });
    }

    /** Starts the new version of a mod in place of its old one (or for the first time, for a new mod). Says whether it started. */
    boolean swapIn(ModInfo info, Path path) {
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
            tell(path, true, wasRunning ? Lang.t("Reloaded {0}!", info.name()) : Lang.t("Started {0}!", info.name()), "GREEN");
            if (!restart.isEmpty()) {
                tell(path, false, Lang.t("Part of {0} needs a restart to work: it changes {1}, which is already loaded.", info.name(),
                        String.join(", ", restart)), "YELLOW");
            }
            return true;
        } catch (Throwable problem) {
            Hooks.turnOff(info.id());
            Events.remove(info.id());
            Slots.endReload(info.id());
            Main.removeMod(info.id()); // it isn't running now, so saving the old code again starts it again
            tell(path, false, Lang.t("{0} broke while reloading: {1}", info.name(), Mistakes.explain(problem, main)), "RED");
            problem.printStackTrace(System.out);
            return false;
        }
    }

    /** A mod's file or folder is gone (or turned off): it stops. */
    void unload(Path path) {
        String id = sources.built.remove(path);
        if (id == null) return;
        // Renamed (X.squid became X-v2.squid) or updated under a new name: the new file runs this mod now
        if (sources.built.containsValue(id)) return;
        // A copy that was waiting for this one to go (like a newer download added before the old one was turned
        // off) takes over now, instead of the mod stopping until the game restarts
        for (Map.Entry<Path, String> copy : Map.copyOf(waiting).entrySet()) {
            if (copy.getValue().equals(id) && Files.exists(copy.getKey())) {
                rebuild(copy.getKey());
                if (sources.built.containsValue(id)) return;
            }
        }
        stop(id);
    }

    /** Stops a mod: its hooks, its events, and its place in the running list. */
    private void stop(String id) {
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

    /** A chat message about a mod's file, kept for the Mod Maker too. */
    private static void tell(Path path, boolean worked, String message, String color) {
        LiveReload.record(path, worked, message);
        tell(message, color);
    }

    /** A chat message for the player (or the log, before they're in a world). */
    private static void tell(String message, String color) {
        System.out.println("[Squid] " + message);
        if (!Main.gameStarted()) return;
        // The chat is only touched from the game's own thread (this one is live reload's watcher)
        Runnable say = () -> {
            try {
                Game.chat(message, color);
            } catch (RuntimeException e) {
                // not in a world yet: the log has it
            }
        };
        try {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, Main.gameLoader());
            if (minecraft.getMethod("getInstance").invoke(null) instanceof Executor executor) {
                executor.execute(say);
                return;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            // no game running (like in tests)
        }
        say.run();
    }
}
