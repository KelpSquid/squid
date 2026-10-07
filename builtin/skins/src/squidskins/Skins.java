package squidskins;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import squid.api.Squid;
import squid.api.SquidMod;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Squid's skin and cape wardrobe: pick a skin (a file, someone's skin by name, or one you painted), slim or wide
 * arms, and a cape (Kelp, Squid, or your own, animated or not) with effects: enchanted, glowing, rainbow, or a trail
 * of bubbles, water, fire and more. Options > Skin Customization has the button.
 * For now only you see them: showing them to other players needs Microsoft sign-in and Squid's own server.
 */
public class Skins implements SquidMod {
    static Wardrobe wardrobe;
    private static final Map<String, Wardrobe.Choice> choices = new HashMap<>(); // by player id, read once
    private static final Map<String, Identifier> textures = new HashMap<>();      // loaded pictures, by file and time
    private static Field optionsList;
    private static final Map<Identifier, List<CapeEffects.Effect>> capeEffects = new HashMap<>(); // by cape texture
    private static final List<LiveCape> liveCapes = new ArrayList<>();                           // capes that move
    private static final ThreadLocal<Boolean> redrawing = ThreadLocal.withInitial(() -> false);
    private static final int FULL_BRIGHT = 0xF000F0;

    @Override
    public void init(Squid squid) {
        wardrobe = Wardrobe.forThisGame();

        // Your player (and only yours) wears what you picked
        squid.atEnd("net.minecraft.client.player.AbstractClientPlayer", "getSkin", call -> {
            if (call.self() != Minecraft.getInstance().player) return;
            AbstractClientPlayer player = (AbstractClientPlayer) call.self();
            call.setReturnValue(dressed((PlayerSkin) call.returnValue(), id(player.getUUID())));
        });

        // Enchanted and glowing capes: Minecraft draws the cape again, through a stand-in that swaps in the glint
        // and full brightness. The stand-in passes everything else straight on.
        squid.atStart("net.minecraft.client.renderer.entity.layers.CapeLayer", "submit",
                "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
                        + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", call -> {
                    if (redrawing.get()) return;
                    Object[] args = call.args();
                    AvatarRenderState state = (AvatarRenderState) args[3];
                    ClientAsset.Texture cape = state.skin == null ? null : state.skin.cape();
                    List<CapeEffects.Effect> effects = cape == null ? null : effectsOf(cape.texturePath());
                    if (effects == null) return;
                    boolean glint = effects.contains(CapeEffects.Effect.ENCHANTED);
                    boolean glow = effects.contains(CapeEffects.Effect.GLOW);
                    if (!glint && !glow) return;
                    call.cancel();
                    SubmitNodeCollector special = special((SubmitNodeCollector) args[1], glint ? cape.texturePath() : null, glow);
                    redrawing.set(true);
                    try {
                        ((CapeLayer) call.self()).submit((PoseStack) args[0], special, glow ? FULL_BRIGHT : (Integer) args[2], state,
                                (Float) args[4], (Float) args[5]);
                    } finally {
                        redrawing.set(false);
                    }
                });

        // Animated and rainbow capes are drawn again every tick, and trails leave their particles
        squid.onTick(() -> {
            long now = System.currentTimeMillis();
            for (LiveCape cape : liveCapes()) cape.update(now);
            trails();
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
        Identifier capeTexture = cape(choice);
        if (capeTexture != null) cape = Optional.of(new ClientAsset.ResourceTexture(capeTexture, capeTexture));
        return base.with(PlayerSkin.Patch.create(body, cape, cape, model)); // elytra wear the cape's picture too
    }

    /**
     * The texture for a player's cape: a built-in cape ("kelp", "squid") or one from the capes folder ("file:name.png"),
     * with its effects. Null for none.
     */
    static Identifier cape(Wardrobe.Choice choice) {
        String cape = choice.cape();
        List<CapeEffects.Effect> effects = CapeEffects.parse(choice.effects());
        String key;
        Opener opener;
        try {
            if (cape.isEmpty()) return null;
            if (cape.startsWith("file:")) {
                Path file = wardrobe.capes().resolve(cape.substring(5));
                if (!Files.exists(file)) return null;
                key = file.toAbsolutePath() + "@" + Files.getLastModifiedTime(file).toMillis();
                opener = () -> Files.newInputStream(file);
            } else if (Wardrobe.BUILT_IN_CAPES.contains(cape)) {
                key = "builtin:" + cape;
                opener = () -> Skins.class.getResourceAsStream("/squidskins/capes/" + cape + ".png");
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return loadCape(key + "|" + effects, opener, effects);
    }

    /** A cape picture as a texture. One that moves (animated, or rainbow) becomes a live cape, drawn again every tick. */
    private static synchronized Identifier loadCape(String key, Opener opener, List<CapeEffects.Effect> effects) {
        Identifier known = textures.get(key);
        if (known != null) return known;
        try (InputStream in = opener.open()) {
            if (in == null) return null;
            NativeImage picture = NativeImage.read(in);
            int width = picture.getWidth();
            int frames = CapeEffects.frames(width, picture.getHeight());
            Identifier id = Identifier.fromNamespaceAndPath("squid", "cape/" + Integer.toHexString(key.hashCode()) + "_" + textures.size());
            if (frames == 0 || !CapeEffects.moving(frames, effects)) {
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Squid cape", picture));
            } else {
                int[] pixels = new int[width * picture.getHeight()];
                for (int y = 0; y < picture.getHeight(); y++) {
                    for (int x = 0; x < width; x++) pixels[y * width + x] = picture.getPixel(x, y);
                }
                picture.close();
                LiveCape live = new LiveCape(pixels, width, frames, effects,
                        new DynamicTexture(() -> "Squid moving cape", new NativeImage(width, width / 2, false)));
                live.update(System.currentTimeMillis());
                Minecraft.getInstance().getTextureManager().register(id, live.texture);
                liveCapes.add(live);
            }
            textures.put(key, id);
            capeEffects.put(id, List.copyOf(effects));
            return id;
        } catch (Exception e) {
            System.out.println("[Squid Skins] Couldn't load a cape: " + e.getMessage());
            return null;
        }
    }

    private static synchronized List<CapeEffects.Effect> effectsOf(Identifier cape) {
        return capeEffects.get(cape);
    }

    private static synchronized List<LiveCape> liveCapes() {
        return List.copyOf(liveCapes);
    }

    /** A cape that moves: its whole picture (every frame), and the texture the game draws, painted again each tick. */
    private record LiveCape(int[] picture, int width, int frames, List<CapeEffects.Effect> effects, DynamicTexture texture) {
        void update(long millis) {
            int[] pixels = CapeEffects.frame(picture, width, CapeEffects.frameAt(frames, millis));
            CapeEffects.paint(pixels, width, effects, millis);
            NativeImage image = texture.getPixels();
            if (image == null) return;
            for (int y = 0; y < width / 2; y++) {
                for (int x = 0; x < width; x++) image.setPixel(x, y, pixels[y * width + x]);
            }
            texture.upload();
        }
    }

    /**
     * A stand-in for Minecraft's drawing list that passes everything on, except that the cape gets drawn with the
     * enchantment glint (if glint isn't null) and at full brightness (if glow is on).
     */
    private static SubmitNodeCollector special(SubmitNodeCollector real, Identifier glint, boolean glow) {
        return (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(), new Class<?>[] {SubmitNodeCollector.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("submitModel") && args != null && args.length >= 7 && args[3] instanceof RenderType) {
                        args = args.clone();
                        if (glint != null) args[3] = RenderTypes.entitySolidGlint(glint);
                        if (glow && args[4] instanceof Integer) args[4] = FULL_BRIGHT;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** Leaves each cape's trail behind its player: a few particles from the cape, more when they're moving. */
    private static void trails() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) return;
        for (AbstractClientPlayer player : level.players()) {
            if (player.isInvisible()) continue;
            PlayerSkin skin = player.getSkin();
            ClientAsset.Texture cape = skin == null ? null : skin.cape();
            List<CapeEffects.Effect> effects = cape == null ? null : effectsOf(cape.texturePath());
            if (effects == null) continue;
            boolean moving = player.getDeltaMovement().horizontalDistanceSqr() > 0.001;
            for (CapeEffects.Effect effect : effects) {
                if (!effect.trail) continue;
                int every = effect == CapeEffects.Effect.HEARTS ? 10 : moving ? 1 : 4;
                if ((player.tickCount + effect.ordinal()) % every != 0) continue;
                double yaw = Math.toRadians(player.yBodyRot);
                double x = player.getX() + Math.sin(yaw) * 0.35 + (player.getRandom().nextDouble() - 0.5) * 0.4;
                double z = player.getZ() - Math.cos(yaw) * 0.35 + (player.getRandom().nextDouble() - 0.5) * 0.4;
                double y = player.getY() + 0.5 + player.getRandom().nextDouble() * 0.9;
                switch (effect) {
                    case BUBBLES -> level.addParticle(ParticleTypes.BUBBLE_POP, x, y, z, 0, 0.03, 0);
                    case WATER -> level.addParticle(ParticleTypes.FALLING_WATER, x, y, z, 0, 0, 0);
                    case FIRE -> level.addParticle(moving ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME, x, y, z, 0, 0.01, 0);
                    case SPARKLES -> level.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0.005, 0);
                    case HEARTS -> level.addParticle(ParticleTypes.HEART, x, y + 0.4, z, 0, 0, 0);
                    case SNOW -> level.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, 0, -0.02, 0);
                    default -> {
                    }
                }
            }
        }
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
