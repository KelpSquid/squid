package squidskins;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import squid.api.Squid;
import squid.api.SquidMod;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Squid's skin and cape wardrobe: pick a skin (a file, someone's skin by name, or one you painted), slim or wide
 * arms, and a cape (Kelp, Squid, or your own). Options > Skin Customization has the button.
 * For now only you see them: showing them to other players needs Microsoft sign-in and Squid's own server.
 */
public class Skins implements SquidMod {
    static Wardrobe wardrobe;
    private static final Map<String, Wardrobe.Choice> choices = new HashMap<>(); // by player id, read once
    private static final Map<String, Identifier> textures = new HashMap<>();      // loaded pictures, by file and time
    private static Field optionsList;

    @Override
    public void init(Squid squid) {
        wardrobe = Wardrobe.forThisGame();

        // Your player (and only yours) wears what you picked
        squid.atEnd("net.minecraft.client.player.AbstractClientPlayer", "getSkin", call -> {
            if (call.self() != Minecraft.getInstance().player) return;
            AbstractClientPlayer player = (AbstractClientPlayer) call.self();
            call.setReturnValue(dressed((PlayerSkin) call.returnValue(), id(player.getUUID())));
        });

        // A button for the wardrobe in Options > Skin Customization
        squid.atEnd("net.minecraft.client.gui.screens.options.SkinCustomizationScreen", "addOptions", call -> {
            Screen screen = (Screen) call.self();
            Button button = Button.builder(Component.literal("Squid Skin & Cape..."),
                    b -> Minecraft.getInstance().setScreenAndShow(new WardrobeScreen(screen))).width(310).build();
            list((OptionsSubScreen) screen).addBig(button);
        });
    }

    private static OptionsList list(OptionsSubScreen screen) {
        try {
            if (optionsList == null) {
                optionsList = OptionsSubScreen.class.getDeclaredField("list");
                optionsList.setAccessible(true);
            }
            return (OptionsList) optionsList.get(screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Couldn't add the wardrobe button", e);
        }
    }

    static String id(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    /** The player's id from their sign-in, which works on the title screen too (when there's no player yet). */
    static String myId() {
        return id(Minecraft.getInstance().getUser().getProfileId());
    }

    static synchronized Wardrobe.Choice choice(String playerId) {
        return choices.computeIfAbsent(playerId, wardrobe::choice);
    }

    static synchronized void choose(String playerId, Wardrobe.Choice choice) throws java.io.IOException {
        wardrobe.choose(playerId, choice);
        choices.put(playerId, choice);
    }

    /** What you look like right now, for the preview: your own skin with your picks on top. */
    static PlayerSkin preview() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) return minecraft.player.getSkin(); // already dressed by the hook above
        return dressed(DefaultPlayerSkin.get(minecraft.getUser().getProfileId()), myId());
    }

    /** The skin with this player's picks put on it. Anything that can't be loaded is just left as it was. */
    static PlayerSkin dressed(PlayerSkin base, String playerId) {
        Wardrobe.Choice choice = choice(playerId);
        if (choice.equals(Wardrobe.Choice.NONE)) return base;
        Optional<ClientAsset.ResourceTexture> body = Optional.empty();
        Optional<PlayerModelType> model = Optional.empty();
        if (!choice.skin().isEmpty()) {
            Identifier skin = picture(wardrobe.skins().resolve(choice.skin()));
            if (skin != null) {
                body = Optional.of(new ClientAsset.ResourceTexture(skin, skin));
                model = Optional.of(choice.slim() ? PlayerModelType.SLIM : PlayerModelType.WIDE);
            }
        } else if (choice.slim()) {
            model = Optional.of(PlayerModelType.SLIM);
        }
        Optional<ClientAsset.ResourceTexture> cape = Optional.empty();
        Identifier capeTexture = cape(choice.cape());
        if (capeTexture != null) cape = Optional.of(new ClientAsset.ResourceTexture(capeTexture, capeTexture));
        return base.with(PlayerSkin.Patch.create(body, cape, cape, model)); // elytra wear the cape's picture too
    }

    /** A built-in cape ("kelp", "squid") or one from the capes folder ("file:name.png"). Null for none. */
    static Identifier cape(String cape) {
        if (cape.isEmpty()) return null;
        if (cape.startsWith("file:")) return picture(wardrobe.capes().resolve(cape.substring(5)));
        if (!Wardrobe.BUILT_IN_CAPES.contains(cape)) return null;
        return load("builtin:" + cape, () -> Skins.class.getResourceAsStream("/squidskins/capes/" + cape + ".png"));
    }

    /** A picture file as a texture the game can draw, loaded again only when the file changes. */
    static Identifier picture(Path file) {
        try {
            if (!Files.exists(file)) return null;
            String key = file.toAbsolutePath() + "@" + Files.getLastModifiedTime(file).toMillis();
            return load(key, () -> Files.newInputStream(file));
        } catch (Exception e) {
            return null;
        }
    }

    private interface Opener {
        InputStream open() throws Exception;
    }

    private static synchronized Identifier load(String key, Opener opener) {
        Identifier known = textures.get(key);
        if (known != null) return known;
        try (InputStream in = opener.open()) {
            if (in == null) return null;
            NativeImage image = NativeImage.read(in);
            Identifier id = Identifier.fromNamespaceAndPath("squid", "wardrobe/" + Integer.toHexString(key.hashCode()) + "_" + textures.size());
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Squid wardrobe picture", image));
            textures.put(key, id);
            return id;
        } catch (Exception e) {
            System.out.println("[Squid Skins] Couldn't load a picture: " + e.getMessage());
            return null;
        }
    }
}
