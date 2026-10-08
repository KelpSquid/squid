package squid;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What live reload last did with each mod's file: started it, reloaded it, or found a mistake. Squid's Mod Maker (the
 * in-game code editor) shows it under the code right after Save.
 */
public final class LiveReload {
    /** One result: whether it worked, what was said in the chat, and when (System.currentTimeMillis()). */
    public record Result(boolean worked, String message, long when) {
    }

    private static final Map<Path, Result> LAST = new ConcurrentHashMap<>();

    private LiveReload() {
    }

    static void record(Path file, boolean worked, String message) {
        LAST.put(file.toAbsolutePath().normalize(), new Result(worked, message, System.currentTimeMillis()));
    }

    /** What happened the last time this file was built while the game was running, or null if it hasn't been yet. */
    public static Result last(Path file) {
        return LAST.get(file.toAbsolutePath().normalize());
    }
}
