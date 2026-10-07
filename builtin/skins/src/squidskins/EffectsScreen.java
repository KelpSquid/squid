package squidskins;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;

import java.io.IOException;

/**
 * Switch your cape's effects on and off, with a turning preview: looks (enchanted, glow, rainbow) on the cape itself,
 * and trails (bubbles, water, fire, sparkles, hearts, snow) that follow you while you play. Mix as many as you like.
 */
final class EffectsScreen extends Screen {
    private final WardrobeScreen parent;
    private String message;

    EffectsScreen(WardrobeScreen parent) {
        super(Component.literal(Lang.t("Cape Effects")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int previewWidth = 110;
        int previewHeight = Math.min(180, height - 90);
        PlayerSkinWidget preview = new PlayerSkinWidget(previewWidth, previewHeight, minecraft.getEntityModels(), Skins::preview);
        preview.setX(width / 2 - 170);
        preview.setY((height - previewHeight) / 2);
        addRenderableWidget(preview);

        Wardrobe.Choice now = Skins.choice(Skins.myId());
        int x = width / 2 - 40;
        int y = Math.max(28, height / 2 - 80);
        int looks = 0;
        int trails = 0;
        for (CapeEffects.Effect effect : CapeEffects.Effect.values()) {
            boolean on = now.effects().contains(effect.id());
            // Looks go in the left column, trails fill two columns under them
            int bx = effect.trail ? x + (trails % 2) * 102 : x + looks * 68;
            int by = effect.trail ? y + 36 + (trails / 2) * 24 : y + 12;
            int bw = effect.trail ? 98 : 64;
            if (effect.trail) trails++;
            else looks++;
            addRenderableWidget(Button.builder(Component.literal((on ? "§a" : "§7") + Lang.t(effect.label)), b -> toggle(effect))
                    .bounds(bx, by, bw, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void toggle(CapeEffects.Effect effect) {
        Wardrobe.Choice now = Skins.choice(Skins.myId());
        try {
            Skins.choose(Skins.myId(), now.toggled(effect));
            message = now.cape().isEmpty() ? Lang.t("Pick a cape in the wardrobe to see it!") : null;
        } catch (IOException e) {
            message = Lang.t("Couldn't save that: {0}", e.getMessage());
        }
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("Cape Effects"), width / 2, 10, 0xFFFFFFFF);
        int x = width / 2 - 40;
        int y = Math.max(28, height / 2 - 80);
        g.text(font, Lang.t("Looks"), x, y, 0xFFA0A0A0);
        g.text(font, Lang.t("Trails (they follow you while you play)"), x, y + 24, 0xFFA0A0A0);
        g.text(font, Lang.t("Green ones are on. Mix as many as you like!"), x, y + 112, 0xFF808080);
        if (message != null) g.centeredText(font, message, width / 2, height - 42, 0xFFFFFF55);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
