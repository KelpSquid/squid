package squidmods;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import squid.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mod icons in the Mods screen: the picture a mod's squid.json names ("icon", in resources), or its icon.png. Each
 * becomes a texture once, and again only when its file changes.
 */
final class ModIcons {
    private ModIcons() {
    }

    private static final Map<String, Optional<Identifier>> TEXTURES = new ConcurrentHashMap<>();

    /** What each mod's icon was made from (file, times), so a changed picture is noticed. Checked once a second. */
    private static final Map<Path, String> KEYS = new ConcurrentHashMap<>();
    private static final Map<Path, Long> CHECKED = new ConcurrentHashMap<>();

    /** The mod's icon as a texture, or null if it has none. Call it from the game's own thread. */
    static Identifier of(ModFiles.ModFile mod) {
        Path file = mod.file();
        long now = System.currentTimeMillis();
        String key = KEYS.get(file);
        Long checked = CHECKED.get(file);
        if (key == null || checked == null || now - checked > 1000) {
            String fresh = key(file);
            if (fresh == null) return null;
            if (key != null && !key.equals(fresh)) {
                // The picture changed: the old texture is let go, so they don't pile up
                Optional<Identifier> old = TEXTURES.remove(key);
                if (old != null) old.ifPresent(id -> Minecraft.getInstance().getTextureManager().release(id));
            }
            key = fresh;
            KEYS.put(file, key);
            CHECKED.put(file, now);
        }
        String k = key;
        return TEXTURES.computeIfAbsent(k, x -> Optional.ofNullable(load(mod, k))).orElse(null);
    }

    /** The file's time, and for a project folder its squid.json's and pictures' times (a folder's own time doesn't change). */
    private static String key(Path file) {
        try {
            StringBuilder key = new StringBuilder(file + "|" + Files.getLastModifiedTime(file).toMillis());
            if (Files.isDirectory(file)) {
                Path json = file.resolve("squid.json");
                if (Files.exists(json)) key.append('|').append(Files.getLastModifiedTime(json).toMillis());
                Path resources = file.resolve("resources");
                if (Files.isDirectory(resources)) {
                    try (java.util.stream.Stream<Path> pictures = Files.list(resources)) {
                        for (Path p : pictures.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")).toList()) {
                            key.append('|').append(p.getFileName()).append(Files.getLastModifiedTime(p).toMillis());
                        }
                    }
                }
            }
            return key.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static Identifier load(ModFiles.ModFile mod, String key) {
        byte[] png = read(mod.file());
        if (png == null) return null;
        try {
            NativeImage picture = NativeImage.read(png);
            Identifier id = Identifier.fromNamespaceAndPath("squid", "modicon/" + Integer.toHexString(key.hashCode()) + "_" + TEXTURES.size());
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Squid mod icon", picture));
            return id;
        } catch (IOException | RuntimeException e) {
            return null; // not a PNG Minecraft can read
        }
    }

    /** The icon's bytes, from a project folder, a .squid file or a jar. Null if there's none. */
    static byte[] read(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".disabled")) name = name.substring(0, name.length() - ".disabled".length());
        try {
            if (Files.isDirectory(file)) {
                Path picture = file.resolve("resources").resolve(icon(squidJson(file.resolve("squid.json")))).normalize();
                if (!picture.startsWith(file) || !Files.isRegularFile(picture) || Files.size(picture) > 1 << 20) return null;
                return Files.readAllBytes(picture);
            }
            if (!name.endsWith(".squid") && !name.endsWith(".jar")) return null;
            try (ZipFile zip = new ZipFile(file.toFile())) {
                boolean packed = name.endsWith(".squid");
                String root = packed ? Packed.root(zip) : "";
                ZipEntry json = root == null ? null : packed ? Packed.entry(zip, root + "squid.json") : zip.getEntry("squid.json");
                if (json == null) return null;
                String icon = icon(parse(Packed.text(zip.getInputStream(json).readAllBytes())));
                ZipEntry entry = packed ? Packed.entry(zip, root + "resources/" + icon) : zip.getEntry(icon);
                if (entry == null || entry.getSize() > 1 << 20) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> squidJson(Path file) throws IOException {
        return Files.exists(file) ? parse(Packed.text(Files.readAllBytes(file))) : Map.of();
    }

    private static Map<String, Object> parse(String text) {
        String clean = text.startsWith("﻿") ? text.substring(1) : text;
        Object json = Json.parse(clean);
        return json instanceof Map<?, ?> ? Json.object(json) : Map.of();
    }

    private static String icon(Map<String, Object> json) {
        return json.get("icon") instanceof String icon && !icon.isBlank() ? icon : "icon.png";
    }
}
