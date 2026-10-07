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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds Squid mods in the mods folder: .jar files with a squid.json inside, and mods Squid builds itself from their
 * code (see {@link SourceMods}): single .java files, project folders, and .squid files (a project packed into one file).
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

    /** sources builds mods from their code. Without it (null), those are skipped. */
    static Found find(Path folder, String minecraftVersion, SourceMods sources) throws IOException {
        List<ModInfo> mods = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        if (!Files.isDirectory(folder)) return new Found(mods, skipped);

        List<Path> files;
        try (Stream<Path> list = Files.list(folder)) {
            files = list.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".jar") || name.endsWith(".java") || name.endsWith(".squid") || isProject(p);
            }).sorted().toList();
        }
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        Map<String, Path> fileOf = new LinkedHashMap<>(); // mod id -> the file it came from
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            ModInfo mod;
            try {
                if (fileName.endsWith(".jar")) {
                    mod = read(file);
                } else if (sources == null) {
                    skipFile(skipped, file, Lang.t("Squid can't build mods here."));
                    continue;
                } else if (Files.isDirectory(file)) {
                    mod = sources.compileProject(file, readProject(file));
                } else if (fileName.endsWith(".squid")) {
                    mod = sources.compilePacked(file, readPacked(file));
                } else {
                    mod = sources.compile(file);
                }
            } catch (IOException e) {
                // A broken jar or a mistake in a mod's code only skips that mod
                skipFile(skipped, file, e.getMessage());
                continue;
            }
            if (mod == null) {
                skipFile(skipped, file, Lang.t("it isn't a Squid mod (it has no squid.json), so Squid can't load it."));
                continue;
            }
            ModInfo first = byId.get(mod.id());
            if (first != null) {
                skip(skipped, mod, Lang.t("it's another copy of {0}. You can delete {1}.", fileOf.get(mod.id()).getFileName(), fileName));
            } else if (!mod.worksOn(minecraftVersion)) {
                skip(skipped, mod, Lang.t("it was made for Minecraft {0}, not {1}. Look for an update to it.",
                        String.join(" " + Lang.t("or") + " ", mod.minecraft()), minecraftVersion));
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
            if (other.id().equals(needed)) return Lang.t("it needs {0}, which was skipped too.", other.name());
        }
        return Lang.t("it needs the mod \"{0}\", but it isn't in the mods folder.", needed);
    }

    /** Puts every mod after the mods it depends on. Explains what's wrong if a mod is missing or mods need each other. */
    static List<ModInfo> inStartOrder(List<ModInfo> mods) throws IOException {
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        for (ModInfo mod : mods) byId.put(mod.id(), mod);
        for (ModInfo mod : mods) {
            for (String needed : mod.depends()) {
                if (!byId.containsKey(needed)) {
                    throw new IOException(Lang.t("{0} needs the mod \"{1}\", but it isn't in the mods folder.", mod.name(), needed));
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
            throw new IOException(Lang.t("These mods need each other in a loop, so none of them can start first: {0}",
                    String.join(" -> ", chain.subList(chain.indexOf(mod.id()), chain.size()))));
        }
        chain.add(mod.id());
        for (String needed : mod.depends()) place(byId.get(needed), byId, ordered, placed, chain);
        chain.remove(chain.size() - 1);
        placed.add(mod.id());
        ordered.add(mod);
    }

    /**
     * A folder Squid treats as a project: one with a squid.json or a src folder. Folders starting with . are Squid's
     * own, and ones ending in .disabled are turned off.
     */
    static boolean isProject(Path path) {
        String name = path.getFileName().toString();
        return Files.isDirectory(path) && !name.startsWith(".") && !name.endsWith(".disabled")
                && (Files.exists(path.resolve("squid.json")) || Files.isDirectory(path.resolve("src")));
    }

    private static ModInfo read(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("squid.json");
            if (entry == null) return null;
            String text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            return info(parse(text, jar.getFileName().toString()), jar, null, null, null);
        }
    }

    /**
     * A project's details. Its squid.json can leave out what's obvious: the id and name come from the folder's name
     * (MegaMod becomes mega-mod and "Mega Mod"), the version starts at 1.0, and the main class is named like the folder.
     */
    static ModInfo readProject(Path folder) throws IOException {
        Path file = folder.resolve("squid.json");
        if (!Files.exists(file)) throw new IOException(Lang.t("it needs a squid.json, with at least its \"name\" in it."));
        Map<String, Object> json = parse(Files.readString(file, StandardCharsets.UTF_8), null);
        String className = folder.getFileName().toString().replaceAll("[^A-Za-z0-9_]", "");
        String id = SourceMods.spaced(className).toLowerCase(Locale.ROOT).replace(' ', '-');
        return info(json, folder, id, SourceMods.spaced(className), className);
    }

    /** A .squid file's details: the squid.json inside, which can leave out the same things as a project's. */
    static ModInfo readPacked(Path file) throws IOException {
        Map<String, Object> json;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("squid.json");
            if (entry == null) throw new IOException(Lang.t("it has no squid.json inside. Pack it again from Kelp."));
            json = parse(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8), null);
        } catch (java.util.zip.ZipException e) {
            throw new IOException(Lang.t("it's damaged, so Squid can't open it. Download or pack it again."));
        }
        String fileName = file.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".squid".length()).replaceAll("[^A-Za-z0-9_]", "");
        String id = SourceMods.spaced(className).toLowerCase(Locale.ROOT).replace(' ', '-');
        return info(json, file, id, SourceMods.spaced(className), className);
    }

    /** Reads a squid.json. jarName is the jar it's in, for saying which one is broken, or null for "its squid.json". */
    private static Map<String, Object> parse(String text, String jarName) throws IOException {
        try {
            return Json.object(Json.parse(text));
        } catch (IllegalArgumentException e) {
            if (jarName == null) throw new IOException(Lang.t("its squid.json is broken: {0}", e.getMessage()));
            throw new IOException(Lang.t("{0}'s squid.json is broken: {1}", jarName, e.getMessage()));
        }
    }

    /** Reads a squid.json. The defaults fill in what it leaves out; a null default means it has to be there. */
    private static ModInfo info(Map<String, Object> json, Path where, String defaultId, String defaultName, String defaultMain)
            throws IOException {
        String id = text(json, "id", defaultId, where);
        if (!id.matches("[a-z0-9_-]+")) {
            throw new IOException(Lang.t("{0}: a mod id can only use a-z, 0-9, _ and -", where.getFileName()));
        }
        // "side" says where a mod runs: "client" (the game, the default), "server", or "both"
        String side = text(json, "side", "client", where);
        if (!side.equals("both") && !side.equals(Main.isServer() ? "server" : "client")) {
            throw new IOException(side.equals("server") ? Lang.t("it's a server mod, so it only runs on servers.")
                    : Lang.t("it's a mod for the game, so it doesn't run on servers."));
        }
        // "minecraft" can be one version ("26.3") or a list (["26.3", "26.4"])
        List<String> minecraft = json.get("minecraft") instanceof String one ? List.of(one) : strings(json, "minecraft");
        return new ModInfo(id,
                text(json, "name", defaultName != null ? defaultName : id, where),
                text(json, "version", defaultId != null ? "1.0" : null, where),
                text(json, "description", "", where),
                strings(json, "authors"),
                strings(json, "depends"),
                minecraft,
                text(json, "main", defaultMain, where),
                where);
    }

    /** A list of strings from squid.json, like "authors": ["Samuel"]. Empty if it isn't there. */
    private static List<String> strings(Map<String, Object> json, String key) {
        List<String> values = new ArrayList<>();
        if (json.get(key) != null) {
            for (Object value : Json.array(json.get(key))) values.add(String.valueOf(value));
        }
        return List.copyOf(values);
    }

    /** A text field, or the fallback if it's left out. Without a fallback, it has to be there. */
    private static String text(Map<String, Object> json, String key, String fallback, Path where) throws IOException {
        if (json.get(key) instanceof String value && !value.isBlank()) return value;
        if (fallback != null) return fallback;
        throw new IOException(Lang.t("{0}: squid.json needs a \"{1}\"", where.getFileName(), key));
    }
}
