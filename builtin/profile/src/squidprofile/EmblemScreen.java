package squidprofile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import squid.Lang;

import java.awt.Color;
import java.io.IOException;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * The emblem editor, Call of Duty style, in the game (Squid > Emblem). The emblem is on the left: drag on it to move
 * the picked layer. Next to it are the layers (the front one at the top), and the tools for the picked layer: its
 * shape, color, size, turn and flip. More shapes, colors, layers and tools unlock as your Squid Count grows; locked
 * ones say how many points they need. Your badges show at the top.
 */
final class EmblemScreen extends Screen {
    private static final int[] MORE_COLORS = {0x8C5A32, 0xF0A0C8, 0x46C8DC, 0xA0E050, 0xC0C0C0, 0x7A0F0F, 0x103C78, 0xFFE68C};
    private static final Identifier PICTURE = Identifier.fromNamespaceAndPath("squid", "emblem/editor");
    private static DynamicTexture texture;

    private final Screen back;
    private final String me;
    private final int points;
    private final List<String> badges;
    private final Emblem emblem;
    private int selected;
    private String message;
    private boolean dragging;
    private String shown = "";

    // Where things are (worked out in init), for the mouse and drawing
    private int left;
    private int top;
    private int canvas = 96;
    private int listX;
    private int toolsX;
    private int swatchW = 18;
    private int swatchH = 9;

    EmblemScreen(Screen back) {
        super(Component.literal(Lang.t("Emblem")));
        this.back = back;
        me = Profile.me();
        points = Profile.points(me);
        badges = Profile.badges(me);
        Emblem saved = Emblem.load(me);
        emblem = saved != null ? saved : Emblem.starter();
        selected = emblem.layers.size() - 1;
    }

    private Emblem.Layer layer() {
        return selected >= 0 && selected < emblem.layers.size() ? emblem.layers.get(selected) : null;
    }

    @Override
    protected void init() {
        left = width / 2 - 158;
        top = 36;
        listX = left + canvas + 4;
        toolsX = left + canvas + 74;
        int tw = 146;
        Emblem.Layer layer = layer();
        boolean has = layer != null;
        // Under the emblem
        int by = top + canvas + 4;
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Add")), b -> addLayer()).bounds(left, by, 46, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Delete")), b -> deleteLayer()).bounds(left + 50, by, 46, 20).build()).active = has;
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Up")), b -> move(1)).bounds(left, by + 22, 46, 20).build()).active =
                has && selected < emblem.layers.size() - 1;
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Down")), b -> move(-1)).bounds(left + 50, by + 22, 46, 20).build()).active =
                has && selected > 0;
        // The tools for the picked layer
        addRenderableWidget(Button.builder(Component.literal(has ? Lang.t("Shape: {0}", Lang.t(layer.shape.label)) : Lang.t("Shape")), b -> nextShape())
                .bounds(toolsX, top, tw, 20).build()).active = has;
        int sy = top + 24 + swatchH * 2 + 4;
        boolean anyColor = points >= Emblem.UNLOCKS.ALL_COLORS;
        boolean turns = points >= Emblem.UNLOCKS.TURN;
        boolean flips = points >= Emblem.UNLOCKS.FLIP;
        double hue = has ? Color.RGBtoHSB(layer.color >> 16 & 0xFF, layer.color >> 8 & 0xFF, layer.color & 0xFF, null)[0] : 0;
        addRenderableWidget(new Slider(toolsX, sy, tw, 0, 359, hue * 359,
                v -> anyColor ? Lang.t("Any color: {0}", (int) v) : Lang.t("Any color: unlocks at {0} Squid Count", Emblem.UNLOCKS.ALL_COLORS),
                v -> {
                    Emblem.Layer l = layer();
                    if (l != null) l.color = Color.HSBtoRGB((float) (v / 360), 0.8f, 0.95f) & 0xFFFFFF;
                })).active = has && anyColor;
        addRenderableWidget(new Slider(toolsX, sy + 22, tw, 5, 200, has ? layer.size * 100 : 100, v -> Lang.t("Size: {0}%", (int) v), v -> {
            Emblem.Layer l = layer();
            if (l != null) l.size = v / 100;
        })).active = has;
        addRenderableWidget(new Slider(toolsX, sy + 44, tw, 0, 359, has ? ((layer.turn % 360) + 360) % 360 : 0,
                v -> turns ? Lang.t("Turn: {0}°", (int) v) : Lang.t("Turn: unlocks at {0} Squid Count", Emblem.UNLOCKS.TURN), v -> {
                    Emblem.Layer l = layer();
                    if (l != null) l.turn = v;
                })).active = has && turns;
        addRenderableWidget(Button.builder(Component.literal(flips ? Lang.t("Flip Across") : Lang.t("Flip: {0} pts", Emblem.UNLOCKS.FLIP)), b -> flip(true))
                .bounds(toolsX, sy + 66, tw / 2 - 1, 20).build()).active = has && flips;
        addRenderableWidget(Button.builder(Component.literal(flips ? Lang.t("Flip Down") : Lang.t("Flip: {0} pts", Emblem.UNLOCKS.FLIP)), b -> flip(false))
                .bounds(toolsX + tw / 2 + 1, sy + 66, tw / 2 - 1, 20).build()).active = has && flips;
        // Done
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Save")), b -> save()).bounds(width / 2 - 100, height - 26, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Cancel")), b -> onClose()).bounds(width / 2 + 2, height - 26, 98, 20).build());
    }

    private void nextShape() {
        Emblem.Layer layer = layer();
        if (layer == null) return;
        Emblem.Shape[] all = Emblem.Shape.values();
        for (int i = 1; i <= all.length; i++) {
            Emblem.Shape next = all[(layer.shape.ordinal() + i) % all.length];
            if (next.points <= points) {
                layer.shape = next;
                break;
            }
        }
        rebuildWidgets();
    }

    private void flip(boolean across) {
        Emblem.Layer layer = layer();
        if (layer == null) return;
        if (across) layer.flipX = !layer.flipX;
        else layer.flipY = !layer.flipY;
    }

    private void addLayer() {
        int most = Emblem.UNLOCKS.layers(points);
        if (emblem.layers.size() >= most) {
            message = Lang.t("You can have {0} layers. Earn more Squid Count for more!", most);
            return;
        }
        emblem.layers.add(selected + 1, new Emblem.Layer(Emblem.Shape.CIRCLE, Emblem.BASIC_COLORS[(emblem.layers.size() + 2) % 8], 0.5, 0.5, 0.5, 0, false, false));
        selected++;
        rebuildWidgets();
    }

    private void deleteLayer() {
        if (layer() == null) return;
        emblem.layers.remove(selected);
        selected = Math.min(selected, emblem.layers.size() - 1);
        rebuildWidgets();
    }

    /** Moves the picked layer up (in front) or down (behind) one. */
    private void move(int by) {
        int to = selected + by;
        if (layer() == null || to < 0 || to >= emblem.layers.size()) return;
        emblem.layers.add(to, emblem.layers.remove(selected));
        selected = to;
        rebuildWidgets();
    }

    private void save() {
        try {
            emblem.save(me);
            onClose();
        } catch (IOException e) {
            message = Lang.t("Couldn't save it: {0}", e.getMessage());
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(back);
    }

    // ---- Drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("Emblem"), width / 2, 6, 0xFFFFFFFF);
        String count = Lang.t("Squid Count: {0}", points);
        int countW = font.width(count);
        int badgeRow = badges.size() * 11;
        int cx = width / 2 - (countW + (badgeRow > 0 ? badgeRow + 6 : 0)) / 2;
        g.text(font, count, cx, 18, 0xFFFFFF55, true);
        // Badges, after the Squid Count; pointing at one says what it is
        int bx = cx + countW + 6;
        String hovered = null;
        for (String badge : badges) {
            Identifier picture = Profile.badgeTexture(badge);
            if (picture != null) g.blit(picture, bx, 17, bx + 9, 26, 0, 1, 0, 1);
            if (mouseX >= bx && mouseX < bx + 9 && mouseY >= 17 && mouseY < 26) hovered = Lang.t(Profile.BADGE_NAMES.getOrDefault(badge, badge));
            bx += 11;
        }
        // The emblem, big, on a checkerboard so see-through parts show
        int cell = canvas / 8;
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) g.fill(left + i * cell, top + j * cell, left + (i + 1) * cell, top + (j + 1) * cell, (i + j) % 2 == 0 ? 0xFF3C424E : 0xFF30343E);
        }
        refreshPicture();
        g.blit(PICTURE, left, top, left + canvas, top + canvas, 0, 1, 0, 1);
        Emblem.Layer layer = layer();
        if (layer != null) { // a little cross on the picked layer's middle
            int x = left + (int) (layer.x * canvas);
            int y = top + (int) (layer.y * canvas);
            g.fill(x - 3, y, x + 4, y + 1, 0xFFFFFFFF);
            g.fill(x, y - 3, x + 1, y + 4, 0xFFFFFFFF);
        }
        // The layers, the front one at the top
        g.text(font, Lang.t("Layers: {0} of {1}", emblem.layers.size(), Emblem.UNLOCKS.layers(points)), listX, top - 10, 0xFFA0A0A0, true);
        for (int row = 0; row < emblem.layers.size() && row < 11; row++) {
            int index = emblem.layers.size() - 1 - row;
            Emblem.Layer l = emblem.layers.get(index);
            int ry = top + row * 12;
            g.fill(listX, ry, listX + 66, ry + 11, index == selected ? 0x46FFFFFF : 0x5A000000);
            g.fill(listX + 2, ry + 2, listX + 9, ry + 9, 0xFF000000 | (l.color & 0xFFFFFF));
            g.text(font, font.plainSubstrByWidth(Lang.t(l.shape.label), 54), listX + 12, ry + 2, 0xFFFFFFFF, true);
        }
        // The colors: 8 for everyone, 8 more with "any color"
        boolean anyColor = points >= Emblem.UNLOCKS.ALL_COLORS;
        int py = top + 24;
        for (int i = 0; i < 16; i++) {
            int color = i < 8 ? Emblem.BASIC_COLORS[i] : MORE_COLORS[i - 8];
            boolean locked = i >= 8 && !anyColor;
            int sx = toolsX + (i % 8) * swatchW;
            int sy = py + (i / 8) * swatchH;
            g.fill(sx + 1, sy + 1, sx + swatchW - 1, sy + swatchH - 1, 0xFF000000 | (locked ? 0x404040 : color));
            if (layer != null && !locked && (layer.color & 0xFFFFFF) == color) g.outline(sx, sy, swatchW, swatchH, 0xFFFFFFFF);
        }
        String bottom = hovered != null ? hovered : message != null ? message : Lang.t("Drag on the emblem to move a layer. Earn Squid Count to unlock more.");
        g.centeredText(font, font.plainSubstrByWidth(bottom, width - 8), width / 2, height - 40, hovered != null || message != null ? 0xFFFFFF55 : 0xFF808080);
    }

    /** Draws the emblem into its texture again when it has changed. */
    private void refreshPicture() {
        String now = emblem.text();
        if (texture != null && now.equals(shown)) return;
        if (texture == null) {
            texture = new DynamicTexture(() -> "Squid emblem editor", Emblem.SIZE, Emblem.SIZE, true);
            Minecraft.getInstance().getTextureManager().register(PICTURE, texture);
        }
        Profile.upload(emblem.draw(), texture);
        shown = now;
    }

    // ---- The mouse ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        message = null;
        if (x >= left && x < left + canvas && y >= top && y < top + canvas && layer() != null) {
            dragging = true;
            moveLayerTo(x, y);
            return true;
        }
        if (x >= listX && x < listX + 66 && y >= top) {
            int row = (int) ((y - top) / 12);
            if (row < emblem.layers.size() && row < 11) {
                selected = emblem.layers.size() - 1 - row;
                rebuildWidgets();
                return true;
            }
        }
        int py = top + 24;
        if (x >= toolsX && x < toolsX + swatchW * 8 && y >= py && y < py + swatchH * 2 && layer() != null) {
            int i = (int) ((x - toolsX) / swatchW) + (int) ((y - py) / swatchH) * 8;
            if (i >= 8 && points < Emblem.UNLOCKS.ALL_COLORS) {
                message = Lang.t("More colors unlock at {0} Squid Count.", Emblem.UNLOCKS.ALL_COLORS);
            } else if (i < 16) {
                layer().color = i < 8 ? Emblem.BASIC_COLORS[i] : MORE_COLORS[i - 8];
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            moveLayerTo(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        return super.mouseReleased(event);
    }

    private void moveLayerTo(double x, double y) {
        Emblem.Layer layer = layer();
        if (layer == null) return;
        layer.x = Math.max(0, Math.min(1, (x - left) / canvas));
        layer.y = Math.max(0, Math.min(1, (y - top) / canvas));
    }

    /** A slider from min to max, with a label worked out from its value. */
    private static final class Slider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final DoubleFunction<String> label;
        private final DoubleConsumer change;

        Slider(int x, int y, int w, double min, double max, double value, DoubleFunction<String> label, DoubleConsumer change) {
            super(x, y, w, 20, Component.empty(), (Math.clamp(value, min, max) - min) / (max - min));
            this.min = min;
            this.max = max;
            this.label = label;
            this.change = change;
            updateMessage();
        }

        private double current() {
            return min + value * (max - min);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label.apply(current())));
        }

        @Override
        protected void applyValue() {
            change.accept(current());
        }
    }
}
