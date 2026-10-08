package squidemotes;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import squid.Lang;

/**
 * The emote wheel: the eight emotes in a circle around the middle of the screen. Click one (or press its number, 1 to
 * 8) and it's sent; Esc closes it. The game keeps going behind it.
 */
final class EmoteScreen extends Screen {
    EmoteScreen() {
        super(Component.literal(Lang.t("Emotes")));
    }

    @Override
    protected void init() {
        int count = Emotes.ALL.length;
        int radius = Math.min(80, Math.min(width, height) / 2 - 24);
        for (int i = 0; i < count; i++) {
            int emote = i;
            double angle = -Math.PI / 2 + i * 2 * Math.PI / count; // the first at the top, going clockwise
            int x = width / 2 + (int) Math.round(Math.cos(angle) * radius) - 30;
            int y = height / 2 + (int) Math.round(Math.sin(angle) * radius) - 10;
            addRenderableWidget(Button.builder(Component.literal((i + 1) + " " + Lang.t(Emotes.ALL[i][0])), b -> pick(emote))
                    .bounds(x, y, 60, 20).build());
        }
    }

    private void pick(int emote) {
        EmotesClient.send(emote);
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // 1 to 8 pick an emote, by the character the key types (so it works on every keyboard)
        int typed = event.shortcutKey();
        if (typed >= '1' && typed < '1' + Emotes.ALL.length) {
            pick(typed - '1');
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("Emotes"), width / 2, height / 2 - 4, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
