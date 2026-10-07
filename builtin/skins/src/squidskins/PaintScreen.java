package squidskins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A pixel painter for a skin (64x64) or a cape (64x32). Left click paints, right click picks up the color
 * under the mouse. It starts from the skin or cape you're wearing, and Save adds the result to your wardrobe.
 */
final class PaintScreen extends Screen {
    /** The colors to paint with. The last one is see-through, for the skin's outer layer (hats, jackets). */
    private static final int[] PALETTE = {
            0xFF000000, 0xFF3F3F3F, 0xFF7F7F7F, 0xFFC0C0C0, 0xFFFFFFFF, 0xFF6B3A1E, 0xFFA0662B, 0xFFE0AC69,
            0xFFFFD7B0, 0xFFB02E26, 0xFFF9801D, 0xFFFED83D, 0xFF80C71F, 0xFF5E7C16, 0xFF169C9C, 0xFF3AB3DA,
            0xFF3C44AA, 0xFF8932B8, 0xFFC74EBD, 0xFFF38BAA, 0x00000000};

    private final WardrobeScreen parent;
    private final boolean isCape;
    private final int[] pixels;
    private final int w;
    private final int h;
    private final Deque<int[]> undo = new ArrayDeque<>();
    private int color = PALETTE[0];
    private boolean painting;
    private String problem;

    private PaintScreen(WardrobeScreen parent, boolean isCape, BufferedImage start) {
        super(Component.literal(isCape ? "Paint a Cape" : "Paint a Skin"));
        this.parent = parent;
        this.isCape = isCape;
        this.w = 64;
        this.h = isCape ? 32 : 64;
        this.pixels = new int[w * h];
        if (start != null) {
            // Big (HD) pictures are shrunk to the normal size; an old 64x32 skin just fills the top half
            int sw = start.getWidth() / w;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int sx = x * Math.max(1, sw);
                    int sy = y * Math.max(1, sw);
                    pixels[y * w + x] = sx < start.getWidth() && sy < start.getHeight() ? start.getRGB(sx, sy) : 0;
                }
            }
        }
    }

    /** Paints a skin, starting from the one you're wearing (or the default skin). */
    static PaintScreen skin(WardrobeScreen parent, Wardrobe.Choice now) {
        BufferedImage start = null;
        try {
            if (!now.skin().isEmpty()) start = ImageIO.read(Skins.wardrobe.skins().resolve(now.skin()).toFile());
            if (start == null) {
                try (InputStream in = Minecraft.getInstance().getResourceManager().open(DefaultPlayerSkin.getDefaultSkin().body().texturePath())) {
                    start = ImageIO.read(in);
                }
            }
        } catch (IOException e) {
            start = null; // a blank page instead
        }
        return new PaintScreen(parent, false, start);
    }

    /** Paints a cape, starting from the one you're wearing (or the Kelp cape). */
    static PaintScreen cape(WardrobeScreen parent, Wardrobe.Choice now) {
        BufferedImage start = null;
        try {
            if (now.cape().startsWith("file:")) start = ImageIO.read(Skins.wardrobe.capes().resolve(now.cape().substring(5)).toFile());
            if (start == null) {
                String builtIn = Wardrobe.BUILT_IN_CAPES.contains(now.cape()) ? now.cape() : "kelp";
                try (InputStream in = Skins.class.getResourceAsStream("/squidskins/capes/" + builtIn + ".png")) {
                    if (in != null) start = ImageIO.read(in);
                }
            }
        } catch (IOException e) {
            start = null;
        }
        return new PaintScreen(parent, true, start);
    }

    private int scale() {
        return Math.max(2, Math.min((height - 70) / h, (width - 130) / w));
    }

    private int left() {
        return 10;
    }

    private int top() {
        return 24;
    }

    @Override
    protected void init() {
        int x = left() + w * scale() + 12;
        int y = top() + 8 * 14 + 14;
        addRenderableWidget(Button.builder(Component.literal("Undo"), b -> {
            if (!undo.isEmpty()) System.arraycopy(undo.pop(), 0, pixels, 0, pixels.length);
        }).bounds(x, y, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(x, y + 24, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x, y + 48, 98, 20).build());
    }

    private void save() {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);
        try {
            Path folder = isCape ? Skins.wardrobe.capes() : Skins.wardrobe.skins();
            Files.createDirectories(folder);
            Path file = Wardrobe.freeName(folder, isCape ? "My Cape.png" : "My Skin.png");
            ImageIO.write(image, "png", file.toFile());
            Wardrobe.Choice now = Skins.choice(Skins.myId());
            String name = file.getFileName().toString();
            Skins.choose(Skins.myId(), isCape ? new Wardrobe.Choice(now.skin(), now.slim(), "file:" + name)
                    : new Wardrobe.Choice(name, now.slim(), now.cape()));
            parent.say("Saved " + name.replaceAll("(?i)\\.png$", "") + " and put it on!", 0xFF55FF55);
            onClose();
        } catch (IOException e) {
            problem = "Couldn't save: " + e.getMessage();
        }
    }

    /** The pixel under the mouse, or -1. */
    private int pixelAt(double mx, double my) {
        int px = (int) Math.floor((mx - left()) / scale());
        int py = (int) Math.floor((my - top()) / scale());
        return px >= 0 && py >= 0 && px < w && py < h ? py * w + px : -1;
    }

    /** The palette color under the mouse, or -1. */
    private int paletteAt(double mx, double my) {
        int x0 = left() + w * scale() + 12;
        for (int i = 0; i < PALETTE.length; i++) {
            int x = x0 + (i % 7) * 14;
            int y = top() + (i / 7) * 14;
            if (mx >= x && mx < x + 12 && my >= y && my < y + 12) return i;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int swatch = paletteAt(event.x(), event.y());
        if (swatch >= 0) {
            color = PALETTE[swatch];
            return true;
        }
        int pixel = pixelAt(event.x(), event.y());
        if (pixel >= 0) {
            if (event.button() == 1) { // right click: pick up this color
                color = pixels[pixel];
                return true;
            }
            undo.push(pixels.clone());
            if (undo.size() > 50) undo.removeLast();
            painting = true;
            pixels[pixel] = color;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
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
        g.text(font, isCape ? "Paint a Cape" : "Paint a Skin", left(), 8, 0xFFFFFFFF);
        int s = scale();
        // The picture, big. See-through pixels show a checkerboard, like in paint programs.
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

        // The palette, with the chosen color outlined
        int x0 = left() + w * s + 12;
        for (int i = 0; i < PALETTE.length; i++) {
            int x = x0 + (i % 7) * 14;
            int y = top() + (i / 7) * 14;
            if (PALETTE[i] == color) g.fill(x - 2, y - 2, x + 14, y + 14, 0xFFFFFFFF);
            g.fill(x, y, x + 12, y + 12, 0xFF000000);
            if (PALETTE[i] == 0) {
                g.fill(x + 1, y + 1, x + 6, y + 6, 0xFF3A3A3A);
                g.fill(x + 6, y + 6, x + 11, y + 11, 0xFF3A3A3A);
            } else {
                g.fill(x + 1, y + 1, x + 11, y + 11, PALETTE[i]);
            }
        }
        int infoY = top() + 3 * 14 + 4;
        g.text(font, "Now:", x0, infoY, 0xFFA0A0A0);
        g.fill(x0 + 28, infoY - 2, x0 + 40, infoY + 10, 0xFF000000);
        g.fill(x0 + 29, infoY - 1, x0 + 39, infoY + 9, (color >>> 24) == 0 ? 0xFF3A3A3A : color);
        g.text(font, "Left click: paint", x0, infoY + 16, 0xFF808080);
        g.text(font, "Right click: pick", x0, infoY + 27, 0xFF808080);
        g.text(font, "Checkered: see-through", x0, infoY + 38, 0xFF808080);
        if (problem != null) g.text(font, problem, left(), height - 12, 0xFFFF5555);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
