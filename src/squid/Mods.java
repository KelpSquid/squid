package squid;

import squid.api.ModInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds Squid mods: .jar files in the mods folder that have a squid.json inside.
 * They come back in the order they should start: every mod after the mods it depends on.
 */
final class Mods {
    private Mods() {
    }

    static List<ModInfo> find(Path folder) throws IOException {
        List<ModInfo> mods = new ArrayList<>();
        if (!Files.isDirectory(folder)) return mods;

        List<Path> jars;
        try (Stream<Path> files = Files.list(folder)) {
            jars = files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
        Set<String> ids = new HashSet<>();
        for (Path jar : jars) {
            ModInfo mod = read(jar);
            if (mod == null) {
                System.out.println("[Squid] Skipping " + jar.getFileName() + ": it has no squid.json, so it isn't a Squid mod");
                continue;
            }
            if (!ids.add(mod.id())) {
                throw new IOException("Two mods have the id \"" + mod.id() + "\". Remove one of them from the mods folder.");
            }
            mods.add(mod);
        }
        return inStartOrder(mods);
    }

    /** Puts every mod after the mods it depends on. Explains what's wrong if a mod is missing or mods need each other. */
    static List<ModInfo> inStartOrder(List<ModInfo> mods) throws IOException {
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        for (ModInfo mod : mods) byId.put(mod.id(), mod);
        for (ModInfo mod : mods) {
            for (String needed : mod.depends()) {
                if (!byId.containsKey(needed)) {
                    throw new IOException(mod.name() + " needs the mod \"" + needed + "\", but it isn't in the mods folder.");
                }
            }
        }
        List<ModInfo> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (ModInfo mod : mods) place(mod, byId, ordered, placed, new ArrayList<>());
        return ordered;
    }

    private static void place(ModInfo mod, Map<String, ModInfo> byId, List<ModInfo> ordered, Set<String> placed,
                              List<String> chain) throws IOException {
        if (placed.contains(mod.id())) return;
        if (chain.contains(mod.id())) {
            chain.add(mod.id());
            throw new IOException("These mods need each other in a loop, so none of them can start first: "
                    + String.join(" -> ", chain.subList(chain.indexOf(mod.id()), chain.size())));
        }
        chain.add(mod.id());
        for (String needed : mod.depends()) place(byId.get(needed), byId, ordered, placed, chain);
        chain.remove(chain.size() - 1);
        placed.add(mod.id());
        ordered.add(mod);
    }

    private static ModInfo read(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("squid.json");
            if (entry == null) return null;
            String text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> json;
            try {
                json = Json.object(Json.parse(text));
            } catch (IllegalArgumentException e) {
                throw new IOException(jar.getFileName() + " has a broken squid.json: " + e.getMessage());
            }
            String id = required(json, "id", jar);
            if (!id.matches("[a-z0-9_-]+")) {
                throw new IOException(jar.getFileName() + ": a mod id can only use a-z, 0-9, _ and -");
            }
            List<String> authors = strings(json, "authors");
            List<String> depends = strings(json, "depends");
            return new ModInfo(id,
                    json.get("name") != null ? (String) json.get("name") : id,
                    required(json, "version", jar),
                    json.get("description") != null ? (String) json.get("description") : "",
                    authors,
                    depends,
                    required(json, "main", jar),
                    jar);
        }
    }

    /** A list of strings from squid.json, like "authors": ["Samuel"]. Empty if it isn't there. */
    private static List<String> strings(Map<String, Object> json, String key) {
        List<String> values = new ArrayList<>();
        if (json.get(key) != null) {
            for (Object value : Json.array(json.get(key))) values.add(String.valueOf(value));
        }
        return List.copyOf(values);
    }

    private static String required(Map<String, Object> json, String key, Path jar) throws IOException {
        if (!(json.get(key) instanceof String value) || value.isBlank()) {
            throw new IOException(jar.getFileName() + ": squid.json needs a \"" + key + "\"");
        }
        return value;
    }
}
