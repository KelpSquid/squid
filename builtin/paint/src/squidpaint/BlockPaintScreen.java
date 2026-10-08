package squidpaint;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import squid.Lang;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paints one block's texture, pixel by pixel. Left click paints (or fills, with the Fill tool), right click picks up
 * a color, and a picture dropped onto the window turns into pixel art. The top row of colors
 * is the block's own (its most used colors), so a repaint still fits Minecraft's look; the rest is a rainbow.
 * Save puts it in the Squid Paint pack and reloads the game, so it shows in the world right away.
 */
final class BlockPaintScreen extends Screen {
    private static final int[] RAINBOW = {
            0xFF000000, 0xFF3F3F3F, 0xFF7F7F7F, 0xFFC0C0C0, 0xFFFFFFFF, 0xFF6B3A1E, 0xFFA0662B,
            0xFFB02E26, 0xFFF9801D, 0xFFFED83D, 0xFF80C71F, 0xFF5E7C16, 0xFF169C9C, 0xFF3AB3DA,
            0xFF3C44AA, 0xFF8932B8, 0xFFC74EBD, 0xFFF38BAA, 0xFFE0AC69, 0xFF4A6B2A, 0x00000000};

    private final BlockPickScreen parent;
    private final Identifier texture;
    private int w;
    private int h;
    private int frame; // how tall one frame is, for an animated texture (0 if it isn't)
    private int[] pixels;
    private int[] palette = RAINBOW;
    private final Deque<int[]> undo = new ArrayDeque<>();
    private int color = RAINBOW[0];
    private boolean painting;
    private boolean filling; // the Fill bucket instead of the pen
    private volatile boolean reading; // a dropped picture is being read in the background
    private String message;
    private int messageColor;
    private boolean canSave; // false when the texture couldn't be read, so Save can't wipe it out

    BlockPaintScreen(BlockPickScreen parent, Identifier texture) {
        super(Component.literal(Lang.t("Block Painter")));
        this.parent = parent;
        this.texture = texture;
        load(Paint.current(texture));
    }

    /**
     * Reads a texture's pixels. Big ones (over 64 x 64) are refused, so the painter stays simple. An animated texture
     * (like magma) is a strip of frames one under the other, painted as a whole.
     */
    private void load(byte[] png) {
        BufferedImage image = null;
        try {
            if (png != null) image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException ignored) {
            // shown as a problem below
        }
        canSave = image != null && image.getWidth() <= 64 && image.getHeight() <= 64;
        if (!canSave) {
            w = 16;
            h = 16;
            pixels = new int[w * h];
            say(image == null ? Lang.t("Couldn't read this texture, so it can't be painted.")
                    : Lang.t("This texture is too big to paint here (the most is 64 x 64)."), 0xFFFF5555);
        } else {
            w = image.getWidth();
            h = image.getHeight();
            pixels = image.getRGB(0, 0, w, h, null, 0, w);
            frame = Paint.frameHeight(texture, w);
        }
        palette = withBlockColors(pixels);
        color = palette[0];
    }

    /** The block's 7 most used colors first, then the rainbow. */
    private static int[] withBlockColors(int[] pixels) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int p : pixels) if ((p >>> 24) > 0) counts.merge(p, 1, Integer::sum);
        List<Integer> common = new ArrayList<>(counts.keySet());
        common.sort((a, b) -> counts.get(b) - counts.get(a));
        int[] out = new int[7 + RAINBOW.length];
        for (int i = 0; i < 7; i++) out[i] = i < common.size() ? common.get(i) : RAINBOW[i];
        System.arraycopy(RAINBOW, 0, out, 7, RAINBOW.length);
        return out;
    }

    private void say(String text, int color) {
        message = text;
        messageColor = color;
    }

    private int scale() {
        return Math.max(2, Math.min((height - 70) / h, (width - 140) / w));
    }

    private int left() {
        return 10;
    }

    private int top() {
        return 24;
    }

    private int panel() {
        return left() + w * scale() + 12;
    }

    @Override
    protected void init() {
        int x = panel();
        int y = top() + rowsOfColors() * 14 + 62;
        // The pen or the Fill bucket; pressing it switches
        addRenderableWidget(Button.builder(Component.literal(filling ? Lang.t("Fill") : Lang.t("Pen")), b -> {
            filling = !filling;
            rebuildWidgets();
        }).bounds(x, y, 48, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Undo")), b -> {
            if (!undo.isEmpty()) System.arraycopy(undo.pop(), 0, pixels, 0, pixels.length);
        }).bounds(x + 50, y, 48, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Save")), b -> save()).bounds(x, y + 24, 98, 20).build())
                .active = canSave;
        // Your newest screenshot (F2) as pixel art: a picture of your world, hung on the wall
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Last Screenshot")), b -> lastScreenshot()).bounds(x, y + 48, 98, 20).build())
                .active = canSave && !reading;
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Reset")), b -> reset()).bounds(x, y + 72, 48, 20).build())
                .active = Paint.painted(texture);
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Back")), b -> onClose()).bounds(x + 50, y + 72, 48, 20).build());
    }

    private int rowsOfColors() {
        return (palette.length + 6) / 7;
    }

    private void save() {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);
        try {
            Paint.pack();
            Path file = Paint.file(texture);
            Files.createDirectories(file.getParent());
            ImageIO.write(image, "png", file.toFile());
            // Minecraft's settings for it (animation, how it looks far away) only count from the same pack
            byte[] settings = Paint.originalSettings(texture);
            if (settings != null) Files.write(Paint.settingsFile(texture), settings);
            else Files.deleteIfExists(Paint.settingsFile(texture));
            say(Lang.t("Saved! Reloading so it shows in the world..."), 0xFF55FF55);
            Paint.apply();
        } catch (IOException e) {
            say(Lang.t("Couldn't save: {0}", e.getMessage()), 0xFFFF5555);
        }
    }

    /** Back to Minecraft's own texture: the painting is removed from the pack. */
    private void reset() {
        try {
            Files.deleteIfExists(Paint.file(texture));
            Files.deleteIfExists(Paint.settingsFile(texture));
            load(Paint.original(texture));
            undo.clear();
            say(Lang.t("Back to Minecraft's own texture."), 0xFF55FF55);
            Paint.apply();
            rebuildWidgets();
        } catch (IOException e) {
            say(Lang.t("Couldn't reset it: {0}", e.getMessage()), 0xFFFF5555);
        }
    }

    /** The pixel under the mouse, or -1. */
    private int pixelAt(double mx, double my) {
        int px = (int) Math.floor((mx - left()) / scale());
        int py = (int) Math.floor((my - top()) / scale());
        return px >= 0 && py >= 0 && px < w && py < h ? py * w + px : -1;
    }

    /** The color swatch under the mouse, or -1. */
    private int swatchAt(double mx, double my) {
        int x0 = panel();
        for (int i = 0; i < palette.length; i++) {
            int x = x0 + (i % 7) * 14;
            int y = top() + 12 + (i / 7) * 14;
            if (mx >= x && mx < x + 12 && my >= y && my < y + 12) return i;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int swatch = swatchAt(event.x(), event.y());
        if (swatch >= 0) {
            color = palette[swatch];
            return true;
        }
        int pixel = pixelAt(event.x(), event.y());
        if (pixel >= 0) {
            if (event.button() == 1) { // right click: pick up this color
                color = pixels[pixel];
                return true;
            }
            remember();
            if (filling) {
                fill(pixels, w, h, pixel, color);
                return true;
            }
            painting = true;
            pixels[pixel] = color;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Keeps a copy for Undo. */
    private void remember() {
        undo.push(pixels.clone());
        if (undo.size() > 50) undo.removeLast();
    }

    /** The Fill bucket: the pixel and every pixel of its color touching it (not across corners) turn this color. */
    static void fill(int[] pixels, int w, int h, int start, int color) {
        int old = pixels[start];
        if (old == color) return;
        Deque<Integer> todo = new ArrayDeque<>();
        todo.push(start);
        while (!todo.isEmpty()) {
            int at = todo.pop();
            if (pixels[at] != old) continue;
            pixels[at] = color;
            int x = at % w;
            int y = at / w;
            if (x > 0) todo.push(at - 1);
            if (x < w - 1) todo.push(at + 1);
            if (y > 0) todo.push(at - w);
            if (y < h - 1) todo.push(at + w);
        }
    }

    /**
     * A picture dropped onto the window (PNG, JPG, GIF or BMP) is shrunk into the texture as pixel art. It's read in
     * the background (a big phone photo takes a moment), and only as big as it needs to be.
     */
    @Override
    public void onFilesDrop(List<Path> files) {
        if (!files.isEmpty()) usePicture(files.getFirst());
    }

    /** The newest picture in the screenshots folder (the ones F2 takes), turned into pixel art. */
    private void lastScreenshot() {
        Path folder = minecraft.gameDirectory.toPath().resolve("screenshots");
        Path newest = null;
        try (java.util.stream.Stream<Path> list = Files.list(folder)) {
            newest = list.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
        } catch (IOException e) {
            // no screenshots folder yet
        }
        if (newest == null) {
            say(Lang.t("No screenshots yet. Press F2 in the game to take one."), 0xFFFF5555);
            return;
        }
        usePicture(newest);
    }

    private void usePicture(Path dropped) {
        if (!canSave || reading) return;
        int tw = w;
        int th = h;
        int frames = frame;
        reading = true;
        say(Lang.t("Turning it into pixel art..."), 0xFFA0A0A0);
        Util.backgroundExecutor().execute(() -> {
            int[] art = null;
            try {
                BufferedImage picture = Paint.readPicture(dropped);
                if (picture != null) art = Paint.fit(picture, tw, th, frames);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // said below
            }
            int[] done = art;
            minecraft.execute(() -> {
                reading = false;
                if (done == null || done.length != pixels.length) {
                    say(Lang.t("That isn't a picture Squid can read (try a PNG or JPG)."), 0xFFFF5555);
                    return;
                }
                remember();
                System.arraycopy(done, 0, pixels, 0, pixels.length);
                palette = withBlockColors(pixels);
                say(Lang.t("Here it is as pixel art. Touch it up, then Save."), 0xFF55FF55);
                rebuildWidgets();
            });
        });
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (painting) {
            int pixel = pixelAt(event.x(), event.y());
            if (pixel >= 0) pixels[pixel] = color;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        painting = false;
        return super.mouseReleased(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.text(font, BlockPickScreen.nice(texture), left(), 8, 0xFFFFFFFF);
        int s = scale();
        // The texture, big. See-through pixels show a checkerboard.
        g.fill(left() - 1, top() - 1, left() + w * s + 1, top() + h * s + 1, 0xFF000000);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = pixels[y * w + x];
                int px = left() + x * s;
                int py = top() + y * s;
                if ((argb >>> 24) < 255) g.fill(px, py, px + s, py + s, ((x + y) & 1) == 0 ? 0xFF2A2A2A : 0xFF3A3A3A);
                if ((argb >>> 24) > 0) g.fill(px, py, px + s, py + s, argb);
            }
        }
        int hover = pixelAt(mouseX, mouseY);
        if (hover >= 0) {
            int px = left() + (hover % w) * s;
            int py = top() + (hover / w) * s;
            g.fill(px, py, px + s, py + 1, 0xFFFFFFFF);
            g.fill(px, py + s - 1, px + s, py + s, 0xFFFFFFFF);
        }
        // A small copy at real size, tiled 3 x 3, to see how it looks as blocks next to each other
        int x0 = panel();
        int previewY = top() + rowsOfColors() * 14 + 16;
        for (int ty = 0; ty < 3; ty++) {
            for (int tx = 0; tx < 3; tx++) {
                for (int y = 0; y < Math.min(h, 16); y++) {
                    for (int x = 0; x < Math.min(w, 16); x++) {
                        int argb = pixels[(y * h / Math.min(h, 16)) * w + x * w / Math.min(w, 16)];
                        if ((argb >>> 24) == 0) continue;
                        int px = x0 + tx * 16 + x;
                        int py = previewY + ty * 16 + y - 2;
                        if (py + 1 > previewY + 46) continue;
                        g.fill(px, py, px + 1, py + 1, argb);
                    }
                }
            }
        }
        // The colors: the block's own on top, then the rainbow, with the chosen one outlined
        g.text(font, Lang.t("Colors"), x0, top(), 0xFFA0A0A0);
        for (int i = 0; i < palette.length; i++) {
            int x = x0 + (i % 7) * 14;
            int y = top() + 12 + (i / 7) * 14;
            if (palette[i] == color) g.fill(x - 2, y - 2, x + 14, y + 14, 0xFFFFFFFF);
            g.fill(x, y, x + 12, y + 12, 0xFF000000);
            if ((palette[i] >>> 24) == 0) {
                g.fill(x + 1, y + 1, x + 6, y + 6, 0xFF3A3A3A);
                g.fill(x + 6, y + 6, x + 11, y + 11, 0xFF3A3A3A);
            } else {
                g.fill(x + 1, y + 1, x + 11, y + 11, palette[i]);
            }
        }
        g.text(font, Lang.t("Left click: paint"), x0 + 52, previewY, 0xFF808080);
        g.text(font, Lang.t("Right click: pick"), x0 + 52, previewY + 11, 0xFF808080);
        g.text(font, font.plainSubstrByWidth(Lang.t("Drop a picture: pixel art"), Math.max(0, width - x0 - 56)), x0 + 52, previewY + 22, 0xFF808080);
        // Under the texture, left of the buttons (two lines if it's long)
        if (message != null) {
            int room = Math.max(40, x0 - left() - 6);
            String first = font.plainSubstrByWidth(message, room);
            g.text(font, first, left(), height - 23, messageColor);
            g.text(font, font.plainSubstrByWidth(message.substring(first.length()).strip(), room), left(), height - 12, messageColor);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
