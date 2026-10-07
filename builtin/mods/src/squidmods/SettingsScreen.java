package squidmods;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;
import squid.api.ModSettings;

import java.util.List;

/**
 * One mod's settings, made from what the mod asked for: on/off ones are buttons, numbers are sliders, and choices are
 * buttons that go through them. Changes take effect right away.
 */
final class SettingsScreen extends Screen {
    private final Screen parent;
    private final String modName;
    private final ModSettings settings;
    private int page;

    SettingsScreen(Screen parent, String modName, ModSettings settings) {
        super(Component.literal(modName));
        this.parent = parent;
        this.modName = modName;
        this.settings = settings;
    }

    private int perPage() {
        return Math.max(1, (height - 90) / 24);
    }

    @Override
    protected void init() {
        List<ModSettings.Setting> all = settings.list();
        int pages = Math.max(1, (all.size() + perPage() - 1) / perPage());
        page = Math.min(page, pages - 1);
        int y = 36;
        for (ModSettings.Setting setting : all.subList(page * perPage(), Math.min(all.size(), (page + 1) * perPage()))) {
            int x = width / 2 - 100;
            if (setting.isNumber()) {
                addRenderableWidget(new NumberSlider(x, y, setting));
            } else {
                addRenderableWidget(Button.builder(Component.literal(label(setting)), b -> {
                    change(setting);
                    b.setMessage(Component.literal(label(setting)));
                }).bounds(x, y, 200, 20).build());
            }
            y += 24;
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = Math.floorMod(page - 1, pages);
                rebuildWidgets();
            }).bounds(width / 2 - 100, height - 52, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = Math.floorMod(page + 1, pages);
                rebuildWidgets();
            }).bounds(width / 2 + 80, height - 52, 20, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private String label(ModSettings.Setting setting) {
        String value = settings.get(setting.name());
        if (setting.isToggle()) value = Boolean.parseBoolean(value) ? Lang.t("ON") : Lang.t("OFF");
        return setting.name() + ": " + value;
    }

    /** On/off settings flip; choices move to the next one. */
    private void change(ModSettings.Setting setting) {
        String now = settings.get(setting.name());
        if (setting.isToggle()) {
            settings.set(setting.name(), !Boolean.parseBoolean(now));
        } else if (setting.isChoice()) {
            List<String> choices = setting.choices();
            settings.set(setting.name(), choices.get((choices.indexOf(now) + 1) % choices.size()));
        }
    }

    /** A Minecraft-style slider for a whole number from the setting's min to its max. */
    private final class NumberSlider extends AbstractSliderButton {
        private final ModSettings.Setting setting;

        NumberSlider(int x, int y, ModSettings.Setting setting) {
            super(x, y, 200, 20, Component.empty(), fraction(setting, Integer.parseInt(settings.get(setting.name()))));
            this.setting = setting;
            updateMessage();
        }

        private int number() {
            return setting.min() + (int) Math.round(value * (setting.max() - setting.min()));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(setting.name() + ": " + number()));
        }

        @Override
        protected void applyValue() {
            settings.set(setting.name(), number());
        }
    }

    private static double fraction(ModSettings.Setting setting, int number) {
        return setting.max() == setting.min() ? 0 : (number - setting.min()) / (double) (setting.max() - setting.min());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("{0} Settings", modName), width / 2, 12, 0xFFFFFFFF);
        g.centeredText(font, Lang.t("Changes work right away."), width / 2, height - 42, 0xFF808080);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
