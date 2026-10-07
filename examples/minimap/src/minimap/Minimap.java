package minimap;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.LocalPlayer;
import squid.api.Hud;
import squid.api.KeyBinding;
import squid.api.Squid;
import squid.api.SquidMod;

/**
 * A map of the world around you in the top-left corner, with your face in the middle and a red dotted line
 * showing where you've been since you joined. C makes it bigger, M opens the full map.
 */
public class Minimap implements SquidMod {
    static final Trail TRAIL = new Trail();

    private static final int SMALL = 64;   // blocks across, and GUI pixels across
    private static final int BIG = 112;
    private static final int EDGE = 4;     // gap between the map and the screen's edge

    private final MapImage map = new MapImage();
    private KeyBinding biggerKey;
    private KeyBinding mapKey;
    private boolean big;
    private int refreshIn;

    @Override
    public void init(Squid squid) {
        biggerKey = squid.addKeyBinding("Bigger Minimap", InputConstants.KEY_C);
        mapKey = squid.addKeyBinding("World Map", InputConstants.KEY_M);
        squid.onTick(this::tick);
        squid.onHud(this::draw);
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean openMap = false;
        while (biggerKey.pressed()) big = !big;
        while (mapKey.pressed()) openMap = true;

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            TRAIL.clear(); // left the world
            return;
        }
        TRAIL.record(mc.level, player.getX(), player.getZ());
        if (openMap && mc.gui.screen() == null) mc.setScreenAndShow(new MapScreen());

        // Looking at thousands of blocks every frame would be slow, so the picture is redrawn 4 times a second
        if (--refreshIn <= 0) {
            map.update(mc.level, player.getBlockX(), player.getBlockZ(), big ? BIG : SMALL);
            refreshIn = 5;
        }
    }

    private void draw(Hud hud) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || map.size() == 0) return;
        GuiGraphicsExtractor g = (GuiGraphicsExtractor) hud.graphics();
        int size = map.size();

        hud.box(EDGE - 1, EDGE - 1, size + 2, size + 2, 0xFF101820);  // dark frame
        hud.box(EDGE, EDGE, size, size, 0xFF1A2333);                  // background where nothing is loaded yet
        map.draw(g, EDGE, EDGE);
        TRAIL.draw(g, map, EDGE, EDGE, 1);

        // The player's own face (front of their skin) marks where they are
        int[] spot = map.toPicture(player.getX(), player.getZ());
        if (spot != null) PlayerFaceExtractor.extractRenderState(g, player.getSkin(), EDGE + spot[0] - 4, EDGE + spot[1] - 4, 8);

        hud.centeredText("N", EDGE + size / 2, EDGE + 2, 0xFFFFFFFF);
        String coords = player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ();
        hud.centeredText(coords, EDGE + size / 2, EDGE + size + 4, 0xFFFFFFFF);
    }
}
