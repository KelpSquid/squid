package squid.api;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A mod's settings, which players change in-game (Squid's Mods screen, the mod's Settings button) without touching
 * code. A mod says what settings it has and their defaults, and asks for the current value whenever it needs it:
 *
 * <pre>
 * boolean showMap = setting("Show map", true);
 * int zoom = setting("Zoom", 4, 1, 10);
 * String corner = setting("Corner", "Top left", "Top left", "Top right");
 * </pre>
 *
 * Changes take effect right away, and are saved in the instance's config/squid folder, one file per mod.
 */
public final class ModSettings {
    /** One setting: its name, kind and limits. */
    public record Setting(String name, Object defaultValue, int min, int max, List<String> choices) {
        public boolean isToggle() {
            return defaultValue instanceof Boolean;
        }

        public boolean isNumber() {
            return defaultValue instanceof Integer;
        }

        public boolean isChoice() {
            return defaultValue instanceof String;
        }
    }

    private static final Map<String, ModSettings> ALL = new ConcurrentHashMap<>();

    private final String modId;
    private final Map<String, Setting> settings = new LinkedHashMap<>();
    private final Properties values = new Properties();
    private boolean loaded;

    private ModSettings(String modId) {
        this.modId = modId;
    }

    /** The settings of a mod (made the first time it's asked for). */
    public static ModSettings of(String modId) {
        return ALL.computeIfAbsent(modId, ModSettings::new);
    }

    /** Whether a mod has any settings, for the Mods screen's Settings button. */
    public static boolean has(String modId) {
        ModSettings found = ALL.get(modId);
        return found != null && !found.list().isEmpty();
    }

    /** Every setting, in the order the mod first asked for them. */
    public synchronized List<Setting> list() {
        return new ArrayList<>(settings.values());
    }

    /** An on/off setting. */
    public synchronized boolean toggle(String name, boolean defaultValue) {
        define(new Setting(name, defaultValue, 0, 0, List.of()));
        return Boolean.parseBoolean(value(name, String.valueOf(defaultValue)));
    }

    /** A whole number from min to max, shown as a slider. */
    public synchronized int number(String name, int defaultValue, int min, int max) {
        define(new Setting(name, defaultValue, Math.min(min, max), Math.max(min, max), List.of()));
        try {
            return Math.clamp(Long.parseLong(value(name, String.valueOf(defaultValue))), Math.min(min, max), Math.max(min, max));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** One of a few choices, shown as a button that goes through them. */
    public synchronized String choice(String name, String defaultValue, String... choices) {
        List<String> all = new ArrayList<>(List.of(choices));
        if (!all.contains(defaultValue)) all.add(0, defaultValue);
        define(new Setting(name, defaultValue, 0, 0, List.copyOf(all)));
        String value = value(name, defaultValue);
        return all.contains(value) ? value : defaultValue;
    }

    /** A number the mod kept with {@link #remember} (like how many diamonds you've ever mined), or startingValue. */
    public synchronized int remembered(String name, int startingValue) {
        try {
            return Integer.parseInt(value("remember." + name, String.valueOf(startingValue)));
        } catch (NumberFormatException e) {
            return startingValue;
        }
    }

    /** Keeps a number for next time. The file is only written when the number changes. */
    public synchronized void remember(String name, int value) {
        if (String.valueOf(value).equals(value("remember." + name, null))) return;
        set("remember." + name, value);
    }

    /** The current value of a setting, as text (for the settings screen). */
    public synchronized String get(String name) {
        Setting setting = settings.get(name);
        return setting == null ? null : value(name, String.valueOf(setting.defaultValue()));
    }

    /** Changes a setting and saves it. Mods see the new value the next time they ask. */
    public synchronized void set(String name, Object value) {
        load();
        values.setProperty(name, String.valueOf(value));
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                values.store(out, modId + " settings, changed in-game");
            }
        } catch (IOException e) {
            System.out.println("[Squid] Couldn't save " + modId + "'s settings: " + e.getMessage());
        }
    }

    private void define(Setting setting) {
        settings.putIfAbsent(setting.name(), setting);
    }

    private String value(String name, String fallback) {
        load();
        return values.getProperty(name, fallback);
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        Path file = file();
        if (!Files.exists(file)) return;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(in);
        } catch (IOException e) {
            System.out.println("[Squid] Couldn't read " + modId + "'s settings: " + e.getMessage());
        }
    }

    private Path file() {
        return squid.Main.gameFolder().resolve("config").resolve("squid").resolve(modId + ".properties");
    }
}
