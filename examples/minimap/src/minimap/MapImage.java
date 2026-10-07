package minimap;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** A square picture of the world from above, one pixel per block, like a paper map. */
final class MapImage {
    private int[] colors = new int[0];
    private int size;
    private int left;  // the world X of the picture's left edge
    private int top;   // the world Z of the picture's top edge

    /** Looks at every column of blocks around the center and remembers the color of the top block. */
    void update(Level level, int centerX, int centerZ, int size) {
        int[] fresh = new int[size * size];
        int startX = centerX - size / 2;
        int startZ = centerZ - size / 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                fresh[dz * size + dx] = colorAt(level, pos, startX + dx, startZ + dz);
            }
        }
        colors = fresh;
        this.size = size;
        left = startX;
        top = startZ;
    }

    /** The color of the top block, a little lighter on slopes facing north and darker facing south, like real maps. */
    private static int colorAt(Level level, BlockPos.MutableBlockPos pos, int x, int z) {
        int height = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        pos.set(x, height - 1, z);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return 0; // nothing loaded there yet
        MapColor color = state.getMapColor(level, pos);
        if (color == MapColor.NONE) return 0;
        int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1);
        MapColor.Brightness shade = height > north ? MapColor.Brightness.HIGH
                : height < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        return color.calculateARGBColor(shade);
    }

    /**
     * Draws the picture with its top-left corner at x, y. Neighbouring blocks of the same color are drawn as one
     * strip, which is much faster than drawing every block on its own.
     */
    void draw(GuiGraphicsExtractor g, int x, int y) {
        for (int row = 0; row < size; row++) {
            int start = 0;
            for (int col = 1; col <= size; col++) {
                if (col == size || colors[row * size + col] != colors[row * size + start]) {
                    int color = colors[row * size + start];
                    if (color != 0) g.fill(x + start, y + row, x + col, y + row + 1, color);
                    start = col;
                }
            }
        }
    }

    /** Where a world position lands on the picture, or null if it's off the picture. */
    int[] toPicture(double worldX, double worldZ) {
        int px = (int) Math.floor(worldX) - left;
        int py = (int) Math.floor(worldZ) - top;
        return px >= 0 && py >= 0 && px < size && py < size ? new int[] {px, py} : null;
    }

    int size() {
        return size;
    }
}
