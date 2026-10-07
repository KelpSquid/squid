package squidpano;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;

import java.io.IOException;
import java.util.List;

/**
 * Pick your title-screen panorama (or Minecraft's own), how fast it spins and which way, capture a new one where you
 * stand (from a world), or make Kelp's background match it. The title screen behind shows each change straight away.
 */
final class PanoScreen extends Screen {
    private final Screen parent;
    private int page;
    private String message;
    private int messageColor = 0xFFA0A0A0;

    PanoScreen(Screen parent) {
        super(Component.literal(Lang.t("Panorama")));
        this.parent = parent;
    }

    private int perPage() {
        return Math.max(1, (height - 150) / 24);
    }

    @Override
    protected void init() {
        PanoStore store = Pano.store;
        List<String> names = store.list();
        names.add(0, null); // Minecraft's own, first
        String active = store.active();
        int pages = Math.max(1, (names.size() + perPage() - 1) / perPage());
        page = Math.min(page, pages - 1);
        int x = width / 2 - 100;
        int y = 30;
        for (String name : names.subList(page * perPage(), Math.min(names.size(), (page + 1) * perPage()))) {
            boolean on = name == null ? active == null : name.equals(active);
            String label = (on ? "§a» " : "") + (name == null ? Lang.t("Minecraft's own") : name);
            addRenderableWidget(Button.builder(Component.literal(label), b -> use(name)).bounds(x, y, name == null ? 200 : 150, 20).build());
            if (name != null) addRenderableWidget(Button.builder(Component.literal(Lang.t("Delete")), b -> delete(name)).bounds(x + 154, y, 46, 20).build());
            y += 24;
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = Math.floorMod(page - 1, pages);
                rebuildWidgets();
            }).bounds(x - 24, 30, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = Math.floorMod(page + 1, pages);
                rebuildWidgets();
            }).bounds(x + 204, 30, 20, 20).build());
        }

        int bottom = height - 100;
        addRenderableWidget(new SpeedSlider(x, bottom, store));
        addRenderableWidget(Button.builder(Component.literal(store.reversed() ? Lang.t("Spin: Right") : Lang.t("Spin: Left")), b -> {
            store.setReversed(!store.reversed());
            rebuildWidgets();
        }).bounds(x, bottom + 24, 98, 20).build());
        Button kelp = Button.builder(Component.literal(Lang.t("Use in Kelp")), b -> useInKelp()).bounds(x + 102, bottom + 24, 98, 20).build();
        kelp.active = active != null;
        addRenderableWidget(kelp);
        Button capture = Button.builder(Component.literal(Lang.t("Capture Here")), b -> capture()).bounds(x, bottom + 48, 200, 20).build();
        capture.active = minecraft.level != null;
        addRenderableWidget(capture);
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(x, height - 28, 200, 20).build());
    }

    private void use(String name) {
        Pano.store.setActive(name);
        Pano.apply();
        rebuildWidgets();
    }

    private void delete(String name) {
        try {
            Pano.store.delete(name);
            Pano.apply();
        } catch (IOException e) {
            say(Lang.t("Couldn't delete it: {0}", e.getMessage()), 0xFFFF5555);
        }
        rebuildWidgets();
    }

    private void useInKelp() {
        try {
            Pano.store.makeKelpTheme(Pano.store.active());
            say(Lang.t("Done! Pick it in Kelp's Options > Theme."), 0xFF55FF55);
        } catch (IOException e) {
            say(Lang.t("Couldn't make it: {0}", e.getMessage()), 0xFFFF5555);
        }
    }

    /** Closes the menus and snaps the six directions on the next tick, so the pictures show the world. */
    private void capture() {
        Pano.captureNextTick = true;
        minecraft.setScreenAndShow(null);
    }

    private void say(String text, int color) {
        message = text;
        messageColor = color;
    }

    /** How fast it spins: 0% (still) to 300%. */
    private static final class SpeedSlider extends AbstractSliderButton {
        private final PanoStore store;

        SpeedSlider(int x, int y, PanoStore store) {
            super(x, y, 200, 20, Component.empty(), store.speed() / 3);
            this.store = store;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(Lang.t("Speed: {0}%", Math.round(value * 300))));
        }

        @Override
        protected void applyValue() {
            store.setSpeed(value * 3);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("Panorama"), width / 2, 12, 0xFFFFFFFF);
        if (minecraft.level == null) g.centeredText(font, Lang.t("To capture one, open this from the pause menu in a world."), width / 2, height - 40, 0xFF808080);
        if (message != null) g.centeredText(font, font.plainSubstrByWidth(message, width - 20), width / 2, height - 52, messageColor);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
