package squidprofile;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import squid.api.Squid;
import squid.api.SquidMod;
import squidcount.CountFile;
import squidcount.SquidCount;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Your Squid profile, in the game: your emblem (made in {@link EmblemScreen}, Call of Duty style, with tools that
 * unlock as your Squid Count grows) and your badges. Squid is where everything social lives, so this is in the Squid
 * menu, not in Kelp. Emblems and badges are kept in Kelp's folder, shared by every instance.
 */
public class Profile implements SquidMod {
    /** Every badge, in the order they're shown. Badges are handed out by Kelp's server, which proves who earned them. */
    public static final List<String> BADGES = List.of("dev", "beta-tester", "early-player", "github-contributor", "discord-member", "birthday");
    public static final Map<String, String> BADGE_NAMES = Map.of("dev", "Developer", "beta-tester", "Beta Tester", "early-player", "Early Player",
            "github-contributor", "GitHub Contributor", "discord-member", "Discord Member", "birthday", "Kelp Birthday");

    private static final Map<String, Identifier> badgeTextures = new ConcurrentHashMap<>();

    @Override
    public void init(Squid squid) {
        squid.addMenuButton("Emblem", false, menu -> Minecraft.getInstance().setScreenAndShow(new EmblemScreen((Screen) menu)));
    }

    /** Kelp's folder (where the Squid Count is), shared by every instance. */
    static Path home() {
        return Emblem.home();
    }

    /** The player playing now, as Kelp and the Squid Count know them: their id without dashes. */
    static String me() {
        return Minecraft.getInstance().getUser().getProfileId().toString().replace("-", "");
    }

    /** A player's Squid Count. */
    static int points(String id) {
        return CountFile.load(SquidCount.countFile()).player(id).points;
    }

    /** A player's badges, in the order they're shown (none until the server has handed some out). */
    static List<String> badges(String id) {
        Path file = home().resolve("badges").resolve(id.replaceAll("[^A-Za-z0-9_-]", "") + ".txt");
        if (!Files.exists(file)) return List.of();
        List<String> have = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String badge : BADGES) {
                if (lines.stream().anyMatch(l -> l.strip().equals(badge))) have.add(badge);
            }
        } catch (IOException e) {
            return List.of();
        }
        return have;
    }

    /** A badge's picture, loaded once. */
    static Identifier badgeTexture(String badge) {
        return badgeTextures.computeIfAbsent(badge, b -> {
            try (InputStream in = Profile.class.getResourceAsStream("/squidprofile/badges/" + b + ".png")) {
                if (in == null) return null;
                Identifier id = Identifier.fromNamespaceAndPath("squid", "badge/" + b);
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Squid badge", NativeImage.read(in)));
                return id;
            } catch (IOException e) {
                return null;
            }
        });
    }

    /** Copies a drawn emblem into a texture's pixels (both are 64x64) and sends it to the graphics card. */
    static void upload(BufferedImage picture, DynamicTexture texture) {
        NativeImage pixels = texture.getPixels();
        if (pixels == null) return;
        for (int y = 0; y < Emblem.SIZE; y++) {
            for (int x = 0; x < Emblem.SIZE; x++) pixels.setPixel(x, y, picture.getRGB(x, y));
        }
        texture.upload();
    }
}
