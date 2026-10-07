package squid;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs a Minecraft server with Squid, so server mods (like Squid's voice chat relay) work on it. Put squid.jar in the
 * server's folder and run:
 *
 *   java -jar squid.jar          (or: java -cp squid.jar squid.ServerLauncher)
 *
 * It downloads Minecraft's server.jar the first time (checking Mojang's fingerprint), unpacks it the way Mojang's own
 * starter does (server.jar holds the real server and its libraries inside it), then starts it through Squid with the
 * mods in the server's mods folder. Anything after the command goes to Minecraft, like nogui.
 */
public final class ServerLauncher {
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private ServerLauncher() {
    }

    public static void main(String[] args) throws Throwable {
        String version = System.getProperty("squid.minecraftVersion", "26.3");
        Path folder = Path.of(".").toAbsolutePath().normalize();
        Path serverJar = folder.resolve("server.jar");
        if (!Files.exists(serverJar)) download(version, serverJar);

        System.out.println("[Squid] Unpacking Minecraft's server");
        List<Path> classpath = new ArrayList<>();
        String mainClass;
        try (ZipFile zip = new ZipFile(serverJar.toFile())) {
            mainClass = text(zip, "META-INF/main-class");
            if (mainClass == null) throw new IOException("server.jar isn't Minecraft's server (no META-INF/main-class)");
            classpath.addAll(unpack(zip, "META-INF/versions.list", "META-INF/versions/", folder.resolve("versions")));
            classpath.addAll(unpack(zip, "META-INF/libraries.list", "META-INF/libraries/", folder.resolve("libraries")));
        }
        StringBuilder cp = new StringBuilder();
        for (Path p : classpath) cp.append(cp.isEmpty() ? "" : java.io.File.pathSeparator).append(p);
        System.setProperty("squid.gameClasspath", cp.toString());
        System.setProperty("squid.mainClass", mainClass.strip());
        System.setProperty("squid.side", "server");
        System.setProperty("squid.minecraftVersion", version);
        Main.main(args);
    }

    /** Unpacks the jars a list names (each line: fingerprint, name, path) into a folder, unless they're there already. */
    private static List<Path> unpack(ZipFile zip, String listName, String inside, Path into) throws IOException {
        List<Path> out = new ArrayList<>();
        String list = text(zip, listName);
        if (list == null) return out;
        for (String line : list.split("\\R")) {
            String[] parts = line.split("\t");
            if (parts.length != 3) continue;
            String path = parts[2];
            if (path.contains("..") || path.startsWith("/") || path.contains(":")) throw new IOException("a bad path in server.jar: " + path);
            Path target = into.resolve(path).normalize();
            if (!target.startsWith(into)) throw new IOException("a bad path in server.jar: " + path);
            if (!Files.exists(target) || !parts[0].equalsIgnoreCase(sha1(target))) {
                ZipEntry entry = zip.getEntry(inside + path);
                if (entry == null) throw new IOException("server.jar is missing " + path);
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            out.add(target);
        }
        return out;
    }

    private static String text(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Downloads Minecraft's server for a version from Mojang, checking its fingerprint. */
    private static void download(String version, Path to) throws IOException, InterruptedException {
        System.out.println("[Squid] Downloading Minecraft " + version + "'s server from Mojang");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        Map<String, Object> manifest = Json.object(Json.parse(get(http, MANIFEST)));
        String url = null;
        for (Object v : Json.array(manifest.get("versions"))) {
            Map<String, Object> m = Json.object(v);
            if (version.equals(m.get("id"))) url = (String) m.get("url");
        }
        if (url == null) throw new IOException("Mojang has no Minecraft " + version);
        Map<String, Object> details = Json.object(Json.parse(get(http, url)));
        Map<String, Object> server = Json.object(Json.object(details.get("downloads")).get("server"));
        if (server == null) throw new IOException("Minecraft " + version + " has no server download");
        Path part = to.resolveSibling("server.jar.part");
        http.send(HttpRequest.newBuilder(URI.create((String) server.get("url"))).build(), HttpResponse.BodyHandlers.ofFile(part));
        if (!String.valueOf(server.get("sha1")).equalsIgnoreCase(sha1(part))) {
            Files.deleteIfExists(part);
            throw new IOException("the server download was damaged; try again");
        }
        Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String get(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("Mojang answered " + r.statusCode() + " for " + url);
        return r.body();
    }

    static String sha1(Path file) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) sha.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
