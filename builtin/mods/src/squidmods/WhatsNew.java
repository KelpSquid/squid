package squidmods;

import squid.Main;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What's new in Squid, shown once on the title screen after an update (and any time from the Squid menu), so new
 * things get found. Which update was last shown is kept in Kelp's folder, so it's once per computer, not per instance.
 * Nothing here needs Minecraft, so it can be tested on its own; the screen is {@link WhatsNewScreen}.
 */
public final class WhatsNew {
    /** Which update this is. Change it (and the list) when there's something new to show. */
    static final String UPDATE = "2026-10-08";

    /** The new things, a line each, shown in the player's language. */
    static final List<String> LINES = List.of(
            "Mod Maker: make your own mods right in the game",
            "Block Painter: repaint blocks, items, mobs and paintings",
            "Sound Swapper: swap any sound, or record your own",
            "Emotes: press J and pick one",
            "Karaoke: give a Jukebox song its .lrc lyrics file",
            "Voice changer: in Voice Chat, try Robot or Chipmunk");

    private WhatsNew() {
    }

    /** Where it's kept: Kelp's folder (Kelp passes -Dsquid.home), or the game folder without Kelp. */
    static Path file() {
        String home = System.getProperty("squid.home");
        return (home != null ? Path.of(home) : Main.gameFolder()).resolve("squid-whats-new.txt");
    }

    /** Whether this update's news was shown already. */
    public static boolean seen(Path file) {
        try {
            return Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).strip().equals(UPDATE);
        } catch (IOException e) {
            return true; // can't tell: better not to show it every time
        }
    }

    /** Notes that this update's news was shown, so it isn't shown again. */
    public static void markSeen(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, UPDATE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[Squid] Couldn't note that What's New was shown: " + e.getMessage());
        }
    }
}
