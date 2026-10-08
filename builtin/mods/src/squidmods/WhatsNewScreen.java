package squidmods;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;

/** "New in Squid": the new things in this update, a line each, with where to find them. */
final class WhatsNewScreen extends Screen {
    private final Screen parent;

    WhatsNewScreen(Screen parent) {
        super(Component.literal(Lang.t("New in Squid")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Let's go!")), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("New in Squid"), width / 2, 16, 0xFF55FFFF);
        int y = Math.max(40, height / 2 - WhatsNew.LINES.size() * 8);
        for (String line : WhatsNew.LINES) {
            g.centeredText(font, font.plainSubstrByWidth(Lang.t(line), width - 20), width / 2, y, 0xFFFFFFFF);
            y += 16;
        }
        g.centeredText(font, Lang.t("Everything is in the Squid menu (top left)."), width / 2, y + 8, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
