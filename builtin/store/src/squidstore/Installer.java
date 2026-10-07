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
import java.util.HexFormat;

/** Puts store items where the game finds them: mods in mods/, resource packs in resourcepacks/. */
public final class Installer {
    private Installer() {
    }

    /** Where the item goes in this game folder. */
    public static Path target(Catalog.Item item, Path gameFolder) {
        return gameFolder.resolve(item.isMod() ? "mods" : "resourcepacks").resolve(item.file());
    }

    /** Whether it's already there, on or off. */
    public static boolean installed(Catalog.Item item, Path gameFolder) {
        Path file = target(item, gameFolder);
        return Files.exists(file) || Files.exists(file.resolveSibling(file.getFileName() + ".disabled"));
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
