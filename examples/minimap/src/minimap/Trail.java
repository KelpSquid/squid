package minimap;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/** Every block the player has walked through since joining the world, drawn on the map as a red dotted line. */
final class Trail {
    private static final int RED = 0xFFE03030;
    private static final int MAX_POINTS = 50_000; // about 50 km of walking

    private final List<int[]> points = new ArrayList<>();
    private Level level; // the world the trail belongs to

    /** Adds the player's block to the trail if they moved to a new one. A new world starts a new trail. */
    void record(Level now, double x, double z) {
        if (now != level) {
            points.clear();
            level = now;
        }
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!points.isEmpty()) {
            int[] last = points.get(points.size() - 1);
            if (last[0] == bx && last[1] == bz) return;
        }
        if (points.size() >= MAX_POINTS) points.remove(0);
        points.add(new int[] {bx, bz});
    }

    void clear() {
        points.clear();
        level = null;
    }

    /** Draws every other point, which makes the line dotted. pixelsPerBlock is how big one block is on the map. */
    void draw(GuiGraphicsExtractor g, MapImage map, int x, int y, int pixelsPerBlock) {
        for (int i = 0; i < points.size(); i += 2) {
            int[] p = points.get(i);
            int[] spot = map.toPicture(p[0] + 0.5, p[1] + 0.5);
            if (spot == null) continue;
            int px = x + spot[0] * pixelsPerBlock;
            int py = y + spot[1] * pixelsPerBlock;
            g.fill(px, py, px + pixelsPerBlock, py + pixelsPerBlock, RED);
        }
    }
}
