package squid;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What live reload last did with each mod's file: started it, reloaded it, or found a mistake. Squid's Mod Maker (the
 * in-game code editor) shows it under the code right after Save.
 */
public final class LiveReload {
    /**
     * One result: whether it worked, what was said in the chat, and the file's time when it was built (so the Mod
     * Maker knows the result is about its latest save, not one before it).
     */
    public record Result(boolean worked, String message, long fileTime) {
    }

    private static final Map<Path, Result> LAST = new ConcurrentHashMap<>();
    private static final Map<Path, Long> BUILDING = new ConcurrentHashMap<>();

    private LiveReload() {
    }

    /** A build of this file is starting: its time now is the version the result will be about. */
    static void building(Path file) {
        try {
            BUILDING.put(file.toAbsolutePath().normalize(), Files.getLastModifiedTime(file).toMillis());
        } catch (IOException e) {
            BUILDING.remove(file.toAbsolutePath().normalize());
        }
    }

    static void record(Path file, boolean worked, String message) {
        Path key = file.toAbsolutePath().normalize();
        LAST.put(key, new Result(worked, message, BUILDING.getOrDefault(key, 0L)));
    }

    /** What happened the last time this file was built while the game was running, or null if it hasn't been yet. */
    public static Result last(Path file) {
        return LAST.get(file.toAbsolutePath().normalize());
    }
}
