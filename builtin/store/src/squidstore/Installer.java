package squidstore;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Puts store items where the game finds them: mods in mods/, resource packs in resourcepacks/. */
public final class Installer {
    private Installer() {
    }

    /** Where the item goes in this game folder. */
    public static Path target(Catalog.Item item, Path gameFolder) {
        return gameFolder.resolve(item.isMod() ? "mods" : "resourcepacks").resolve(item.file());
    }

    /**
     * Whether it's already there, on or off. Mods are recognised by the id in their squid.json, so a mod counts as
     * installed even under another file name (like xray.jar instead of xray-1.0.0.jar).
     */
    public static boolean installed(Catalog.Item item, Path gameFolder) {
        Path file = target(item, gameFolder);
        if (Files.exists(file) || Files.exists(file.resolveSibling(file.getFileName() + ".disabled"))) return true;
        return item.isMod() && !copiesOf(item.id(), gameFolder.resolve("mods")).isEmpty();
    }

    /**
     * Whether the Store has a newer version of a mod than the one installed. Only mods have versions to compare;
     * a mod without a version on either side never asks for an update.
     */
    public static boolean updateAvailable(Catalog.Item item, Path gameFolder) {
        if (!item.isMod() || item.version().isEmpty()) return false;
        for (Path copy : copiesOf(item.id(), gameFolder.resolve("mods"))) {
            String installed = modVersion(copy);
            if (installed != null && !installed.isEmpty() && compare(item.version(), installed) > 0) return true;
        }
        return false;
    }

    /** Compares versions like "1.2.0" and "1.10" number by number. A beta (1.0-beta) comes before its release. */
    public static int compare(String a, String b) {
        String[] x = a.split("[.+-]");
        String[] y = b.split("[.+-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : null;
            String q = i < y.length ? y[i] : null;
            if (p == null) return q.matches("\\d+") && Long.parseLong(q) == 0 ? 0 : q.matches("\\d+") ? -1 : 1;
            if (q == null) return p.matches("\\d+") && Long.parseLong(p) == 0 ? 0 : p.matches("\\d+") ? 1 : -1;
            int c = p.matches("\\d+") && q.matches("\\d+") ? Long.compare(Long.parseLong(p), Long.parseLong(q))
                    : p.matches("\\d+") ? 1 : q.matches("\\d+") ? -1 : p.compareTo(q);
            if (c != 0) return c;
        }
        return 0;
    }

    /** Every jar in the mods folder (on or off) whose squid.json has this id. */
    static List<Path> copiesOf(String id, Path mods) {
        List<Path> copies = new ArrayList<>();
        if (!Files.isDirectory(mods)) return copies;
        try (Stream<Path> files = Files.list(mods)) {
            for (Path jar : files.filter(f -> f.toString().endsWith(".jar") || f.toString().endsWith(".jar.disabled")).toList()) {
                if (id.equals(modId(jar))) copies.add(jar);
            }
        } catch (IOException e) {
            // can't look in the folder: nothing found
        }
        return copies;
    }

    /** The id in a jar's squid.json, or null if it hasn't got one (or can't be read). */
    static String modId(Path jar) {
        return squidJson(jar, "id");
    }

    /** The version in a jar's squid.json, or null. */
    static String modVersion(Path jar) {
        return squidJson(jar, "version");
    }

    private static String squidJson(Path jar, String key) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("squid.json");
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                Map<String, Object> json = squid.Json.object(squid.Json.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                return json != null && json.get(key) instanceof String value ? value : null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Downloads the item and checks its fingerprint (sha256) before it goes in, so a file that was changed
     * or damaged on the way never gets used.
     */
    public static void install(Catalog.Item item, Path gameFolder) throws IOException, InterruptedException {
        Path file = target(item, gameFolder);
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try {
            download(item.url(), part);
            if (item.size() >= 0 && Files.size(part) != item.size()) throw new IOException("it arrived the wrong size");
            if (!sha256(part).equals(item.sha256())) throw new IOException("it arrived damaged, so it wasn't installed");
            // Reinstalling: the old copy of this same mod goes, so there are never two of it
            if (item.isMod()) {
                for (Path old : copiesOf(item.id(), file.getParent())) {
                    if (!old.equals(file)) Files.deleteIfExists(old);
                }
            }
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".disabled"));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static void download(String url, Path to) throws IOException, InterruptedException {
        if (url.startsWith("file:")) {
            try (InputStream in = URI.create(url).toURL().openStream()) {
                Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        try {
            HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", Catalog.USER_AGENT)
                    .timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofFile(to));
            if (response.statusCode() != 200) throw new IOException("the download answered with error " + response.statusCode());
        } catch (ConnectException | UnknownHostException e) {
            throw new IOException("no internet connection");
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
