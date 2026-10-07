package squid;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Squid in other languages, following the language picked in Minecraft's own Options. Every bit of text goes through
 * {@link #t}, written in English, and each language is a plain text file inside squid.jar (lang/ja_jp.txt), one line
 * per text: "Store => ストア". Anything a file doesn't have shows in English. Every language but English is BETA:
 * an AI wrote them, and no native speaker has checked them yet.
 */
public final class Lang {
    private Lang() {
    }

    public static final String ENGLISH = "en_us";

    private static final Map<String, Map<String, String>> tables = new HashMap<>();
    private static String code = ENGLISH;
    private static long checkedAt;

    /**
     * The text in Minecraft's language. {0}, {1}... are filled in with the values, so a sentence with a name in it is
     * translated as one sentence: t("Installed {0}!", name).
     */
    public static String t(String english, Object... values) {
        String text = table(language()).getOrDefault(english, english);
        for (int i = 0; i < values.length; i++) text = text.replace("{" + i + "}", String.valueOf(values[i]));
        return text;
    }

    /** Minecraft's language code right now, like "ja_jp", looked at again at most once a second. */
    public static synchronized String language() {
        long now = System.currentTimeMillis();
        if (now - checkedAt > 1000) {
            checkedAt = now;
            String found = fromGame();
            if (found == null) found = fromOptionsFile();
            if (found != null) code = found.toLowerCase();
        }
        return code;
    }

    /** The language the running game is in, or null before the game has started. */
    private static String fromGame() {
        try {
            ClassLoader loader = Main.gameLoader();
            if (loader == null || !Main.gameStarted()) return null;
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Object game = minecraft.getMethod("getInstance").invoke(null);
            if (game == null) return null;
            Object options = minecraft.getField("options").get(game);
            return options == null ? null : (String) options.getClass().getField("languageCode").get(options);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /** The language saved in the instance's options.txt ("lang:ja_jp"), for before the game is up. */
    private static String fromOptionsFile() {
        try {
            Path options = Main.gameFolder().resolve("options.txt");
            if (!Files.exists(options)) return null;
            for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
                if (line.startsWith("lang:")) return line.substring(5).trim();
            }
        } catch (IOException | RuntimeException e) {
            // no options yet: English
        }
        return null;
    }

    /** Which file a Minecraft language uses: English variants use English, Spanish and French ones share. */
    static String fileFor(String language) {
        if (language.startsWith("en_") && !language.equals("en_pt") && !language.equals("en_ud")) return ENGLISH;
        return switch (language) {
            case "es_ar", "es_cl", "es_ec", "es_uy", "es_ve" -> "es_mx";
            case "fr_ca", "fr_ch" -> "fr_fr";
            default -> language;
        };
    }

    private static synchronized Map<String, String> table(String language) {
        String file = fileFor(language);
        return tables.computeIfAbsent(file, Lang::load);
    }

    private static Map<String, String> load(String file) {
        if (file.equals(ENGLISH)) return Map.of();
        try (InputStream in = Lang.class.getResourceAsStream("/lang/" + file + ".txt")) {
            if (in == null) return Map.of();
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("[Squid] Couldn't read the " + file + " language file: " + e.getMessage());
            return Map.of();
        }
    }

    /** Reads a language file: "English => translation" on each line. Lines starting with # are notes. */
    public static Map<String, String> parse(String text) {
        Map<String, String> map = new HashMap<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int arrow = line.indexOf(" => ");
            if (arrow < 0) continue;
            String english = line.substring(0, arrow).strip();
            String translated = line.substring(arrow + 4).strip();
            if (!translated.isEmpty()) map.put(english, translated);
        }
        return map;
    }

    /** Uses this language from now on, whatever Minecraft says. Only tests need this. */
    static synchronized void force(String language) {
        code = language;
        checkedAt = Long.MAX_VALUE;
    }
}
