package squid;

import squid.api.ModInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Finds Squid mods: .jar files in the mods folder that have a squid.json inside. */
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
        return mods;
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
            List<String> authors = new ArrayList<>();
            if (json.get("authors") != null) {
                for (Object author : Json.array(json.get("authors"))) authors.add(String.valueOf(author));
            }
            return new ModInfo(id,
                    json.get("name") != null ? (String) json.get("name") : id,
                    required(json, "version", jar),
                    json.get("description") != null ? (String) json.get("description") : "",
                    List.copyOf(authors),
                    required(json, "main", jar),
                    jar);
        }
    }

    private static String required(Map<String, Object> json, String key, Path jar) throws IOException {
        if (!(json.get(key) instanceof String value) || value.isBlank()) {
            throw new IOException(jar.getFileName() + ": squid.json needs a \"" + key + "\"");
        }
        return value;
    }
}
