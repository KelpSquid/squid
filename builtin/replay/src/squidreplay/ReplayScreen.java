package squidreplay;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import squid.Lang;

/**
 * The replay editor: a timeline to scrub along the bottom, play and pause, speed, forward or backward, and a free
 * camera you fly with WASD (Q and E for down and up) and turn by dragging. Singleplayer pauses while it's open.
 */
final class ReplayScreen extends Screen {
    private static final float[] SPEEDS = {0.1f, 0.25f, 0.5f, 1, 2, 4};

    private final Playback playback;
    private double time;
    private boolean playing = true;
    private boolean backward;
    private int speed = 3; // index in SPEEDS: 1x
    private double flySpeed = 8; // blocks a second
    private long lastFrame = System.nanoTime();
    private boolean scrubbing;
    private Button playButton;
    private Button speedButton;
    private Button directionButton;

    ReplayScreen(Playback playback) {
        super(Component.literal(Lang.t("Replay")));
        this.playback = playback;
    }

    @Override
    protected void init() {
        int y = height - 50;
        int x = width / 2 - 154;
        addRenderableWidget(Button.builder(Component.literal("|<"), b -> {
            time = 0;
            playing = true;
            backward = false;
            updateLabels();
        }).bounds(x, y, 20, 20).build());
        playButton = addRenderableWidget(Button.builder(Component.empty(), b -> togglePlay()).bounds(x + 24, y, 70, 20).build());
        speedButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            speed = (speed + 1) % SPEEDS.length;
            updateLabels();
        }).bounds(x + 98, y, 70, 20).build());
        directionButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            backward = !backward;
            playing = true;
            updateLabels();
        }).bounds(x + 172, y, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Exit")), b -> onClose()).bounds(x + 246, y, 62, 20).build());
        updateLabels();
    }

    private void updateLabels() {
        if (playButton == null) return;
        playButton.setMessage(Component.literal(playing ? Lang.t("Pause") : Lang.t("Play")));
        float s = SPEEDS[speed];
        speedButton.setMessage(Component.literal(Lang.t("Speed: {0}x", s == (int) s ? String.valueOf((int) s) : String.valueOf(s))));
        directionButton.setMessage(Component.literal(backward ? Lang.t("Backward") : Lang.t("Forward")));
    }

    private void togglePlay() {
        if (!playing && !backward && time >= end()) time = 0; // at the end, Play starts over
        if (!playing && backward && time <= 0) time = end();
        playing = !playing;
        updateLabels();
    }

    private double end() {
        return Math.max(0, playback.recording.length() - 1);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // no blur or dimming: the world behind is the whole point
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        double seconds = Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (playing && !scrubbing) {
            time += seconds * 20 * SPEEDS[speed] * (backward ? -1 : 1);
            if (time >= end() || time <= 0) {
                time = Math.max(0, Math.min(end(), time));
                playing = false;
                updateLabels();
            }
        }
        fly(seconds);
        playback.show(time);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
        // The timeline: how far along, and where you are
        int left = 10;
        int right = width - 10;
        int top = height - 22;
        g.fill(left, top, right, top + 8, 0xA0000000);
        int at = left + (int) Math.round((right - left) * (end() == 0 ? 0 : time / end()));
        g.fill(left, top + 2, at, top + 6, 0xFF55AAFF);
        g.fill(at - 1, top - 2, at + 2, top + 10, 0xFFFFFFFF);
        g.text(font, clock(time) + " / " + clock(end()), left, top - 12, 0xFFFFFFFF, true);
        g.text(font, Lang.t("WASD to fly, Q/E down and up, drag to look, Space to play or pause"), left, 8, 0xFFE0E0E0, true);
    }

    /** A time in ticks as minutes:seconds.tenths. */
    private static String clock(double ticks) {
        double seconds = ticks / 20;
        int minutes = (int) (seconds / 60);
        double rest = seconds - minutes * 60;
        return minutes + ":" + (rest < 10 ? "0" : "") + String.format(java.util.Locale.ROOT, "%.1f", rest);
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
        } else {
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
        flySpeed = Math.max(1, Math.min(64, flySpeed * (scrollY > 0 ? 1.25 : 0.8)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_SPACE) {
            togglePlay();
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

    @Override
    public void removed() {
        playback.stop();
    }

    @Override
    public void onClose() {
        playback.stop();
        minecraft.setScreenAndShow(null);
    }
}
