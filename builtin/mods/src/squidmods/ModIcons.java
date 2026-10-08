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

    /** The mod's icon as a texture, or null if it has none. Call it from the game's own thread. */
    static Identifier of(ModFiles.ModFile mod) {
        String key;
        try {
            key = mod.file() + "|" + Files.getLastModifiedTime(mod.file()).toMillis();
        } catch (IOException e) {
            return null;
        }
        return TEXTURES.computeIfAbsent(key, k -> Optional.ofNullable(load(mod, k))).orElse(null);
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
                ZipEntry json = zip.getEntry("squid.json");
                if (json == null) return null;
                String icon = icon(parse(new String(zip.getInputStream(json).readAllBytes(), StandardCharsets.UTF_8)));
                ZipEntry entry = zip.getEntry(name.endsWith(".squid") ? "resources/" + icon : icon);
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
        return Files.exists(file) ? parse(Files.readString(file, StandardCharsets.UTF_8)) : Map.of();
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
