package minimap;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;

/** The full-screen map, opened with M. Press M or Esc to close it. */
final class MapScreen extends Screen {
    private final MapImage map = new MapImage();
    private int refreshIn;

    MapScreen() {
        super(Component.literal("World Map"));
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (--refreshIn <= 0) {
            map.update(mc.level, mc.player.getBlockX(), mc.player.getBlockZ(), mapBlocks());
            refreshIn = 20; // once a second
        }
    }

    /** How many blocks across the map shows: as many as fit at 1 GUI pixel each, up to 512. */
    private int mapBlocks() {
        return Math.max(64, Math.min(512, Math.min(width, height) - 40));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (map.size() == 0) tick();
        int size = map.size();
        int x = (width - size) / 2;
        int y = (height - size) / 2 + 6;

        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF101820);
        g.fill(x, y, x + size, y + size, 0xFF1A2333);
        map.draw(g, x, y);
        Minimap.TRAIL.draw(g, map, x, y, 1);
        int[] spot = map.toPicture(player.getX(), player.getZ());
        if (spot != null) PlayerFaceExtractor.extractRenderState(g, player.getSkin(), x + spot[0] - 4, y + spot[1] - 4, 8);

        g.centeredText(font, "World Map", width / 2, y - 14, 0xFFFFFFFF);
        g.centeredText(font, "N", x + size / 2, y + 2, 0xFFFFFFFF);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_M) { // M closes it again
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the world keeps going while you look at the map
    }
}
