package squidmods;

import squid.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The mods in an instance's mods folder, on or off, for the Mods screen. Turned-off mods end in ".disabled", the same
 * as in Kelp. Nothing here needs Minecraft, so it can be tested on its own.
 */
public final class ModFiles {
    private ModFiles() {
    }

    private static final String OFF = ".disabled";

    /**
     * One mod: where it is, its id and name, whether it's on, and whether Squid builds it from code (those turn on and
     * off right away while playing; jars need a restart). squid is false for a jar that isn't a Squid mod.
     */
    public record ModFile(Path file, String id, String name, String version, String description, boolean enabled,
                          boolean fromCode, boolean squid) {
    }

    /** Every mod in the folder, by name. */
    public static List<ModFile> list(Path mods) {
        List<ModFile> found = new ArrayList<>();
        if (!Files.isDirectory(mods)) return found;
        try (Stream<Path> files = Files.list(mods)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith(".")) continue;
                boolean enabled = !name.endsWith(OFF);
                String plain = enabled ? name : name.substring(0, name.length() - OFF.length());
                ModFile mod = null;
                if (Files.isDirectory(file)) {
                    if (Files.exists(file.resolve("squid.json")) || Files.isDirectory(file.resolve("src"))) mod = project(file, plain, enabled);
                } else if (plain.endsWith(".java")) {
                    String className = plain.substring(0, plain.length() - ".java".length());
                    // the same id Squid gives a .java mod (and a project or .squid with that name): MegaMod is mega-mod
                    mod = new ModFile(file, idFrom(className), spaced(className), "", "", enabled, true, true);
                } else if (plain.endsWith(".jar") || plain.endsWith(".squid")) {
                    mod = packed(file, plain, enabled);
                }
                if (mod != null) found.add(mod);
            }
        } catch (IOException e) {
            System.out.println("[Squid] Couldn't list the mods folder: " + e.getMessage());
        }
        found.sort(Comparator.comparing(m -> m.name().toLowerCase(Locale.ROOT)));
        return found;
    }

    /** Turns a mod on or off by renaming it, and gives back its new place. */
    public static Path toggle(ModFile mod) throws IOException {
        String name = mod.file().getFileName().toString();
        Path renamed = mod.file().resolveSibling(mod.enabled() ? name + OFF : name.substring(0, name.length() - OFF.length()));
        return Files.move(mod.file(), renamed);
    }

    private static ModFile project(Path folder, String plain, boolean enabled) {
        String className = plain.replaceAll("[^A-Za-z0-9_]", "");
        Map<String, Object> json = Map.of();
        try {
            Path file = folder.resolve("squid.json");
            if (Files.exists(file) && Json.parse(Packed.text(Files.readAllBytes(file))) instanceof Map<?, ?> found) json = Json.object(found);
        } catch (IOException | RuntimeException e) {
            // a broken squid.json: still listed, by its folder's name
        }
        if (json == null) json = Map.of();
        return new ModFile(folder, text(json, "id", idFrom(className)), text(json, "name", spaced(className)), text(json, "version", "1.0"),
                text(json, "description", ""), enabled, true, true);
    }

    private static ModFile packed(Path file, String plain, boolean enabled) {
        boolean fromCode = plain.endsWith(".squid");
        String base = plain.substring(0, plain.lastIndexOf('.'));
        try (ZipFile zip = new ZipFile(file.toFile())) {
            String root = fromCode ? Packed.root(zip) : "";
            ZipEntry entry = root == null ? null : fromCode ? Packed.entry(zip, root + "squid.json") : zip.getEntry("squid.json");
            if (entry == null) return new ModFile(file, base, base, "", "", enabled, false, false);
            Object parsed = Json.parse(Packed.text(Packed.small(zip.getInputStream(entry), 1 << 20)));
            Map<String, Object> json = parsed instanceof Map<?, ?> ? Json.object(parsed) : Map.of();
            String className = base.replaceAll("[^A-Za-z0-9_]", "");
            return new ModFile(file, text(json, "id", idFrom(className)), text(json, "name", spaced(className)), text(json, "version", ""),
                    text(json, "description", ""), enabled, fromCode, true);
        } catch (IOException | RuntimeException e) {
            return new ModFile(file, base, base, "", "", enabled, fromCode, false);
        }
    }

    private static String text(Map<String, Object> json, String key, String fallback) {
        return json.get(key) instanceof String value && !value.isBlank() ? value : fallback;
    }

    private static String idFrom(String className) {
        return spaced(className).toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    /** "MyCoolMod" becomes "My Cool Mod", the same way Squid names mods. */
    static String spaced(String className) {
        return className.replace('_', ' ')
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
                .replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ")
                .replaceAll("(?<=[A-Za-z])(?=[0-9])", " ")
                .trim();
    }
}
