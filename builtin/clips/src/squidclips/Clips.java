package squidclips;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import squid.Lang;
import squid.Main;
import squid.api.Game;
import squid.api.KeyBinding;
import squid.api.ModSettings;
import squid.api.Squid;
import squid.api.SquidMod;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Instant replay: while you play, Squid keeps the last 30 seconds (10 to 60, in its settings) as small pictures, and
 * F8 (changeable in Controls) saves them as a video clip in the instance's clips folder, where Kelp's Gallery shows it.
 * Clips have the game's sounds too (not music), mixed by Squid itself, heard from where the camera was.
 */
public class Clips implements SquidMod {
    private final ClipBuffer buffer = new ClipBuffer();
    // Pictures are shrunk and made into JPEGs away from the game's own thread, so the game stays smooth
    private final ExecutorService encoder = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Squid clips");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final AtomicInteger waiting = new AtomicInteger();
    private ModSettings settings;
    private KeyBinding saveKey;

    @Override
    public void init(Squid squid) {
        settings = squid.settings();
        saveKey = squid.addKeyBinding("Save Clip", InputConstants.KEY_F8);
        squid.onTick(this::tick);
        // Every sound (not music or menu clicks), so clips have sound
        squid.atStart("net.minecraft.client.sounds.SoundManager", "play",
                "(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", call -> {
                    if (!on() || Minecraft.getInstance().level == null) return;
                    net.minecraft.client.resources.sounds.SoundInstance sound = (net.minecraft.client.resources.sounds.SoundInstance) call.args()[0];
                    net.minecraft.sounds.SoundSource source = sound.getSource();
                    if (source == net.minecraft.sounds.SoundSource.MUSIC || source == net.minecraft.sounds.SoundSource.UI || sound.isLooping()) return;
                    buffer.addSound(new ClipBuffer.Noise(System.currentTimeMillis(), sound.getIdentifier(), sound.getVolume(), sound.getPitch(),
                            sound.getX(), sound.getY(), sound.getZ(), sound.isRelative(), sound.getAttenuation()));
                });
    }

    private boolean on() {
        return settings.toggle("Instant replay", true);
    }

    private int seconds() {
        return settings.number("Seconds", 30, 10, 60);
    }

    /** The video's width for the chosen quality; its height keeps the 16:9 shape. */
    private int videoWidth() {
        return switch (settings.choice("Quality", "Medium", "Small", "Medium", "Big")) {
            case "Small" -> 640;
            case "Big" -> 1280;
            default -> 960;
        };
    }

    private void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        while (saveKey.pressed()) save();
        if (!on() || minecraft.level == null) {
            if (!on()) buffer.clear();
            return;
        }
        if (waiting.get() > 4) return; // the computer is busy: skip a picture rather than slow the game down
        var target = minecraft.gameRenderer.mainRenderTarget();
        int downscale = target.width % 2 == 0 && target.height % 2 == 0 ? 2 : 1;
        long time = System.currentTimeMillis();
        int width = videoWidth();
        int seconds = seconds();
        var camera = minecraft.gameRenderer.mainCamera();
        double[] pose = {camera.position().x, camera.position().y, camera.position().z, camera.yRot()}; // where the sound is heard from
        waiting.incrementAndGet();
        Screenshot.takeScreenshot(target, downscale, image -> {
            int[] pixels;
            int w;
            int h;
            try (NativeImage picture = image) {
                w = picture.getWidth();
                h = picture.getHeight();
                pixels = picture.getPixels();
            } catch (RuntimeException e) {
                waiting.decrementAndGet();
                return;
            }
            encoder.execute(() -> {
                try {
                    BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    frame.setRGB(0, 0, w, h, pixels, 0, w);
                    buffer.add(ClipBuffer.jpeg(ClipBuffer.fit(frame, width, width * 9 / 16), 0.75f), time, seconds, pose);
                } catch (Exception e) {
                    // one lost picture: the clip just skips it
                } finally {
                    waiting.decrementAndGet();
                }
            });
        });
    }

    /** Writes what's kept as a video, in the background, and says where it went. */
    private void save() {
        ClipBuffer.Snapshot snapshot = buffer.snapshot();
        List<byte[]> frames = new java.util.ArrayList<>();
        for (ClipBuffer.Frame frame : snapshot.frames()) frames.add(frame.jpeg());
        if (frames.size() < 2) {
            Game.chat(on() ? Lang.t("Nothing to save yet. Play a little first!") : Lang.t("Instant replay is off. Turn it on in Squid > Mods > Squid Clips > Settings."), "YELLOW");
            return;
        }
        int fps = buffer.fps();
        int width = videoWidth();
        String name = "clip-" + DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now());
        Path folder = Main.gameFolder().resolve("clips");
        Game.chat(Lang.t("Saving the last {0} seconds...", Math.max(1, frames.size() / fps)), "GRAY");
        encoder.execute(() -> {
            try {
                short[] sound = ClipBuffer.soundTrack(snapshot, fps, (noise, at) -> SoundLibrary.hit(at, noise.id(), noise.volume(), noise.pitch(),
                        noise.x(), noise.y(), noise.z(), noise.relative(), noise.attenuation()));
                AviWriter.write(frames, width, width * 9 / 16, fps, sound, AudioMix.RATE, folder.resolve(name + ".avi"));
                Files.write(folder.resolve(name + ".jpg"), frames.get(frames.size() / 2)); // the picture Kelp's Gallery shows for it
                Minecraft.getInstance().execute(() -> Game.chat(Lang.t("Saved the clip! It's in Kelp's Gallery."), "GREEN"));
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> Game.chat(Lang.t("Couldn't save the clip: {0}", e.getMessage()), "RED"));
            }
        });
    }
}
