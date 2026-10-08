package squidstore;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store items' icons: small pictures downloaded in the background the first time they're shown, then kept as
 * textures. A missing or broken icon just leaves the space empty.
 */
final class StoreIcons {
    private StoreIcons() {
    }

    /** The most an icon can be. They're tiny pictures; anything bigger isn't one. */
    private static final int MOST = 256 * 1024;

    /** Loaded icons. Empty: there isn't one (or it couldn't be loaded). A link missing from here is still loading. */
    private static final Map<String, Optional<Identifier>> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();

    /** The icon's texture, or null while it loads (or if there's none). Call it from the game's own thread. */
    static Identifier of(Catalog.Item item) {
        String link = item.icon();
        if (link.isEmpty()) return null;
        Optional<Identifier> done = LOADED.get(link);
        if (done != null) return done.orElse(null);
        if (LOADING.putIfAbsent(link, true) == null) {
            Thread.ofVirtual().start(() -> {
                byte[] png = download(link);
                // Textures are made on the game's own thread
                Minecraft.getInstance().execute(() -> {
                    LOADED.put(link, Optional.ofNullable(register(link, png)));
                    LOADING.remove(link);
                });
            });
        }
        return null;
    }

    private static byte[] download(String link) {
        try {
            if (link.startsWith("file:")) {
                try (InputStream in = URI.create(link).toURL().openStream()) {
                    return in.readNBytes(MOST + 1);
                }
            }
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(link))
                    .header("User-Agent", Catalog.USER_AGENT).timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) return null;
                return in.readNBytes(MOST + 1);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Identifier register(String link, byte[] png) {
        if (png == null || png.length > MOST) return null;
        try {
            NativeImage picture = NativeImage.read(png);
            Identifier id = Identifier.fromNamespaceAndPath("squid", "storeicon/" + Integer.toHexString(link.hashCode()) + "_" + LOADED.size());
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Squid Store icon", picture));
            return id;
        } catch (Exception e) {
            return null;
        }
    }
}
