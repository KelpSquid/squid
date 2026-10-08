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
 * Mojang's official capes, for the wardrobe's Official tab: a slot for every vanilla cape. Squid never includes or
 * hosts their pictures: the list (official-capes.json, in Squid and in the squid-store repo, which can add newer ones)
 * only has links, and a picture is only downloaded when the player presses Download on its slot. Java capes come from
 * Mojang's own texture server, the same place the game gets capes for the players who own them. Capes that server
 * doesn't have (Bedrock and console capes, old holiday capes, skin pack capes) come from the Minecraft Wiki's copy, and
 * each of those has a fingerprint of its pixels in the list, so a different picture is never kept. A downloaded
 * picture is kept in Kelp's official-capes folder so it only downloads once.
 * Wearing one you don't own shows a tag next to your name.
 */
public final class OfficialCapes {
    private OfficialCapes() {
    }

    /** The list of links. Tests (or a test list) can point it somewhere else with -Dsquid.officialCapes. */
    public static String list = System.getProperty("squid.officialCapes",
            "https://raw.githubusercontent.com/KelpSquid/squid-store/main/official-capes.json");
    /** Mojang's texture server, where Java capes are loaded from. */
    public static String mojangTextures = "https://textures.minecraft.net/texture/";
    /** The Minecraft Wiki's pictures, where capes Mojang's Java server doesn't have are loaded from. */
    static final String WIKI_PICTURES = "https://minecraft.wiki/images/";

    /**
     * One official cape: its name, which group of capes it's in, and its picture's id. For a Java cape, the id is the
     * picture's name on Mojang's texture server; for one from the wiki, it's the fingerprint of its pixels (see
     * {@link #pixels}) and url is where it's downloaded from.
     */
    public record Cape(String id, String name, String group, String hash, String url) {
        /** How a wardrobe choice names it: "official:" and the picture's id. */
        public String choice() {
            return "official:" + hash;
        }

        /** Whether it comes from Mojang's own server (not the wiki). */
        public boolean fromMojang() {
            return url == null;
        }
    }

    /** Every cape on a list seen so far, by picture id, so a choice can find where its picture comes from. */
    private static final Map<String, Cape> KNOWN = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The capes in a list. Only links to Mojang's texture server are kept, and links to the Minecraft Wiki's pictures
     * that have a fingerprint of their pixels.
     */
    public static List<Cape> parse(String json) {
        List<Cape> capes = new ArrayList<>();
        Map<String, Object> all = Json.object(Json.parse(json));
        if (all == null || all.get("capes") == null) return capes;
        for (Object entry : Json.array(all.get("capes"))) {
            Map<String, Object> cape = Json.object(entry);
            if (cape == null || !(cape.get("texture") instanceof String texture) || !(cape.get("name") instanceof String name)) continue;
            String prefix = "https://textures.minecraft.net/texture/";
            String hash;
            String url = null;
            if (texture.startsWith(prefix)) {
                hash = texture.substring(prefix.length());
            } else if (texture.startsWith(WIKI_PICTURES) && !texture.contains("..") && cape.get("pixels") instanceof String pixels) {
                hash = pixels;
                url = texture;
            } else {
                continue; // anything else is left out
            }
            if (!hash.matches("[0-9a-f]{40,64}")) continue;
            capes.add(new Cape(cape.get("id") instanceof String id ? id : hash, name, cape.get("group") instanceof String group ? group : "", hash, url));
        }
        return capes;
    }

    private static volatile List<Cape> bundled;

    /** The list that comes with Squid, so every slot is there even without internet. Read once. */
    public static List<Cape> bundled() {
        List<Cape> known = bundled;
        if (known != null) return known;
        try (java.io.InputStream in = OfficialCapes.class.getResourceAsStream("/squidskins/official-capes.json")) {
            known = in == null ? List.of() : List.copyOf(parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        } catch (IOException e) {
            known = List.of();
        }
        bundled = known;
        remember(known);
        return known;
    }

    private static void remember(List<Cape> capes) {
        for (Cape cape : capes) KNOWN.put(cape.hash(), cape);
    }

    /** The official cape a choice names, if it's in the list that comes with Squid. */
    public static Cape named(String choice) {
        for (Cape cape : bundled()) {
            if (cape.choice().equals(choice)) return cape;
        }
        return null;
    }

    /** The list from the Store, which can have capes newer than this Squid. */
    public static List<Cape> load() throws IOException, InterruptedException {
        HttpResponse<String> response = client().send(request(list), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException(Lang.t("The list of official capes didn't load (error {0}).", response.statusCode()));
        List<Cape> capes = parse(response.body());
        remember(capes);
        return capes;
    }

    /** Whether a choice's cape is an official one. */
    public static boolean isOfficial(String cape) {
        return cape.startsWith("official:");
    }

    /** Whether an official cape's picture has been downloaded from Mojang to this computer. */
    public static boolean downloaded(Path folder, String hash) {
        return Files.exists(file(folder, hash));
    }

    /** Where an official cape's picture is kept on this computer once it's been loaded from Mojang. */
    public static Path file(Path folder, String hash) {
        return folder.resolve(hash + ".png");
    }

    /** The official cape with this picture id, from any list seen so far (Squid's own is always read). */
    public static Cape find(String hash) {
        bundled();
        return KNOWN.get(hash);
    }

    /**
     * Gets an official cape's picture (unless it's here already) and gives back where it is: from Mojang, or for a cape
     * Mojang's Java server doesn't have, from the wiki. Anything that isn't a cape picture is thrown away, and so is a
     * wiki picture whose pixels aren't the ones on the list.
     */
    public static Path fetch(Path folder, String hash) throws IOException, InterruptedException {
        if (!hash.matches("[0-9a-f]{40,64}")) throw new IOException(Lang.t("That isn't an official cape."));
        Path file = file(folder, hash);
        if (Files.exists(file)) return file;
        Files.createDirectories(folder);
        Path part = folder.resolve(hash + ".part");
        Cape cape = find(hash);
        boolean fromWiki = cape != null && !cape.fromMojang();
        try {
            HttpResponse<Path> response = client().send(request(fromWiki ? cape.url() : mojangTextures + hash), HttpResponse.BodyHandlers.ofFile(part));
            if (response.statusCode() != 200) {
                throw new IOException(fromWiki ? Lang.t("The Minecraft Wiki didn't send that cape (error {0}).", response.statusCode())
                        : Lang.t("Mojang's server didn't send that cape (error {0}).", response.statusCode()));
            }
            BufferedImage image = ImageIO.read(part.toFile());
            if (image == null || CapeEffects.frames(image.getWidth(), image.getHeight()) < 1) throw new IOException(Lang.t("That isn't a cape picture."));
            if (fromWiki && !pixels(image).equals(hash)) throw new IOException(Lang.t("That picture isn't the cape on the list."));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        } catch (java.net.ConnectException | java.net.UnknownHostException e) {
            throw new IOException(Lang.t("No internet connection."));
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * A fingerprint of a picture's pixels: SHA-1 of its width and height, then each pixel as alpha, red, green, blue
     * (with fully see-through pixels all zero), row by row. The wiki's server re-packs its pictures, so the file
     * changes but the pixels don't.
     */
    static String pixels(BufferedImage image) {
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(8 + 4 * image.getWidth() * image.getHeight());
        bytes.putInt(image.getWidth()).putInt(image.getHeight());
        // Gray pictures are read straight from their pixels: Java's getRGB changes gray shades (treating them as
        // light levels), where Minecraft and everything else use them as they are
        java.awt.image.ColorModel colors = image.getColorModel();
        boolean gray = colors.getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY && colors.getComponentSize(0) == 8;
        java.awt.image.Raster raster = image.getRaster();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb;
                if (gray) {
                    int shade = raster.getSample(x, y, 0);
                    int alpha = raster.getNumBands() > 1 ? raster.getSample(x, y, 1) : 255;
                    argb = alpha << 24 | shade << 16 | shade << 8 | shade;
                } else {
                    argb = image.getRGB(x, y);
                }
                bytes.putInt((argb >>> 24) == 0 ? 0 : argb);
            }
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes.array()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
