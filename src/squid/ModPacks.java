package squid;

import squid.api.ModInfo;
import squid.api.Reflect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A mod's resources folder working on its own: resources/assets/... is a resource pack while the mod is on (textures,
 * sounds, .sqda, lang, models), and resources/data/... a data pack on the worlds and servers the mod runs on (recipes,
 * loot tables, advancements...). There's nothing to switch on: each mod's pack is "required", like Minecraft's own.
 * A mod's resource pack sits just above Minecraft's own, so the player's resource packs (and Squid Paint) still win.
 * Data packs are only for mods that run on servers ("side" "both" or "server"): a data pack is saved into a world, so
 * a mod for the game alone mustn't change the worlds it visits.
 *
 * Squid hooks the place Minecraft lists its built-in packs (BuiltInPackSource.loadPacks, used for both kinds) and adds
 * one pack per mod that has that folder. Squid is built without Minecraft, so the packs are made through
 * {@link Reflect}; this only runs when Minecraft (re)loads its packs, so that costs nothing while playing.
 */
public final class ModPacks {
    private ModPacks() {
    }

    /** Hooked once, as Squid starts. */
    static void install() {
        Events.hookAtStartup("net.minecraft.server.packs.repository.BuiltInPackSource", "loadPacks", false,
                call -> add(call.self(), call.args()[0]));
    }

    /** What the mods' pictures and sounds were the last time Minecraft loaded them, to notice a mod changing them. */
    private static volatile String loadedAssets;
    private static volatile List<ModInfo> checkedMods;

    @SuppressWarnings("unchecked")
    static void add(Object source, Object packs) {
        String folder = Reflect.call(Reflect.get(source, "packType"), "getDirectory"); // "assets" or "data"
        for (ModInfo mod : Main.mods()) {
            if (!has(mod.jar(), folder)) continue;
            if (folder.equals("data") && !Main.isServer() && !runsOnServers(mod)) {
                System.out.println("[Squid] " + mod.name() + " has a data folder, but it's a mod for the game only (its squid.json"
                        + " needs \"side\": \"both\"), so its data pack isn't used.");
                continue;
            }
            try {
                ((Consumer<Object>) packs).accept(pack(mod, folder, source.getClass().getClassLoader()));
            } catch (RuntimeException | LinkageError e) {
                System.out.println("[Squid] Couldn't use " + mod.name() + "'s " + folder + " folder as a pack: " + Main.describe(e));
            }
        }
        if (folder.equals("assets")) {
            checkedMods = Main.mods();
            loadedAssets = signature(checkedMods);
        }
    }

    /** One mod's pack, always on, above the vanilla pack. Minecraft's classes come from the loader that asked. */
    private static Object pack(ModInfo mod, String folder, ClassLoader game) {
        String base = "net.minecraft.server.packs.";
        Class<?> component = type("net.minecraft.network.chat.Component", game);
        Object title = Reflect.callStatic(component, "literal", mod.name());
        Object description = Reflect.callStatic(component, "literal", folder.equals("assets")
                ? Lang.t("{0}: its pictures and sounds", mod.name()) : Lang.t("{0}: its data", mod.name()));
        Object location = Reflect.create(type(base + "PackLocationInfo", game), "squid/" + mod.id(), title,
                Reflect.getStatic(type(base + "repository.PackSource", game), "BUILT_IN"), Optional.empty());
        Object resources = Files.isDirectory(mod.jar())
                ? Reflect.create(type(base + "PathPackResources$PathResourcesSupplier", game), mod.jar())
                : Reflect.create(type(base + "FilePackResources$FileResourcesSupplier", game), mod.jar());
        Object metadata = Reflect.create(type(base + "repository.Pack$Metadata", game), description,
                Reflect.getStatic(type(base + "repository.PackCompatibility", game), "COMPATIBLE"),
                Reflect.callStatic(type("net.minecraft.world.flag.FeatureFlagSet", game), "of"), List.of());
        // Required (always on), and BOTTOM but not fixed: Minecraft puts it just above its own pack, under the player's
        Object selection = Reflect.create(type(base + "PackSelectionConfig", game), true,
                Reflect.getStatic(type(base + "repository.Pack$Position", game), "BOTTOM"), false);
        return Reflect.create(type(base + "repository.Pack", game), location, resources, metadata, selection);
    }

    private static Class<?> type(String name, ClassLoader game) {
        try {
            return Class.forName(name, true, game);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't find Minecraft's {0}", name), e);
        }
    }

    /** Whether a mod's squid.json says it runs on servers ("side": "both" or "server"). Easy mods don't. */
    static boolean runsOnServers(ModInfo mod) {
        String side = side(mod);
        return side.equals("both") || side.equals("server");
    }

    /** A mod's "side", read from its squid.json (in its jar, or in the folder or .squid it was built from). */
    static String side(ModInfo mod) {
        try {
            String json = null;
            Path jar = mod.jar();
            Path source = sourceOf(mod);
            if (Files.isRegularFile(jar)) json = fromZip(jar, false);
            else if (Files.isRegularFile(jar.resolve("squid.json"))) json = small(jar.resolve("squid.json"));
            else if (source != null && Files.isDirectory(source)) json = small(source.resolve("squid.json"));
            else if (source != null && source.toString().endsWith(".squid")) json = fromZip(source, true);
            if (json == null) return "client";
            Object side = Json.object(Json.parse(json)).get("side");
            return side instanceof String s ? s : "client";
        } catch (IOException | RuntimeException e) {
            return "client";
        }
    }

    private static final int MAX_JSON = 1 << 20;

    private static String small(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_JSON) return null;
        return Files.readString(file);
    }

    /** The squid.json in a zip: at the top, or (nested) one folder down, as a .squid zipped by hand has it. */
    private static String fromZip(Path file, boolean nested) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName().replace('\\', '/');
                boolean found = name.equals("squid.json") || (nested && name.endsWith("/squid.json") && name.indexOf('/') == name.length() - 11);
                if (!found) continue;
                try (java.io.InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = in.readNBytes(MAX_JSON + 1);
                    return bytes.length > MAX_JSON ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /** Where a mod built from code came from: its project folder, .squid or .java file. Null for jars, or if unknown. */
    static Path sourceOf(ModInfo mod) {
        SourceMods sources = Main.sourceMods();
        if (sources == null) return null;
        for (java.util.Map.Entry<Path, String> entry : sources.built.entrySet()) {
            if (entry.getValue().equals(mod.id()) && Files.exists(entry.getKey())) return entry.getKey();
        }
        return null;
    }

    /** Whether a mod's build (a folder) or jar has an assets or data folder. */
    static boolean has(Path jar, String folder) {
        try {
            if (Files.isDirectory(jar)) return Files.isDirectory(jar.resolve(folder));
            if (!Files.isRegularFile(jar)) return false;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    if (e.nextElement().getName().startsWith(folder + "/")) return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            // can't read it: no pack
        }
        return false;
    }

    /**
     * Whether a mod's pictures and sounds changed since Minecraft last loaded them (a mod saved with new textures, or
     * added, or turned off), so Squid Mods can reload Minecraft's resources. Only looked at when the mod list changes.
     */
    public static boolean assetsChanged() {
        List<ModInfo> now = Main.mods();
        if (now == checkedMods || loadedAssets == null) return false;
        checkedMods = now;
        String signature = signature(now);
        if (signature.equals(loadedAssets)) return false;
        loadedAssets = signature; // asked once, even if the reload goes wrong
        return true;
    }

    /**
     * Every mod's assets, as one text: each file's name, size and time. A mod built from code is looked at where it
     * comes from (its resources folder, or the .squid file's own list), since each new build copies the files again
     * with new times; jars' own lists already carry a check of each file. Nothing is read, so this is quick.
     */
    static String signature(List<ModInfo> mods) {
        StringBuilder b = new StringBuilder();
        for (ModInfo mod : mods) {
            Path jar = mod.jar();
            if (!has(jar, "assets")) continue;
            b.append(mod.id()).append(':');
            Path source = sourceOf(mod);
            try {
                if (source != null && Files.isDirectory(source)) listed(b, source.resolve("resources").resolve("assets"));
                else if (source != null) zipListed(b, source, "resources/assets/");
                else if (Files.isDirectory(jar)) listed(b, jar.resolve("assets"));
                else zipListed(b, jar, "assets/");
            } catch (IOException | UncheckedIOException e) {
                b.append("unreadable");
            }
            b.append('\n');
        }
        return b.toString();
    }

    private static void listed(StringBuilder b, Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return;
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                b.append(folder.relativize(file)).append('=').append(Files.size(file)).append('@')
                        .append(Files.getLastModifiedTime(file).toMillis()).append(';');
            }
        }
    }

    private static void zipListed(StringBuilder b, Path zipFile, String part) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.getName().replace('\\', '/').contains(part)) {
                    b.append(entry.getName()).append('=').append(entry.getSize()).append('@').append(entry.getCrc()).append(';');
                }
            }
        }
    }
}
