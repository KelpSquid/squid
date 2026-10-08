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
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A mod's resources folder working on its own: resources/assets/... is a resource pack while the mod is on (textures,
 * sounds, .sqda, lang, models), and resources/data/... a data pack on every world and server the mod runs on (recipes,
 * loot tables, advancements...). There's nothing to switch on: each mod's pack is "required", like Minecraft's own.
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
        Object selection = Reflect.create(type(base + "PackSelectionConfig", game), true,
                Reflect.getStatic(type(base + "repository.Pack$Position", game), "TOP"), false);
        return Reflect.create(type(base + "repository.Pack", game), location, resources, metadata, selection);
    }

    private static Class<?> type(String name, ClassLoader game) {
        try {
            return Class.forName(name, true, game);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't find Minecraft's {0}", name), e);
        }
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

    /** Every mod's assets, as one text: each file's name and a check of its bytes (a new build copies them, with new times). */
    static String signature(List<ModInfo> mods) {
        StringBuilder b = new StringBuilder();
        for (ModInfo mod : mods) {
            Path jar = mod.jar();
            if (!has(jar, "assets")) continue;
            b.append(mod.id()).append(':');
            try {
                if (Files.isDirectory(jar)) {
                    try (Stream<Path> files = Files.walk(jar.resolve("assets"))) {
                        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                            CRC32 crc = new CRC32();
                            crc.update(Files.readAllBytes(file));
                            b.append(jar.relativize(file)).append('=').append(crc.getValue()).append(';');
                        }
                    }
                } else {
                    try (ZipFile zip = new ZipFile(jar.toFile())) {
                        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                            ZipEntry entry = e.nextElement();
                            if (entry.getName().startsWith("assets/")) b.append(entry.getName()).append('=').append(entry.getCrc()).append(';');
                        }
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                b.append("unreadable");
            }
            b.append('\n');
        }
        return b.toString();
    }
}
