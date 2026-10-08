package squid;

import squid.api.ModInfo;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * Ids a mod can't have, because Squid itself and its built-in parts use them. A mod called Squid.java would
     * otherwise share Squid's own hooks, and switching it off would switch Squid off too.
     */
    static boolean reserved(String id) {
        return id.equals("squid") || id.equals("minecraft") || id.equals("java") || BUILT_IN_IDS.contains(id);
    }

    private static final Set<String> BUILT_IN_IDS = Set.of("squid-clips", "squid-count", "squid-mods", "squid-net",
            "squid-panorama", "squid-profile", "squid-replay", "squid-skins", "squid-sounds", "squid-store",
            "squid-voice", "squid-voice-server", "squid-jukebox", "squid-paint", "squid-emotes");

    /** Why a mod can't use its id, or null if it can. */
    static String reservedReason(ModInfo mod) {
        if (!reserved(mod.id())) return null;
        return Lang.t("its id \"{0}\" belongs to Squid itself. Give the mod another name.", mod.id());
    }

    /** A file in the mods folder and what it says about itself, before it's built. */
    private record Candidate(Path file, ModInfo mod) {
    }

    /** What a mod file says about itself (its squid.json, or its name), without building it. Null if it isn't a Squid mod. */
    static ModInfo describe(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(".jar")) return read(file);
        if (Files.isDirectory(file)) return readProject(file);
        if (fileName.endsWith(".squid")) return readPacked(file);
        return SourceMods.describeEasy(file);
    }

    /** sources builds mods from their code. Without it (null), those are skipped. */
    static Found find(Path folder, String minecraftVersion, SourceMods sources) throws IOException {
        List<Skipped> skipped = new ArrayList<>();
        if (!Files.isDirectory(folder)) return new Found(new ArrayList<>(), skipped);

        List<Path> files;
        try (Stream<Path> list = Files.list(folder)) {
            files = list.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".jar") || name.endsWith(".java") || name.endsWith(".squid") || isProject(p);
            }).sorted().toList();
        }

        // First read what every file says about itself, without building anything, so a second copy of a mod
        // can't build over the copy that wins
        List<Candidate> candidates = new ArrayList<>();
        for (Path file : files) {
            if (sources == null && !file.getFileName().toString().endsWith(".jar")) {
                skipFile(skipped, file, Lang.t("Squid can't build mods here."));
                continue;
            }
            ModInfo mod;
            try {
                mod = describe(file);
            } catch (IOException | RuntimeException e) {
                // A broken file only skips that mod: one mod never stops the game from opening
                skipFile(skipped, file, reason(e));
                continue;
            }
            if (mod == null) {
                skipFile(skipped, file, Lang.t("it isn't a Squid mod (it has no squid.json), so Squid can't load it."));
                continue;
            }
            candidates.add(new Candidate(file, mod));
        }

        // Copies of the same mod, best first: the newest version; if they're the same, your own code (a project or
        // .java) over a packed copy; then the first by file name
        Map<String, List<Candidate>> byId = new LinkedHashMap<>();
        for (Candidate c : candidates) byId.computeIfAbsent(c.mod().id(), k -> new ArrayList<>()).add(c);
        for (List<Candidate> copies : byId.values()) {
            copies.sort((a, b) -> {
                int versions = compareVersions(b.mod().version(), a.mod().version());
                if (versions != 0) return versions;
                if (source(a.file()) != source(b.file())) return source(a.file()) ? -1 : 1;
                return a.file().compareTo(b.file());
            });
        }

        // The first copy that works is the one that loads. A copy with a mistake says what it is, and the next copy
        // gets its turn, so a broken folder can't hide a working download of the same mod.
        Map<String, ModInfo> ready = new LinkedHashMap<>();
        for (List<Candidate> copies : byId.values()) {
            Candidate loaded = null;
            for (Candidate c : copies) {
                if (loaded != null) {
                    skip(skipped, c.mod(), Lang.t("it's another copy of {0}. You can delete {1}.",
                            loaded.file().getFileName(), c.file().getFileName()));
                    continue;
                }
                ModInfo mod = c.mod();
                // Squid's own ids are only taken in the mods folder: the built-in parts come from Squid's own folder
                String taken = sources != null ? reservedReason(mod) : null;
                if (taken != null) {
                    skip(skipped, mod, taken);
                    continue;
                }
                if (!mod.worksOn(minecraftVersion)) {
                    skip(skipped, mod, Lang.t("it was made for Minecraft {0}, not {1}. Look for an update to it.",
                            String.join(" " + Lang.t("or") + " ", mod.minecraft()), minecraftVersion));
                    continue;
                }
                if (!c.file().getFileName().toString().endsWith(".jar")) {
                    try {
                        mod = sources.build(c.file(), mod);
                    } catch (IOException | RuntimeException e) {
                        // A mistake in a mod's code only skips that copy. It's named after its file, so it's easy to find.
                        String fileName = c.file().getFileName().toString();
                        System.out.println("[Squid] Skipping " + fileName + ": " + reason(e));
                        skipped.add(new Skipped(mod.id(), fileName, reason(e)));
                        continue;
                    }
                }
                ready.put(mod.id(), mod);
                loaded = c;
            }
        }

        // Skip mods that need a mod that isn't here, or that need each other in a loop. Do it again until nothing
        // changes, because skipping one mod can leave another mod without something it needs.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ModInfo mod : List.copyOf(ready.values())) {
                for (String needed : mod.depends()) {
                    if (!ready.containsKey(needed)) {
                        skip(skipped, mod, why(needed, skipped));
                        ready.remove(mod.id());
                        changed = true;
                        break;
                    }
                }
            }
            if (changed) continue;
            List<String> loop = findLoop(ready);
            if (loop != null) {
                String chain = String.join(" -> ", loop);
                for (String id : new LinkedHashSet<>(loop)) {
                    skip(skipped, ready.remove(id), Lang.t("These mods need each other in a loop, so none of them can start first: {0}", chain));
                }
                changed = true;
            }
        }
        if (sources != null) sources.cleanUp(); // old builds of mods that changed or are gone
        return new Found(inStartOrder(new ArrayList<>(ready.values())), List.copyOf(skipped));
    }

    /** Whether a mod is someone's own code: a project folder or a .java file, not a packed .squid or .jar. */
    private static boolean source(Path file) {
        return Files.isDirectory(file) || file.getFileName().toString().endsWith(".java");
    }

    /** A problem's message for the player. Errors that aren't Squid's own, like a file name Windows can't use, get one too. */
    static String reason(Throwable e) {
        if (e instanceof IOException && e.getMessage() != null) return e.getMessage();
        if (e instanceof InvalidPathException bad) {
            return Lang.t("it has a file with a name this computer can't use. Rename that file: {0}", bad.getInput());
        }
        return Lang.t("Squid couldn't read it ({0}).", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    /** Mods that need each other in a circle, like a -> b -> a, or null if there are none. */
    private static List<String> findLoop(Map<String, ModInfo> mods) {
        Set<String> done = new HashSet<>();
        for (String id : mods.keySet()) {
            List<String> loop = findLoop(id, mods, done, new ArrayList<>());
            if (loop != null) return loop;
        }
        return null;
    }

    private static List<String> findLoop(String id, Map<String, ModInfo> mods, Set<String> done, List<String> chain) {
        if (done.contains(id) || !mods.containsKey(id)) return null;
        if (chain.contains(id)) {
            List<String> loop = new ArrayList<>(chain.subList(chain.indexOf(id), chain.size()));
            loop.add(id);
            return loop;
        }
        chain.add(id);
        for (String needed : mods.get(id).depends()) {
            List<String> loop = findLoop(needed, mods, done, chain);
            if (loop != null) return loop;
        }
        chain.remove(chain.size() - 1);
        done.add(id);
        return null;
    }

    /** Compares versions like "1.2.0" and "1.10" number by number. A beta (1.0-beta) comes before its release. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.+-]");
        String[] y = b.split("[.+-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : null;
            String q = i < y.length ? y[i] : null;
            // A missing part counts as 0 ("1.0" is "1.0.0"); a word after it ("1.0.0-beta") comes before the release
            if (p == null) {
                if (number(q) && Long.parseLong(q) == 0) continue;
                return number(q) ? -1 : 1;
            }
            if (q == null) {
                if (number(p) && Long.parseLong(p) == 0) continue;
                return number(p) ? 1 : -1;
            }
            int c = number(p) && number(q) ? Long.compare(Long.parseLong(p), Long.parseLong(q))
                    : number(p) ? 1 : number(q) ? -1 : p.compareTo(q);
            if (c != 0) return c;
        }
        return 0;
    }

    private static boolean number(String part) {
        return part.matches("\\d{1,18}");
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
            String text = SourceMods.text(zip.getInputStream(entry).readAllBytes());
            return info(parse(text, jar.getFileName().toString()), jar, null, null, null);
        } catch (java.util.zip.ZipException e) {
            throw new IOException(Lang.t("it's damaged, so Squid can't open it. Download it again."));
        }
    }

    /**
     * A project's details. Its squid.json can leave out what's obvious: the id and name come from the folder's name
     * (MegaMod becomes mega-mod and "Mega Mod"), the version starts at 1.0, and the main class is named like the folder.
     */
    static ModInfo readProject(Path folder) throws IOException {
        Path file = folder.resolve("squid.json");
        if (!Files.exists(file)) throw new IOException(Lang.t("it needs a squid.json, with at least its \"name\" in it."));
        Map<String, Object> json = parse(SourceMods.text(Files.readAllBytes(file)), null);
        String className = folder.getFileName().toString().replaceAll("[^A-Za-z0-9_]", "");
        return info(json, folder, idFor(className), SourceMods.spaced(className), className);
    }

    /** A mod's id from its class or file name, the same for every kind of mod: MegaMod becomes mega-mod. */
    static String idFor(String className) {
        return SourceMods.spaced(className).toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    /** A .squid file's details: the squid.json inside, which can leave out the same things as a project's. */
    static ModInfo readPacked(Path file) throws IOException {
        Map<String, Object> json;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            String root = SourceMods.packedRoot(zip);
            if (root == null) throw new IOException(Lang.t("it has no squid.json inside. Pack it again from Kelp."));
            json = parse(SourceMods.text(zip.getInputStream(SourceMods.entry(zip, root + "squid.json")).readAllBytes()), null);
        } catch (java.util.zip.ZipException e) {
            throw new IOException(Lang.t("it's damaged, so Squid can't open it. Download or pack it again."));
        }
        String fileName = file.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".squid".length()).replaceAll("[^A-Za-z0-9_]", "");
        return info(json, file, idFor(className), SourceMods.spaced(className), className);
    }

    /** Reads a squid.json. jarName is the jar it's in, for saying which one is broken, or null for "its squid.json". */
    private static Map<String, Object> parse(String text, String jarName) throws IOException {
        Object json;
        try {
            json = Json.parse(text);
        } catch (IllegalArgumentException e) {
            if (jarName == null) throw new IOException(Lang.t("its squid.json is broken: {0}", e.getMessage()));
            throw new IOException(Lang.t("{0}'s squid.json is broken: {1}", jarName, e.getMessage()));
        }
        if (json instanceof Map<?, ?>) return Json.object(json);
        String problem = Lang.t("it has to start with { and end with }");
        if (jarName == null) throw new IOException(Lang.t("its squid.json is broken: {0}", problem));
        throw new IOException(Lang.t("{0}'s squid.json is broken: {1}", jarName, problem));
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
        List<String> minecraft = strings(json, "minecraft");
        // A mod that lists itself in "depends" (easy to copy from an example) doesn't really need itself
        List<String> depends = strings(json, "depends").stream().filter(d -> !d.equals(id)).toList();
        warnUnknownKeys(json, where);
        return new ModInfo(id,
                text(json, "name", defaultName != null ? defaultName : id, where),
                text(json, "version", defaultId != null ? "1.0" : null, where),
                text(json, "description", "", where),
                strings(json, "authors"),
                depends,
                minecraft,
                text(json, "main", defaultMain, where),
                where);
    }

    /** Every key squid.json can have. */
    static final Set<String> KEYS = Set.of("id", "name", "version", "description", "authors", "depends", "minecraft",
            "main", "side", "icon");

    /** Keys people often write by mistake, and the one they meant. */
    static final Map<String, String> DID_YOU_MEAN = Map.ofEntries(
            Map.entry("author", "authors"), Map.entry("dependencies", "depends"), Map.entry("depend", "depends"),
            Map.entry("requires", "depends"), Map.entry("mainclass", "main"), Map.entry("main_class", "main"),
            Map.entry("entrypoint", "main"), Map.entry("mc", "minecraft"), Map.entry("minecraft_version", "minecraft"),
            Map.entry("minecraftversion", "minecraft"), Map.entry("desc", "description"), Map.entry("title", "name"),
            Map.entry("modid", "id"), Map.entry("mod_id", "id"), Map.entry("environment", "side"), Map.entry("logo", "icon"), Map.entry("image", "icon"));

    /**
     * The problems with squid.json keys, like "author" when it should be "authors". They don't stop the mod (it may
     * be from a newer Squid), but Kelp shows them, and they go in the log.
     */
    static List<String> unknownKeys(Map<String, Object> json) {
        List<String> problems = new ArrayList<>();
        for (String key : json.keySet()) {
            if (KEYS.contains(key)) continue;
            String meant = DID_YOU_MEAN.get(key.toLowerCase(Locale.ROOT));
            if (meant == null && KEYS.contains(key.toLowerCase(Locale.ROOT))) meant = key.toLowerCase(Locale.ROOT);
            problems.add(meant != null ? Lang.t("squid.json has \"{0}\". Did you mean \"{1}\"?", key, meant)
                    : Lang.t("squid.json has \"{0}\", which Squid doesn't use.", key));
        }
        return problems;
    }

    private static void warnUnknownKeys(Map<String, Object> json, Path where) {
        for (String problem : unknownKeys(json)) System.out.println("[Squid] " + where.getFileName() + ": " + problem);
    }

    /**
     * A list of strings from squid.json, like "authors": ["Samuel"]. Empty if it isn't there. One value on its own
     * ("authors": "Samuel") counts as a list of one.
     */
    private static List<String> strings(Map<String, Object> json, String key) {
        Object value = json.get(key);
        if (value == null) return List.of();
        List<?> list = value instanceof List<?> many ? many : List.of(value);
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !plain(item).isBlank()) values.add(plain(item).trim());
        }
        return List.copyOf(values);
    }

    /** A JSON value as text. Whole numbers lose their ".0", so "version": 2 is "2", and "minecraft": 26.3 is "26.3". */
    private static String plain(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite()) return String.valueOf(d.longValue());
        return String.valueOf(value);
    }

    /** A text field, or the fallback if it's left out. Without a fallback, it has to be there. */
    private static String text(Map<String, Object> json, String key, String fallback, Path where) throws IOException {
        Object value = json.get(key);
        if (value instanceof String s && !s.isBlank()) return s;
        if (value instanceof Double || value instanceof Boolean) return plain(value);
        if (fallback != null) return fallback;
        throw new IOException(Lang.t("{0}: squid.json needs a \"{1}\"", where.getFileName(), key));
    }
}
