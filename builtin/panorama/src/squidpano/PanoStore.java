package squidpano;

import squid.Main;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The panoramas you captured, kept in Kelp's panoramas folder (so every instance has them), one folder each with its
 * six pictures (panorama_0.png to panorama_5.png), plus which one is in use and how it spins. Nothing here needs
 * Minecraft, so it can be tested on its own.
 */
public final class PanoStore {
    private final Path folder;

    public PanoStore(Path folder) {
        this.folder = folder;
    }

    /** Kelp's panoramas folder: Kelp passes its own folder as -Dsquid.home (or it's two folders up from the instance). */
    public static PanoStore forThisGame() {
        String home = System.getProperty("squid.home");
        if (home != null) return new PanoStore(Path.of(home).resolve("panoramas"));
        Path game = Main.gameFolder().toAbsolutePath();
        Path instances = game.getParent();
        if (instances != null && instances.getFileName() != null && instances.getFileName().toString().equals("instances") && instances.getParent() != null) {
            return new PanoStore(instances.getParent().resolve("panoramas"));
        }
        return new PanoStore(game.resolve("panoramas"));
    }

    public Path folder() {
        return folder;
    }

    /** Every captured panorama's name, newest first. */
    public List<String> list() {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(folder)) return names;
        try (Stream<Path> dirs = Files.list(folder)) {
            dirs.filter(d -> complete(d)).sorted(Comparator.comparing((Path d) -> d.getFileName().toString()).reversed())
                    .forEach(d -> names.add(d.getFileName().toString()));
        } catch (IOException e) {
            // can't look: none to show
        }
        return names;
    }

    /** One of a panorama's six pictures (0 is the front). */
    public Path picture(String name, int side) {
        return folder.resolve(name).resolve("panorama_" + side + ".png");
    }

    private static boolean complete(Path dir) {
        for (int side = 0; side < 6; side++) {
            if (!Files.exists(dir.resolve("panorama_" + side + ".png"))) return false;
        }
        return true;
    }

    /** Keeps the six pictures Minecraft just captured (in from/screenshots) as a new panorama, and gives back its name. */
    public String keep(Path from) throws IOException {
        String name = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss").format(LocalDateTime.now());
        Path dir = folder.resolve(name);
        Files.createDirectories(dir);
        for (int side = 0; side < 6; side++) {
            Files.copy(from.resolve("screenshots").resolve("panorama_" + side + ".png"), dir.resolve("panorama_" + side + ".png"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        return name;
    }

    public void delete(String name) throws IOException {
        Path dir = folder.resolve(name);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        if (name.equals(active())) setActive(null);
    }

    // ---- Which one is in use, and how it spins ----

    private Properties settings() {
        Properties values = new Properties();
        Path file = folder.resolve("settings.properties");
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(in);
            } catch (IOException e) {
                // unreadable: the defaults
            }
        }
        return values;
    }

    private void save(Properties values) {
        try {
            Files.createDirectories(folder);
            try (Writer out = Files.newBufferedWriter(folder.resolve("settings.properties"), StandardCharsets.UTF_8)) {
                values.store(out, "Squid's title-screen panorama");
            }
        } catch (IOException e) {
            System.out.println("[Squid] Couldn't save the panorama settings: " + e.getMessage());
        }
    }

    /** The panorama in use, or null for Minecraft's own. */
    public String active() {
        String name = settings().getProperty("active");
        return name != null && complete(folder.resolve(name)) ? name : null;
    }

    public void setActive(String name) {
        Properties values = settings();
        if (name == null) values.remove("active");
        else values.setProperty("active", name);
        save(values);
    }

    /** How fast it spins compared to Minecraft's own speed: 0 to 3 (1 is normal). */
    public double speed() {
        try {
            return Math.max(0, Math.min(3, Double.parseDouble(settings().getProperty("speed", "1"))));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public void setSpeed(double speed) {
        Properties values = settings();
        values.setProperty("speed", String.valueOf(Math.max(0, Math.min(3, speed))));
        save(values);
    }

    /** Whether it spins the other way. */
    public boolean reversed() {
        return Boolean.parseBoolean(settings().getProperty("reversed", "false"));
    }

    public void setReversed(boolean reversed) {
        Properties values = settings();
        values.setProperty("reversed", String.valueOf(reversed));
        save(values);
    }

    /**
     * Makes a Kelp theme from a panorama (its front picture as the background), in Kelp's themes folder, so Kelp can look
     * like your title screen. Gives back the theme's folder.
     */
    public Path makeKelpTheme(String name) throws IOException {
        Path themes = folder.getParent().resolve("themes");
        String id = "panorama-" + name.replaceAll("[^0-9]", "");
        Path dir = themes.resolve(id);
        Files.createDirectories(dir);
        Files.copy(picture(name, 0), dir.resolve("background.png"), StandardCopyOption.REPLACE_EXISTING);
        Properties values = new Properties();
        values.setProperty("name", "Panorama " + name.substring(0, Math.min(16, name.length())));
        values.setProperty("scene", "plain");
        values.setProperty("water", "none");
        values.setProperty("base", "#000000");
        values.setProperty("buttons", "-1");
        values.setProperty("picture", "background.png");
        try (Writer out = Files.newBufferedWriter(dir.resolve("theme.properties"), StandardCharsets.UTF_8)) {
            values.store(out, "A Kelp theme made from a Squid panorama");
        }
        return dir;
    }

    /** An angle between -180 and 180, like Minecraft keeps its panorama's spin. */
    public static float wrap(float degrees) {
        float d = degrees % 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }
}
