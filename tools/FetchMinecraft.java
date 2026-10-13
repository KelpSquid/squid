import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads Minecraft's client jar and libraries from Mojang into Kelp's folders (%APPDATA%\Kelp), the way Kelp
 * does when you press Play. Only for computers without Kelp, like GitHub's test machines:
 *   java tools\FetchMinecraft.java 26.3
 */
public class FetchMinecraft {
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public static void main(String[] args) throws Exception {
        String version = args.length > 0 ? args[0] : "26.3";
        Path kelp = Path.of(System.getenv("APPDATA"), "Kelp");
        String manifest = text("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        Matcher entry = Pattern.compile("\"id\":\\s*\"" + Pattern.quote(version) + "\",[^}]*?\"url\":\\s*\"([^\"]+)\"").matcher(manifest);
        if (!entry.find()) throw new IllegalStateException("Mojang's list has no Minecraft " + version);
        String json = text(entry.group(1));
        Path folder = kelp.resolve("versions").resolve(version);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(version + ".json"), json);

        Matcher client = Pattern.compile("\"client\":\\s*\\{\\s*\"sha1\":\\s*\"[^\"]+\",\\s*\"size\":\\s*\\d+,\\s*\"url\":\\s*\"([^\"]+)\"").matcher(json);
        if (!client.find()) throw new IllegalStateException("No client jar in " + version + ".json");
        download(client.group(1), folder.resolve(version + ".jar"));

        Matcher library = Pattern.compile("\"path\":\\s*\"([^\"]+)\",\\s*\"sha1\":\\s*\"[^\"]+\",\\s*\"size\":\\s*\\d+,\\s*\"url\":\\s*\"([^\"]+)\"").matcher(json);
        int count = 0;
        while (library.find()) {
            download(library.group(2), kelp.resolve("libraries").resolve(library.group(1)));
            count++;
        }
        System.out.println("Downloaded Minecraft " + version + " and " + count + " libraries into " + kelp);
    }

    static String text(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static void download(String url, Path to) throws Exception {
        if (Files.exists(to)) return;
        Files.createDirectories(to.getParent());
        HttpResponse<Path> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(to));
        if (response.statusCode() != 200) throw new IllegalStateException(url + " gave " + response.statusCode());
    }
}
