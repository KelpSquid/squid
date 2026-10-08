package squidmods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;
import squid.api.ModScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * A mod's own screen (squid.api.ModScreen), made into a real Minecraft screen: its items one under the other, 200
 * wide, with pages when they don't fit, and Done at the bottom. Closing it goes back to the screen that was open.
 */
final class BuiltScreen extends Screen {
    private final ModScreen built;
    private final Screen parent;
    private final List<ModScreen.Item> items;
    // What each item is now, kept between pages (and when the window is resized)
    private final boolean[] on;
    private final int[] numbers;
    private final String[] texts;
    private final List<int[]> labelRows = new ArrayList<>(); // item index and y of each label on this page
    private int page;

    /** Opens a mod's screen on the game's own thread. */
    static void open(ModScreen built) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreenAndShow(new BuiltScreen(built, minecraft.gui.screen())));
    }

    private BuiltScreen(ModScreen built, Screen parent) {
        super(Component.literal(built.title()));
        this.built = built;
        this.parent = parent;
        this.items = built.items();
        on = new boolean[items.size()];
        numbers = new int[items.size()];
        texts = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof ModScreen.Toggle t) on[i] = t.startOn();
            if (items.get(i) instanceof ModScreen.Slider s) numbers[i] = s.start();
            if (items.get(i) instanceof ModScreen.TextBox t) texts[i] = t.start();
        }
    }

    private int perPage() {
        return Math.max(1, (height - 70) / 24);
    }

    @Override
    protected void init() {
        labelRows.clear();
        int pages = Math.max(1, (items.size() + perPage() - 1) / perPage());
        page = Math.min(page, pages - 1);
        int x = width / 2 - 100;
        int y = 30;
        for (int i = page * perPage(); i < Math.min(items.size(), (page + 1) * perPage()); i++) {
            int index = i;
            ModScreen.Item item = items.get(i);
            if (item instanceof ModScreen.Label) {
                labelRows.add(new int[] {i, y + 6});
            } else if (item instanceof ModScreen.Button b) {
                addRenderableWidget(Button.builder(Component.literal(b.label()), w -> built.safely(b.onClick())).bounds(x, y, 200, 20).build());
            } else if (item instanceof ModScreen.Toggle t) {
                addRenderableWidget(Button.builder(Component.literal(toggleText(t, on[i])), w -> {
                    on[index] = !on[index];
                    w.setMessage(Component.literal(toggleText(t, on[index])));
                    built.safely(() -> t.onChange().accept(on[index]));
                }).bounds(x, y, 200, 20).build());
            } else if (item instanceof ModScreen.Slider s) {
                addRenderableWidget(new NumberSlider(x, y, s, index));
            } else if (item instanceof ModScreen.TextBox t) {
                EditBox box = new EditBox(font, x, y, 200, 20, Component.literal(t.label()));
                box.setMaxLength(256);
                box.setHint(Component.literal(t.label()));
                box.setValue(texts[i]);
                box.setResponder(text -> {
                    texts[index] = text;
                    built.safely(() -> t.onChange().accept(text));
                });
                addRenderableWidget(box);
            }
            y += 24;
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = Math.floorMod(page - 1, pages);
                rebuildWidgets();
            }).bounds(width / 2 - 100, height - 28, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = Math.floorMod(page + 1, pages);
                rebuildWidgets();
            }).bounds(width / 2 + 80, height - 28, 20, 20).build());
        }
        int doneX = pages > 1 ? width / 2 - 76 : width / 2 - 100;
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(doneX, height - 28, pages > 1 ? 152 : 200, 20).build());
    }

    private static String toggleText(ModScreen.Toggle t, boolean isOn) {
        return t.label() + ": " + (isOn ? Lang.t("ON") : Lang.t("OFF"));
    }

    /** A Minecraft-style slider for a whole number from the slider's min to its max. */
    private final class NumberSlider extends AbstractSliderButton {
        private final ModScreen.Slider slider;
        private final int index;

        NumberSlider(int x, int y, ModScreen.Slider slider, int index) {
            super(x, y, 200, 20, Component.empty(), slider.max() == slider.min() ? 0
                    : (numbers[index] - slider.min()) / (double) (slider.max() - slider.min()));
            this.slider = slider;
            this.index = index;
            updateMessage();
        }

        private int number() {
            return slider.min() + (int) Math.round(value * (slider.max() - slider.min()));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(slider.label() + ": " + number()));
        }

        @Override
        protected void applyValue() {
            int number = number();
            if (number == numbers[index]) return;
            numbers[index] = number;
            built.safely(() -> slider.onChange().accept(number));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, built.title(), width / 2, 12, 0xFFFFFFFF);
        for (int[] row : labelRows) {
            g.centeredText(font, built.text((ModScreen.Label) items.get(row[0])), width / 2, row[1], 0xFFE0E0E0);
        }
    }

    @Override
    public void onClose() {
        built.closed();
        minecraft.setScreenAndShow(parent);
    }
}
