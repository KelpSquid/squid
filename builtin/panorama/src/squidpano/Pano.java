package squidpano;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Panorama;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import squid.Lang;
import squid.api.Squid;
import squid.api.SquidMod;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Your own spinning title-screen background. When Minecraft loads its panorama's six pictures, Squid hands it yours
 * instead (if one is picked), and it spins at your speed and in your direction. A Pano button sits next to Mods on the
 * title screen and the pause menu; from a world, Capture Here snaps the six directions where you stand.
 */
public class Pano implements SquidMod {
    static PanoStore store;
    private static Method addWidget;
    private static Field spin;
    private static final ThreadLocal<Float> spinBefore = new ThreadLocal<>();
    static volatile boolean captureNextTick;
    static volatile String lastMessage;

    @Override
    public void init(Squid squid) {
        store = PanoStore.forThisGame();

        // Minecraft reads panorama_0.png to panorama_5.png; a picked panorama's pictures go in their place
        squid.atStart("net.minecraft.client.renderer.texture.TextureContents", "load", call -> {
            Identifier id = (Identifier) call.args()[1];
            String path = id.getPath();
            if (!path.startsWith("textures/gui/title/background/panorama_") || !path.endsWith(".png")) return;
            String active = store.active();
            if (active == null) return;
            try {
                int side = Integer.parseInt(path.substring(path.lastIndexOf('_') + 1, path.length() - 4));
                Path picture = store.picture(active, side);
                try (InputStream in = Files.newInputStream(picture)) {
                    call.cancel(new TextureContents(NativeImage.read(in), null));
                }
            } catch (Exception e) {
                System.out.println("[Squid] Couldn't use the panorama " + active + ": " + e.getMessage());
            }
        });

        // Spinning: Minecraft turns it a little every frame; Squid scales that by your speed, and can turn it around
        squid.atStart("net.minecraft.client.renderer.Panorama", "extractRenderState", call -> spinBefore.set(spinOf((Panorama) call.self())));
        squid.atEnd("net.minecraft.client.renderer.Panorama", "extractRenderState", call -> {
            Float before = spinBefore.get();
            if (before == null) return;
            Panorama panorama = (Panorama) call.self();
            float turned = PanoStore.wrap(spinOf(panorama) - before);
            float changed = (float) (turned * store.speed() * (store.reversed() ? -1 : 1));
            setSpin(panorama, PanoStore.wrap(before + changed));
        });

        // The Pano button, next to Squid's Mods button
        squid.atEnd("net.minecraft.client.gui.screens.TitleScreen", "init", "()V", call -> addButton((Screen) call.self()));
        squid.atEnd("net.minecraft.client.gui.screens.PauseScreen", "init", "()V", call -> addButton((Screen) call.self()));

        // Capturing happens on the tick after the menu closes, so the pictures show the world and not the menu
        squid.onTick(() -> {
            if (!captureNextTick) return;
            captureNextTick = false;
            capture();
        });
    }

    /** Snaps the six directions where the player stands into a new panorama, and uses it. */
    static void capture() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            Path temp = Files.createTempDirectory("squid-panorama");
            minecraft.grabPanoramixScreenshot(temp.toFile());
            Thread keeper = new Thread(() -> {
                try {
                    // Minecraft saves the pictures in the background: wait for all six
                    for (int tries = 0; tries < 100; tries++) {
                        boolean all = true;
                        for (int side = 0; side < 6; side++) all &= Files.exists(temp.resolve("screenshots").resolve("panorama_" + side + ".png"));
                        if (all) break;
                        Thread.sleep(100);
                    }
                    Thread.sleep(300);
                    String name = store.keep(temp);
                    store.setActive(name);
                    minecraft.execute(() -> {
                        apply();
                        lastMessage = Lang.t("Captured a panorama! It's your title screen now.");
                        squid.api.Game.chat(lastMessage, "GREEN");
                    });
                } catch (Exception e) {
                    lastMessage = Lang.t("Couldn't capture it: {0}", e.getMessage());
                }
            }, "keep panorama");
            keeper.setDaemon(true);
            keeper.start();
        } catch (Exception e) {
            lastMessage = Lang.t("Couldn't capture it: {0}", e.getMessage());
        }
    }

    /** Loads the panorama's pictures again, so a newly picked one shows right away. */
    static void apply() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gameRenderer.registerPanoramaTextures(minecraft.getTextureManager());
    }

    private static void addButton(Screen screen) {
        Button pano = Button.builder(Component.literal(Lang.t("Pano")),
                button -> Minecraft.getInstance().setScreenAndShow(new PanoScreen(screen))).bounds(68, 4, 50, 20).build();
        try {
            if (addWidget == null) {
                addWidget = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
                addWidget.setAccessible(true);
            }
            addWidget.invoke(screen, pano);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Couldn't add the Pano button"), e);
        }
    }

    private static float spinOf(Panorama panorama) {
        try {
            if (spin == null) {
                spin = Panorama.class.getDeclaredField("spin");
                spin.setAccessible(true);
            }
            return spin.getFloat(panorama);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setSpin(Panorama panorama, float value) {
        try {
            spin.setFloat(panorama, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
