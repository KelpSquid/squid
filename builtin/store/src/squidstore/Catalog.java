package squidstore;

import squid.Json;
import squid.Lang;
import squid.Signatures;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The store's list of everything Samuel has approved, read from store.json. Each item is a mod, a resource pack
 * or a cape (there's no skin store: skins are your own). The list has to be signed by Kelp & Squid (store.json.sig,
 * next to it; see {@link Signatures}), or nothing in it is read.
 *
 * <pre>
 * {"items": [{"id": "xray", "type": "mod", "name": "X-Ray", "version": "1.0.0", "author": "Samuel", "description": "...",
 *             "minecraft": ["26.3.x"], "devPicked": true, "file": "xray-1.0.0.jar",
 *             "url": "https://...", "sha256": "...", "size": 3558, "icon": "https://.../icons/xray.png"}]}
 * </pre>
 */
public final class Catalog {
    /** Where the list lives. -Dsquid.store=... points the store somewhere else, like a test list. */
    public static final String URL = System.getProperty("squid.store",
            "https://raw.githubusercontent.com/KelpSquid/squid-store/main/store.json");

    static final String USER_AGENT = "KelpSquid/squid/0.1 (squid@kelplauncher.org)";

    /** One thing in the store. type is "mod", "resourcepack" or "cape". icon is a link to a small picture, or "". */
    public record Item(String id, String type, String name, String author, String description, List<String> minecraft,
                       boolean devPicked, String file, String url, String sha256, long size, String version, String icon) {
        public boolean isMod() {
            return "mod".equals(type);
        }

        public boolean isCape() {
            return "cape".equals(type);
        }

        /** Whether it works on this Minecraft version. "26.3.x" means 26.3 and its updates; no list means any. */
        public boolean worksOn(String version) {
            if (minecraft.isEmpty() || version == null) return true;
            for (String wanted : minecraft) {
                if (wanted.equals(version)) return true;
                if (wanted.endsWith(".x")) {
                    String series = wanted.substring(0, wanted.length() - 2);
                    if (version.equals(series) || version.startsWith(series + ".")) return true;
                }
            }
            return false;
        }
    }

    private Catalog() {
    }

    /**
     * Downloads the list (or reads it, for a file: link) and its .sig. Unless the list is signed by Kelp & Squid, it
     * throws {@link Signatures.Unsigned} before reading anything in it, so nothing from it can be installed.
     */
    public static List<Item> load(String url) throws IOException, InterruptedException {
        byte[] list = bytes(url);
        byte[] sig;
        try {
            sig = bytes(url + ".sig");
        } catch (Missing e) {
            sig = null;
        }
        Signatures.check(list, sig, Lang.t("The Store list isn't signed by Kelp & Squid, so nothing was installed."));
        return parse(new String(list, StandardCharsets.UTF_8));
    }

    /** Reads store.json. Items that are missing something important, or have an unsafe file name, are left out. */
    public static List<Item> parse(String json) {
        List<Item> items = new ArrayList<>();
        Map<String, Object> root = Json.object(Json.parse(json));
        if (root == null || root.get("items") == null) return items;
        for (Object entry : Json.array(root.get("items"))) {
            Map<String, Object> it = Json.object(entry);
            String type = string(it, "type");
            String file = string(it, "file");
            if (!safeFileName(file, type) || string(it, "url").isEmpty() || string(it, "sha256").isEmpty()) continue;
            List<String> minecraft = new ArrayList<>();
            if (it.get("minecraft") instanceof String one) minecraft.add(one);
            else if (it.get("minecraft") != null) for (Object v : Json.array(it.get("minecraft"))) minecraft.add(String.valueOf(v));
            items.add(new Item(string(it, "id"), type, string(it, "name"), string(it, "author"), string(it, "description"),
                    List.copyOf(minecraft), Boolean.TRUE.equals(it.get("devPicked")), file, string(it, "url"),
                    string(it, "sha256").toLowerCase(), it.get("size") instanceof Double d ? d.longValue() : -1, string(it, "version"),
                    iconLink(string(it, "icon"))));
        }
        return items;
    }

    /**
     * A file name the store may write: just a name (no folders, so it can't land somewhere else on the computer),
     * ending in .jar for a mod, .zip for a resource pack or .png for a cape.
     */
    static boolean safeFileName(String file, String type) {
        if (file.isEmpty() || !file.matches("[A-Za-z0-9._+-]+") || file.startsWith(".")) return false;
        return switch (type) {
            case "mod" -> file.endsWith(".jar") || file.endsWith(".squid");
            case "resourcepack" -> file.endsWith(".zip");
            case "cape" -> file.endsWith(".png");
            default -> false;
        };
    }

    /** An icon's link, if it's one the store may load: https only (a file: link could reach into the computer or network). */
    static String iconLink(String link) {
        return link.startsWith("https://") ? link : "";
    }

    private static String string(Map<String, Object> json, String key) {
        return json.get(key) instanceof String s ? s : "";
    }

    /** A file that isn't there (a 404, or no such file for a file: link). */
    static final class Missing extends IOException {
        Missing(String message) {
            super(message);
        }
    }

    static byte[] bytes(String url) throws IOException, InterruptedException {
        if (url.startsWith("file:")) {
            try (InputStream in = URI.create(url).toURL().openStream()) {
                return in.readAllBytes();
            } catch (NoSuchFileException | java.io.FileNotFoundException e) {
                throw new Missing(e.getMessage());
            }
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) throw new Missing(Lang.t("the store answered with error {0}", 404));
        if (response.statusCode() != 200) throw new IOException(Lang.t("the store answered with error {0}", response.statusCode()));
        return response.body();
    }
}
