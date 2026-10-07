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
 * Finds Squid mods: .jar files in the mods folder that have a squid.json inside, and single .java files
 * that Squid compiles itself (see {@link SourceMods}).
 * They come back in the order they should start: every mod after the mods it depends on.
 *
 * A mod that can't work this time (not a Squid mod, a mistake in its code, made for another Minecraft version,
 * a second copy of a mod, or missing a mod it needs) is skipped with a reason, instead of stopping the game.
 * The rest still load.
 */
final class Mods {
    private Mods() {
    }

    /** A mod Squid didn't load, and why, in words for the player. */
    record Skipped(String id, String name, String reason) {
    }

    /** The mods to start, in order, and the ones that were skipped. */
    record Found(List<ModInfo> mods, List<Skipped> skipped) {
    }

    static Found find(Path folder, String minecraftVersion) throws IOException {
        return find(folder, minecraftVersion, null);
    }

    /** sources compiles .java mods. Without it (null), .java files are skipped. */
    static Found find(Path folder, String minecraftVersion, SourceMods sources) throws IOException {
        List<ModInfo> mods = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        if (!Files.isDirectory(folder)) return new Found(mods, skipped);

        List<Path> files;
        try (Stream<Path> list = Files.list(folder)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".jar") || p.getFileName().toString().endsWith(".java"))
                    .sorted().toList();
        }
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        Map<String, Path> fileOf = new LinkedHashMap<>(); // mod id -> the file it came from
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            ModInfo mod;
            try {
                if (fileName.endsWith(".jar")) {
                    mod = read(file);
                } else if (sources != null) {
                    mod = sources.compile(file);
                } else {
                    skipFile(skipped, file, "Squid can't compile mods here.");
                    continue;
                }
            } catch (IOException e) {
                // A broken jar or a mistake in a mod's code only skips that mod
                skipFile(skipped, file, e.getMessage());
                continue;
            }
            if (mod == null) {
                skipFile(skipped, file, "it isn't a Squid mod (it has no squid.json), so Squid can't load it.");
                continue;
            }
            ModInfo first = byId.get(mod.id());
            if (first != null) {
                skip(skipped, mod, "it's another copy of " + fileOf.get(mod.id()).getFileName() + ". You can delete " + fileName + ".");
            } else if (!mod.worksOn(minecraftVersion)) {
                skip(skipped, mod, "it was made for Minecraft " + String.join(" or ", mod.minecraft())
                        + ", not " + minecraftVersion + ". Look for an update to it.");
            } else {
                byId.put(mod.id(), mod);
                fileOf.put(mod.id(), file);
            }
        }
        // Skip mods that need a mod that isn't here. Do it again until nothing changes, because skipping one
        // mod can leave another mod without something it needs.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ModInfo mod : List.copyOf(byId.values())) {
                for (String needed : mod.depends()) {
                    if (!byId.containsKey(needed)) {
                        skip(skipped, mod, why(needed, skipped));
                        byId.remove(mod.id());
                        changed = true;
                        break;
                    }
                }
            }
        }
        mods.addAll(byId.values());
        return new Found(inStartOrder(mods), List.copyOf(skipped));
    }

    /** Skips a file that couldn't become a mod at all. It's named after the file, like "Hello.java". */
    private static void skipFile(List<Skipped> skipped, Path file, String reason) {
        String name = file.getFileName().toString();
        System.out.println("[Squid] Skipping " + name + ": " + reason);
        skipped.add(new Skipped(name, name, reason));
    }

    private static void skip(List<Skipped> skipped, ModInfo mod, String reason) {
        System.out.println("[Squid] Skipping " + mod.name() + ": " + reason);
        skipped.add(new Skipped(mod.id(), mod.name(), reason));
    }

    /** Why a needed mod isn't there: it was skipped too, or it's missing. */
    private static String why(String needed, List<Skipped> skipped) {
        for (Skipped other : skipped) {
            if (other.id().equals(needed)) return "it needs " + other.name() + ", which was skipped too.";
        }
        return "it needs the mod \"" + needed + "\", but it isn't in the mods folder.";
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
            // "minecraft" can be one version ("26.3") or a list (["26.3", "26.4"])
            List<String> minecraft = json.get("minecraft") instanceof String one ? List.of(one) : strings(json, "minecraft");
            return new ModInfo(id,
                    json.get("name") != null ? (String) json.get("name") : id,
                    required(json, "version", jar),
                    json.get("description") != null ? (String) json.get("description") : "",
                    authors,
                    depends,
                    minecraft,
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
