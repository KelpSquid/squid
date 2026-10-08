package squidmods;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;
import squid.MenuButtons;

import java.util.ArrayList;
import java.util.List;

/**
 * The Squid menu: one button on the title screen and pause menu opens it, so Squid's things (Mods, Panorama,
 * Replay, and any mod that adds a button) don't crowd Minecraft's own screens. Buttons that only work in a world,
 * like Replay, only show there.
 */
final class SquidMenuScreen extends Screen {
    private final Screen parent;

    SquidMenuScreen(Screen parent) {
        super(Component.literal("Squid"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.builder(Component.literal(Lang.t("Mods")), b -> minecraft.setScreenAndShow(new ModListScreen(this))).build());
        buttons.add(Button.builder(Component.literal(Lang.t("Mod Maker")), b -> minecraft.setScreenAndShow(new ModMakerScreen(this))).build());
        for (MenuButtons.Entry entry : MenuButtons.all()) {
            if (entry.inWorldOnly() && minecraft.level == null) continue;
            buttons.add(Button.builder(Component.literal(Lang.t(entry.label())), b -> {
                try {
                    entry.open().accept(this);
                } catch (RuntimeException e) {
                    System.out.println("[Squid] " + entry.modId() + "'s " + entry.label() + " button broke: " + e);
                }
            }).build());
        }
        // Two columns of buttons in the middle, like Minecraft's own menus
        int y = Math.max(30, height / 4);
        for (int i = 0; i < buttons.size(); i++) {
            Button button = buttons.get(i);
            boolean alone = i == buttons.size() - 1 && i % 2 == 0;
            button.setX(alone ? width / 2 - 100 : width / 2 - 100 + (i % 2) * 104);
            button.setY(y + (i / 2) * 24);
            button.setWidth(alone ? 200 : 96);
            addRenderableWidget(button);
        }
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, "Squid", width / 2, 12, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
