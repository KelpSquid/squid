package squidskins;

import squid.Json;
import squid.Lang;
import squid.Main;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The skins and capes you can pick from, and which ones each player picked. Skins live in Kelp's skins folder,
 * capes in its capes folder, and the choices in squid-skins.json, so they're the same in every instance.
 * Nothing here talks to Minecraft, so it can be tested on its own.
 */
public final class Wardrobe {
    /** The two capes that come with Squid. */
    public static final List<String> BUILT_IN_CAPES = List.of("kelp", "squid");

    /**
     * One player's picks. skin is a file name in the skins folder ("" for their own skin). cape is "", "kelp", "squid"
     * "file:name.png" or "official:" and a Mojang cape's id. effects are the cape's effects by id, like "enchanted" or
     * "bubbles" (see {@link CapeEffects}).
     */
    public record Choice(String skin, boolean slim, String cape, List<String> effects) {
        public static final Choice NONE = new Choice("", false, "");

        public Choice {
            effects = List.copyOf(effects);
        }

        public Choice(String skin, boolean slim, String cape) {
            this(skin, slim, cape, List.of());
        }

        /** The same picks with another cape, keeping its effects. */
        public Choice withCape(String newCape) {
            return new Choice(skin, slim, newCape, effects);
        }

        /** The same picks with this effect switched on or off. */
        public Choice toggled(CapeEffects.Effect effect) {
            List<String> changed = new ArrayList<>(effects);
            if (!changed.remove(effect.id())) changed.add(effect.id());
            return new Choice(skin, slim, cape, changed);
        }
    }

    // Mojang's servers. Tests point these at a pretend server.
    public static String mojangProfiles = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    public static String mojangSessions = "https://sessionserver.mojang.com/session/minecraft/profile/";
    public static String skinServer = "https://textures.minecraft.net/";

    private final Path home;

    public Wardrobe(Path home) {
        this.home = home;
    }

    /** Kelp's folder, which Kelp passes as -Dsquid.home (or, without it, two folders up from the instance). */
    public static Wardrobe forThisGame() {
        String home = System.getProperty("squid.home");
        if (home != null) return new Wardrobe(Path.of(home));
        Path game = Main.gameFolder().toAbsolutePath();
        Path instances = game.getParent();
        if (instances != null && instances.getFileName() != null && instances.getFileName().toString().equals("instances")
                && instances.getParent() != null) {
            return new Wardrobe(instances.getParent());
        }
        return new Wardrobe(game);
    }

    public Path skins() {
        return home.resolve("skins");
    }

    public Path capes() {
        return home.resolve("capes");
    }

    /** Official capes' pictures, once they've been loaded from Mojang's server (see {@link OfficialCapes}). */
    public Path officialCapes() {
        return home.resolve("official-capes");
    }

    /** Pictures of Store capes, for looking at before getting them. */
    public Path storePreviews() {
        return home.resolve("store-previews");
    }

    /** The picture files in a folder, sorted by name. */
    public static List<String> pictures(Path folder) {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(folder)) return names;
        try (Stream<Path> files = Files.list(folder)) {
            files.map(f -> f.getFileName().toString()).filter(n -> n.toLowerCase().endsWith(".png")).sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(names::add);
        } catch (IOException e) {
            // can't look in the folder: nothing to pick
        }
        return names;
    }

    // ---- Choices ----

    private Path choicesFile() {
        return home.resolve("squid-skins.json");
    }

    public Choice choice(String playerId) {
        Map<String, Object> player = Json.object(loadChoices().get(playerId));
        if (player == null) return Choice.NONE;
        List<String> effects = new ArrayList<>();
        if (player.get("effects") != null) {
            for (Object effect : Json.array(player.get("effects"))) effects.add(String.valueOf(effect));
        }
        return new Choice(player.get("skin") instanceof String s ? s : "", Boolean.TRUE.equals(player.get("slim")),
                player.get("cape") instanceof String c ? c : "", effects);
    }

    public void choose(String playerId, Choice choice) throws IOException {
        Map<String, Object> all = loadChoices();
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("skin", choice.skin());
        player.put("slim", choice.slim());
        player.put("cape", choice.cape());
        player.put("effects", choice.effects());
        all.put(playerId, player);
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : all.entrySet()) {
            Map<String, Object> p = Json.object(entry.getValue());
            json.append(first ? "\n" : ",\n").append("    ").append(quote(entry.getKey())).append(": {\"skin\": ")
                    .append(quote(String.valueOf(p.get("skin")))).append(", \"slim\": ").append(Boolean.TRUE.equals(p.get("slim")))
                    .append(", \"cape\": ").append(quote(String.valueOf(p.get("cape"))))
                    .append(", \"effects\": [");
            List<String> effects = new ArrayList<>();
            if (p.get("effects") != null) {
                for (Object effect : Json.array(p.get("effects"))) effects.add(quote(String.valueOf(effect)));
            }
            json.append(String.join(", ", effects)).append("]}");
            first = false;
        }
        json.append(first ? "}\n" : "\n}\n");
        Files.createDirectories(home);
        Path part = choicesFile().resolveSibling("squid-skins.json.part");
        Files.writeString(part, json);
        Files.move(part, choicesFile(), StandardCopyOption.REPLACE_EXISTING);
    }

    private Map<String, Object> loadChoices() {
        try {
            if (Files.exists(choicesFile())) {
                Map<String, Object> all = Json.object(Json.parse(Files.readString(choicesFile())));
                if (all != null) return new LinkedHashMap<>(all);
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[Squid Skins] Couldn't read " + choicesFile() + ", starting fresh: " + e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    // ---- Bringing pictures in ----

    /**
     * What a picture is, by its shape: square ones are skins, twice-as-wide ones are capes, and capes with their frames
     * stacked top to bottom are animated capes.
     */
    public enum Kind { SKIN, CAPE }

    /**
     * Copies a picture into the skins or capes folder, depending on its shape, and gives back its file name there.
     * Anything else is explained instead of copied.
     */
    public String bringIn(Path picture, Kind[] kind) throws IOException {
        BufferedImage image = read(picture);
        Kind is = kindOf(image);
        if (is == null) {
            throw new IOException(Lang.t("That picture is {0}x{1}. Skins are 64x64, capes are 64x32"
                    + " (animated ones stack their frames: 64x96, 64x128...).", image.getWidth(), image.getHeight()));
        }
        kind[0] = is;
        Path folder = is == Kind.SKIN ? skins() : capes();
        Files.createDirectories(folder);
        Path target = freeName(folder, picture.getFileName().toString());
        Files.copy(picture, target);
        return target.getFileName().toString();
    }

    public static Kind kindOf(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w >= 64 && w == h && w % 64 == 0) return Kind.SKIN;
        if (CapeEffects.frames(w, h) >= 1) return Kind.CAPE;
        return null;
    }

    private static BufferedImage read(Path picture) throws IOException {
        BufferedImage image;
        try {
            image = ImageIO.read(picture.toFile());
        } catch (IOException e) {
            image = null;
        }
        if (image == null) throw new IOException(Lang.t("That isn't a picture Squid can read. Use a .png file."));
        return image;
    }

    /** "name.png", or "name (2).png" if that's taken. */
    public static Path freeName(Path folder, String fileName) {
        String clean = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!clean.toLowerCase().endsWith(".png")) clean += ".png";
        String base = clean.substring(0, clean.length() - 4);
        Path target = folder.resolve(clean);
        for (int n = 2; Files.exists(target); n++) target = folder.resolve(base + " (" + n + ").png");
        return target;
    }

    // ---- Getting a player's skin by name ----

    /** A skin that was looked up: saved in the skins folder, plus whether it's made for slim arms. */
    public record Fetched(String file, boolean slim) {
    }

    /**
     * Gets any player's skin by their Minecraft name, from Mojang's own servers (the same place sites like NameMC
     * get them), and saves it in the skins folder as "Name.png". Capes aren't copied: only custom capes are used.
     */
    public Fetched fetchSkin(String name) throws IOException, InterruptedException {
        if (!name.matches("[A-Za-z0-9_]{3,16}")) throw new IOException(Lang.t("Minecraft names are 3-16 letters, numbers or _."));
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
        HttpResponse<String> profile = get(client, mojangProfiles + name);
        if (profile.statusCode() == 404 || profile.statusCode() == 204) throw new IOException(Lang.t("Nobody is called {0}.", name));
        if (profile.statusCode() != 200) throw new IOException(Lang.t("Mojang's servers didn't answer (error {0}).", profile.statusCode()));
        Map<String, Object> who = Json.object(Json.parse(profile.body()));
        String id = (String) who.get("id");
        String realName = who.get("name") instanceof String n ? n : name;

        HttpResponse<String> session = get(client, mojangSessions + id);
        if (session.statusCode() != 200) throw new IOException(Lang.t("Mojang's servers didn't answer (error {0}).", session.statusCode()));
        String texturesValue = null;
        for (Object property : Json.array(Json.object(Json.parse(session.body())).get("properties"))) {
            Map<String, Object> p = Json.object(property);
            if ("textures".equals(p.get("name"))) texturesValue = (String) p.get("value");
        }
        if (texturesValue == null) throw new IOException(Lang.t("{0} doesn't have a skin.", realName));
        Map<String, Object> textures = Json.object(Json.object(Json.parse(
                new String(Base64.getDecoder().decode(texturesValue), StandardCharsets.UTF_8))).get("textures"));
        Map<String, Object> skin = textures == null ? null : Json.object(textures.get("SKIN"));
        if (skin == null) throw new IOException(Lang.t("{0} uses a default skin, so there's nothing to copy.", realName));
        String url = ((String) skin.get("url")).replaceFirst("^http://textures\\.minecraft\\.net/","https://textures.minecraft.net/");
        if (!url.startsWith(skinServer)) throw new IOException(Lang.t("That skin isn't on Mojang's skin server."));
        Map<String, Object> metadata = Json.object(skin.get("metadata"));
        boolean slim = metadata != null && "slim".equals(metadata.get("model"));

        Files.createDirectories(skins());
        Path target = skins().resolve(realName + ".png"); // looking someone up again gets their newest skin
        Path part = skins().resolve(realName + ".png.part");
        HttpResponse<Path> download = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofFile(part));
        try {
            if (download.statusCode() != 200) throw new IOException(Lang.t("The skin didn't download (error {0}).", download.statusCode()));
            if (kindOf(read(part)) != Kind.SKIN) throw new IOException(Lang.t("That skin is an old shape Squid can't use."));
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
        return new Fetched(target.getFileName().toString(), slim);
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws IOException, InterruptedException {
        try {
            return client.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "KelpSquid/squid/0.1 (squid@kelplauncher.org)")
                    .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException | java.net.UnknownHostException e) {
            throw new IOException(Lang.t("No internet connection."));
        }
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
