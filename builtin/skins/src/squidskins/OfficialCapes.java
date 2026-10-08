package squidskins;

import squid.Json;
import squid.Lang;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mojang's official capes, for the wardrobe's Official tab. Squid never includes or hosts their pictures: the list
 * (official-capes.json in the squid-store repo) only has links to Mojang's own texture server, and each picture is
 * downloaded from Mojang when someone looks at it, the same place the game gets capes for the players who own them,
 * and kept in Kelp's official-capes folder so it only downloads once.
 * Wearing one you don't own shows a tag next to your name.
 */
public final class OfficialCapes {
    private OfficialCapes() {
    }

    /** The list of links. Tests (or a test list) can point it somewhere else with -Dsquid.officialCapes. */
    public static String list = System.getProperty("squid.officialCapes",
            "https://raw.githubusercontent.com/KelpSquid/squid-store/main/official-capes.json");
    /** Mojang's texture server: the only place official cape pictures are ever loaded from. */
    public static String mojangTextures = "https://textures.minecraft.net/texture/";

    /** One official cape: its name, and its picture's id on Mojang's texture server. */
    public record Cape(String id, String name, String hash) {
        /** How a wardrobe choice names it: "official:" and the picture's id. */
        public String choice() {
            return "official:" + hash;
        }
    }

    /** The capes in a list, keeping only links to Mojang's texture server. */
    public static List<Cape> parse(String json) {
        List<Cape> capes = new ArrayList<>();
        Map<String, Object> all = Json.object(Json.parse(json));
        if (all == null || all.get("capes") == null) return capes;
        for (Object entry : Json.array(all.get("capes"))) {
            Map<String, Object> cape = Json.object(entry);
            if (cape == null || !(cape.get("texture") instanceof String texture) || !(cape.get("name") instanceof String name)) continue;
            String prefix = "https://textures.minecraft.net/texture/";
            String hash = texture.startsWith(prefix) ? texture.substring(prefix.length()) : "";
            if (!hash.matches("[0-9a-f]{40,64}")) continue; // anything that isn't a Mojang texture is left out
            capes.add(new Cape(cape.get("id") instanceof String id ? id : hash, name, hash));
        }
        return capes;
    }

    public static List<Cape> load() throws IOException, InterruptedException {
        HttpResponse<String> response = client().send(request(list), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException(Lang.t("The list of official capes didn't load (error {0}).", response.statusCode()));
        return parse(response.body());
    }

    /** Whether a choice's cape is an official one. */
    public static boolean isOfficial(String cape) {
        return cape.startsWith("official:");
    }

    /** Where an official cape's picture is kept on this computer once it's been loaded from Mojang. */
    public static Path file(Path folder, String hash) {
        return folder.resolve(hash + ".png");
    }

    /**
     * Gets an official cape's picture from Mojang (unless it's here already) and gives back where it is. Anything that
     * isn't a cape picture is thrown away.
     */
    public static Path fetch(Path folder, String hash) throws IOException, InterruptedException {
        if (!hash.matches("[0-9a-f]{40,64}")) throw new IOException(Lang.t("That isn't an official cape."));
        Path file = file(folder, hash);
        if (Files.exists(file)) return file;
        Files.createDirectories(folder);
        Path part = folder.resolve(hash + ".part");
        try {
            HttpResponse<Path> response = client().send(request(mojangTextures + hash), HttpResponse.BodyHandlers.ofFile(part));
            if (response.statusCode() != 200) throw new IOException(Lang.t("Mojang's server didn't send that cape (error {0}).", response.statusCode()));
            BufferedImage image = ImageIO.read(part.toFile());
            if (image == null || CapeEffects.frames(image.getWidth(), image.getHeight()) < 1) throw new IOException(Lang.t("That isn't a cape picture."));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        } catch (java.net.ConnectException | java.net.UnknownHostException e) {
            throw new IOException(Lang.t("No internet connection."));
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "KelpSquid/squid/0.1 (squid@kelplauncher.org)")
                .timeout(Duration.ofSeconds(20)).build();
    }
}
