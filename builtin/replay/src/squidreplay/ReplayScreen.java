package squidreplay;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import squid.Lang;
import squid.Main;
import squidclips.AviWriter;
import squidclips.ClipBuffer;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The replay editor, Skate 3 style. Along the bottom: a timeline to scrub (with your keyframes on it and the trimmed
 * part greyed out), play and pause, speed, forward or backward. Four cameras: Free (fly it with WASD, Q/E for down
 * and up, drag to turn), Follow (rides behind someone; drag to circle them, scroll to zoom), Tripod (stays put and
 * turns to keep them in shot) and Path (glides through your keyframes). Lens sets how wide the camera sees.
 * Export plays the trimmed part once with nothing drawn over it and saves it as a video in Kelp's Gallery.
 * Singleplayer pauses while it's open.
 */
final class ReplayScreen extends Screen {
    private static final float[] SPEEDS = {0.1f, 0.25f, 0.5f, 1, 2, 4};
    private static final float[] LENSES = {0, 30, 50, 70, 90, 110}; // 0: the player's own field of view

    private enum Mode {
        FREE("Free"), FOLLOW("Follow"), TRIPOD("Tripod"), PATH("Path");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private final Playback playback;
    private final CameraPath path = new CameraPath();
    private double time;
    private double in;
    private double out;
    private boolean playing = true;
    private boolean backward;
    private int speed = 3; // 1x
    private int lens;
    private Mode mode = Mode.FREE;
    private int target = -1; // the recorded id of who the camera follows or watches
    private double flySpeed = 8; // blocks a second
    private double followDistance = 4;
    private float orbitYaw;
    private float orbitPitch = 15;
    private long lastFrame = System.nanoTime();
    private boolean scrubbing;
    private String note;
    private long noteUntil;
    private Button playButton;
    private Button speedButton;
    private Button directionButton;
    private Button modeButton;
    private Button targetButton;
    private Button lensButton;

    // Exporting: every video frame is shown, drawn once, then copied, so each picture is exactly that moment
    private static final int EXPORT_FPS = 30;
    private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Squid replay export");
        thread.setDaemon(true);
        return thread;
    });
    private boolean exporting;
    private boolean captureNext;
    private double exportTime;
    private int exportIndex;
    private int exportWidth;
    private int exportHeight;
    private final TreeMap<Integer, byte[]> exportFrames = new TreeMap<>();
    private final AtomicInteger exportPending = new AtomicInteger();

    ReplayScreen(Playback playback) {
        super(Component.literal(Lang.t("Replay")));
        this.playback = playback;
        out = end();
        List<Timeline.Thing> start = playback.things(0);
        for (Timeline.Thing thing : start) {
            if (thing.profile != null) {
                target = thing.id; // the camera starts on a player (usually you)
                break;
            }
        }
    }

    @Override
    protected void init() {
        int x = width / 2 - 195;
        int top = height - 74;
        modeButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
            if (mode == Mode.PATH && path.size() < 2) say(Lang.t("Add at least 2 keyframes for a camera path."));
            updateLabels();
        }).bounds(x, top, 110, 20).build());
        targetButton = addRenderableWidget(Button.builder(Component.empty(), b -> nextTarget()).bounds(x + 114, top, 160, 20).build());
        lensButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            lens = (lens + 1) % LENSES.length;
            playback.fov = LENSES[lens];
            updateLabels();
        }).bounds(x + 278, top, 112, 20).build());

        int y = height - 50;
        addRenderableWidget(Button.builder(Component.literal("|<"), b -> {
            time = in;
            playing = true;
            backward = false;
            updateLabels();
        }).bounds(x, y, 20, 20).build());
        playButton = addRenderableWidget(Button.builder(Component.empty(), b -> togglePlay()).bounds(x + 24, y, 50, 20).build());
        speedButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            speed = (speed + 1) % SPEEDS.length;
            updateLabels();
        }).bounds(x + 78, y, 66, 20).build());
        directionButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            backward = !backward;
            playing = true;
            updateLabels();
        }).bounds(x + 148, y, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("+Key")), b -> addKeyframe()).bounds(x + 212, y, 36, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("-Key")), b -> {
            if (path.removeNear(time)) say(Lang.t("Keyframe removed."));
        }).bounds(x + 252, y, 36, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("In")), b -> {
            in = Math.min(time, out - 1);
            say(Lang.t("The replay now starts here."));
        }).bounds(x + 292, y, 26, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Out")), b -> {
            out = Math.max(time, in + 1);
            say(Lang.t("The replay now ends here."));
        }).bounds(x + 322, y, 26, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Exit")), b -> onClose()).bounds(x + 352, y, 38, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Export")), b -> startExport()).bounds(width - 70, 4, 66, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Save")), b -> save()).bounds(width - 140, 4, 66, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Saved...")), b -> {
            keepPlaying();
            minecraft.setScreenAndShow(new SavedReplaysScreen(this));
        })
                .bounds(width - 210, 4, 66, 20).build());
        updateLabels();
    }

    private void updateLabels() {
        if (playButton == null) return;
        playButton.setMessage(Component.literal(playing ? Lang.t("Pause") : Lang.t("Play")));
        float s = SPEEDS[speed];
        speedButton.setMessage(Component.literal(Lang.t("Speed: {0}x", s == (int) s ? String.valueOf((int) s) : String.valueOf(s))));
        directionButton.setMessage(Component.literal(backward ? Lang.t("Backward") : Lang.t("Forward")));
        modeButton.setMessage(Component.literal(Lang.t("Camera: {0}", Lang.t(mode.label))));
        Timeline.Thing who = targetThing();
        targetButton.setMessage(Component.literal(Lang.t("Target: {0}", who == null ? Lang.t("None") : Playback.name(who))));
        targetButton.active = mode == Mode.FOLLOW || mode == Mode.TRIPOD;
        lensButton.setMessage(Component.literal(LENSES[lens] == 0 ? Lang.t("Lens: Normal") : Lang.t("Lens: {0}°", (int) LENSES[lens])));
    }

    private void say(String text) {
        note = text;
        noteUntil = System.currentTimeMillis() + 2500;
    }

    private void togglePlay() {
        if (!playing && !backward && time >= out) time = in; // at the end, Play starts over
        if (!playing && backward && time <= in) time = out;
        playing = !playing;
        updateLabels();
    }

    private double end() {
        return Math.max(0, playback.recording.length() - 1);
    }

    /** Who the camera follows or watches, where they are right now. */
    private Timeline.Thing targetThing() {
        if (target < 0) return null;
        for (Timeline.Thing thing : playback.things(time)) {
            if (thing.id == target) return thing;
        }
        return null;
    }

    /** The next one to follow: players first, then everything else, nearest the camera first. */
    private void nextTarget() {
        List<Timeline.Thing> things = new java.util.ArrayList<>(playback.things(time));
        things.sort(java.util.Comparator.<Timeline.Thing>comparingInt(t -> t.profile != null ? 0 : 1)
                .thenComparingDouble(t -> sq(t.x - playback.cameraX) + sq(t.y - playback.cameraY) + sq(t.z - playback.cameraZ)));
        if (things.isEmpty()) return;
        int at = -1;
        for (int i = 0; i < things.size(); i++) {
            if (things.get(i).id == target) at = i;
        }
        target = things.get((at + 1) % Math.min(things.size(), 20)).id;
        updateLabels();
    }

    private static double sq(double v) {
        return v * v;
    }

    private void addKeyframe() {
        path.add(new CameraPath.Key(time, playback.cameraX, playback.cameraY, playback.cameraZ, playback.cameraYaw, playback.cameraPitch,
                LENSES[lens] == 0 ? 70 : LENSES[lens]));
        say(Lang.t("Keyframe {0} added.", path.size()));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // no blur or dimming: the world behind is the whole point
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (exporting) {
            exportStep();
            return; // nothing drawn over the picture
        }
        long now = System.nanoTime();
        double seconds = Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (playing && !scrubbing) {
            double before = time;
            time += seconds * 20 * SPEEDS[speed] * (backward ? -1 : 1);
            if (!backward && SPEEDS[speed] >= 0.25f && SPEEDS[speed] <= 2) playback.playSounds(before, Math.min(time, out), SPEEDS[speed]);
            if (!backward) playback.playParticles(before, Math.min(time, out));
            if (time >= out || time <= in) {
                time = Math.max(in, Math.min(out, time));
                playing = false;
                updateLabels();
            }
        }
        aim(seconds);
        playback.show(time);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int left = 10;
        int right = width - 10;
        int top = height - 22;
        double length = Math.max(1, end());
        g.fill(left, top, right, top + 8, 0xA0000000);
        int inX = left + (int) Math.round((right - left) * in / length);
        int outX = left + (int) Math.round((right - left) * out / length);
        int at = left + (int) Math.round((right - left) * time / length);
        g.fill(inX, top + 2, at, top + 6, 0xFF55AAFF);
        g.fill(left, top, inX, top + 8, 0xC0303030); // trimmed off
        g.fill(outX, top, right, top + 8, 0xC0303030);
        for (CameraPath.Key key : path.keys()) {
            int kx = left + (int) Math.round((right - left) * key.time() / length);
            g.fill(kx - 1, top - 3, kx + 1, top + 11, 0xFFFFD040);
        }
        g.fill(at - 1, top - 2, at + 2, top + 10, 0xFFFFFFFF);
        g.text(font, clock(time) + " / " + clock(end()), left, top - 12, 0xFFFFFFFF, true);
        g.text(font, help(), left, 8, 0xFFE0E0E0, true);
        if (note != null && System.currentTimeMillis() < noteUntil) g.centeredText(font, note, width / 2, 24, 0xFFFFFF55);
    }

    /** Saves the trimmed part to the replays folder, to open again later in this world. */
    private void save() {
        int first = (int) Math.floor(in);
        int last = (int) Math.ceil(out);
        Thread saver = new Thread(() -> {
            try {
                java.nio.file.Path file = ReplayFile.save(minecraft, playback.recording, first, last);
                minecraft.execute(() -> say(Lang.t("Saved as {0}", file.getFileName())));
            } catch (Exception e) {
                minecraft.execute(() -> say(Lang.t("Couldn't save it: {0}", e.getMessage())));
            }
        }, "Squid replay save");
        saver.setDaemon(true);
        saver.start();
    }

    /** Whether this editor is still the one playing (a saved replay opened from it takes over). */
    Playback playback() {
        return playback;
    }

    // ---- Export ----

    private void startExport() {
        var target = minecraft.gameRenderer.mainRenderTarget();
        exportWidth = Math.min(1280, target.width / 2 * 2);
        exportHeight = Math.max(2, (int) Math.round(exportWidth * (double) target.height / target.width) / 2 * 2);
        synchronized (exportFrames) {
            exportFrames.clear();
        }
        exportIndex = 0;
        exportTime = in;
        captureNext = false;
        playing = false;
        exporting = true;
        clearWidgets();
    }

    /** One step of exporting: show the next moment, or (a frame later, once it's drawn) copy the picture. */
    private void exportStep() {
        if (!captureNext) {
            aim(1.0 / EXPORT_FPS);
            playback.playParticles(exportTime - 20.0 / EXPORT_FPS * SPEEDS[speed], exportTime);
            playback.show(exportTime);
            captureNext = true;
            return;
        }
        captureNext = false;
        capture(exportIndex++);
        exportTime += 20.0 / EXPORT_FPS * SPEEDS[speed]; // slow motion exports as slow motion
        if (exportTime > out) finishExport();
    }

    private void capture(int index) {
        int width = exportWidth;
        int height = exportHeight;
        exportPending.incrementAndGet();
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), 1, image -> {
            int[] pixels;
            int w;
            int h;
            try (NativeImage picture = image) {
                w = picture.getWidth();
                h = picture.getHeight();
                pixels = picture.getPixels();
            } catch (RuntimeException e) {
                exportPending.decrementAndGet();
                return;
            }
            ENCODER.execute(() -> {
                try {
                    BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    frame.setRGB(0, 0, w, h, pixels, 0, w);
                    byte[] jpeg = ClipBuffer.jpeg(ClipBuffer.fit(frame, width, height), 0.85f);
                    synchronized (exportFrames) {
                        exportFrames.put(index, jpeg);
                    }
                } catch (Exception e) {
                    // a lost picture: the video skips it
                } finally {
                    exportPending.decrementAndGet();
                }
            });
        });
    }

    /** Writes the video once every picture is ready, in the background, and says where it went. */
    private void finishExport() {
        exporting = false;
        rebuildWidgets();
        say(Lang.t("Saving the video..."));
        String name = "replay-" + DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now());
        Path folder = Main.gameFolder().resolve("clips");
        int width = exportWidth;
        int height = exportHeight;
        Thread writer = new Thread(() -> {
            try {
                while (exportPending.get() > 0) Thread.sleep(20);
                List<byte[]> frames;
                synchronized (exportFrames) {
                    frames = new ArrayList<>(exportFrames.values());
                }
                if (frames.size() < 2) throw new IllegalStateException(Lang.t("it's too short"));
                AviWriter.write(frames, width, height, EXPORT_FPS, folder.resolve(name + ".avi"));
                Files.write(folder.resolve(name + ".jpg"), frames.get(frames.size() / 2)); // the picture Kelp's Gallery shows
                minecraft.execute(() -> say(Lang.t("Saved the video! It's in Kelp's Gallery.")));
            } catch (Exception e) {
                minecraft.execute(() -> say(Lang.t("Couldn't save the video: {0}", e.getMessage())));
            }
        }, "Squid replay video");
        writer.setDaemon(true);
        writer.start();
    }

    private String help() {
        return switch (mode) {
            case FREE -> Lang.t("WASD to fly, Q/E down and up, drag to look, Space to play or pause");
            case FOLLOW -> Lang.t("Drag to circle around, scroll to zoom, Space to play or pause");
            case TRIPOD -> Lang.t("The camera stays put and watches. Fly it in Free first.");
            case PATH -> Lang.t("The camera glides through your keyframes.");
        };
    }

    /** A time in ticks as minutes:seconds.tenths. */
    private static String clock(double ticks) {
        double seconds = ticks / 20;
        int minutes = (int) (seconds / 60);
        double rest = seconds - minutes * 60;
        return minutes + ":" + (rest < 10 ? "0" : "") + String.format(java.util.Locale.ROOT, "%.1f", rest);
    }

    /** Puts the camera where its mode says. */
    private void aim(double seconds) {
        Timeline.Thing who = targetThing();
        switch (mode) {
            case FREE -> fly(seconds);
            case FOLLOW -> {
                if (who == null) {
                    fly(seconds);
                    return;
                }
                double eye = who.y + playback.eyeHeight(who);
                double yaw = Math.toRadians(who.yRot + orbitYaw);
                double pitch = Math.toRadians(orbitPitch);
                playback.cameraX = who.x + Math.sin(yaw) * Math.cos(pitch) * followDistance;
                playback.cameraY = eye + Math.sin(pitch) * followDistance;
                playback.cameraZ = who.z - Math.cos(yaw) * Math.cos(pitch) * followDistance;
                lookAt(who.x, eye, who.z);
            }
            case TRIPOD -> {
                if (who != null) lookAt(who.x, who.y + playback.eyeHeight(who), who.z);
            }
            case PATH -> {
                if (path.size() == 0) {
                    fly(seconds);
                    return;
                }
                CameraPath.Key key = path.at(time);
                playback.cameraX = key.x();
                playback.cameraY = key.y();
                playback.cameraZ = key.z();
                playback.cameraYaw = key.yaw();
                playback.cameraPitch = key.pitch();
                playback.fov = key.fov();
            }
        }
    }

    private void lookAt(double x, double y, double z) {
        float[] angles = CameraPath.lookAt(playback.cameraX, playback.cameraY, playback.cameraZ, x, y, z);
        playback.cameraYaw = angles[0];
        playback.cameraPitch = angles[1];
    }

    /** Free camera: WASD along where it looks, Q and E straight down and up, Ctrl for faster. */
    private void fly(double seconds) {
        double forward = (down(InputConstants.KEY_W) ? 1 : 0) - (down(InputConstants.KEY_S) ? 1 : 0);
        double strafe = (down(InputConstants.KEY_D) ? 1 : 0) - (down(InputConstants.KEY_A) ? 1 : 0);
        double rise = (down(InputConstants.KEY_E) ? 1 : 0) - (down(InputConstants.KEY_Q) ? 1 : 0);
        if (forward == 0 && strafe == 0 && rise == 0) return;
        double step = flySpeed * seconds * (down(InputConstants.KEY_LCONTROL) ? 3 : 1);
        double yaw = Math.toRadians(playback.cameraYaw);
        double pitch = Math.toRadians(playback.cameraPitch);
        playback.cameraX += (-Math.sin(yaw) * Math.cos(pitch) * forward - Math.cos(yaw) * strafe) * step;
        playback.cameraY += (-Math.sin(pitch) * forward + rise) * step;
        playback.cameraZ += (Math.cos(yaw) * Math.cos(pitch) * forward - Math.sin(yaw) * strafe) * step;
    }

    private static boolean down(int key) {
        return InputConstants.isKeyDown(key);
    }

    private boolean onTimeline(double x, double y) {
        return y >= height - 26 && y <= height - 10 && x >= 8 && x <= width - 8;
    }

    private void scrubTo(double x) {
        double f = (x - 10) / Math.max(1, width - 20);
        time = Math.max(0, Math.min(1, f)) * end();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (onTimeline(event.x(), event.y())) {
            scrubbing = true;
            scrubTo(event.x());
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (scrubbing) {
            scrubTo(event.x());
        } else if (mode == Mode.FOLLOW) {
            orbitYaw += (float) dx * 0.6f;
            orbitPitch = Math.max(-80, Math.min(80, orbitPitch + (float) dy * 0.6f));
        } else if (mode == Mode.FREE) {
            playback.cameraYaw += (float) dx * 0.4f;
            playback.cameraPitch = Math.max(-90, Math.min(90, playback.cameraPitch + (float) dy * 0.4f));
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        scrubbing = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (mode == Mode.FOLLOW) followDistance = Math.max(1.5, Math.min(30, followDistance * (scrollY > 0 ? 0.85 : 1.15)));
        else flySpeed = Math.max(1, Math.min(64, flySpeed * (scrollY > 0 ? 1.25 : 0.8)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (exporting) {
            if (event.key() == InputConstants.KEY_ESCAPE) { // stop exporting, keep editing
                exporting = false;
                rebuildWidgets();
                say(Lang.t("Export stopped."));
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_SPACE) {
            togglePlay();
            return true;
        }
        if (event.key() == InputConstants.KEY_K) {
            addKeyframe();
            return true;
        }
        if (event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT) {
            playing = false;
            time = Math.max(0, Math.min(end(), time + (event.key() == InputConstants.KEY_LEFT ? -1 : 1)));
            updateLabels();
            return true;
        }
        return super.keyPressed(event);
    }

    private boolean switching; // going to the saved-replays list keeps this replay playing

    void keepPlaying() {
        switching = true;
    }

    @Override
    public void removed() {
        if (switching) {
            switching = false;
            return;
        }
        playback.stop();
    }

    @Override
    public void onClose() {
        playback.stop();
        minecraft.setScreenAndShow(null);
    }
}
