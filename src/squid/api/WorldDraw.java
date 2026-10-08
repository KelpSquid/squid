package squid.api;

import squid.WorldPainter;

/**
 * Draws in the world, not just on the screen: outlines around blocks, boxes, lines, floating text and waypoints.
 * {@link Squid#onWorldDraw} hands one to the mod every frame, and what it draws shows for that frame. Positions are
 * world coordinates (block x, y, z), and colors are names like "red" or numbers like 0xFF8800 (see {@link Colors}).
 *
 * <pre>
 * squid.onWorldDraw(draw -&gt; {
 *     draw.block(0, 64, 0, "gold");
 *     draw.text("Spawn", 0.5, 66, 0.5, "white");
 * });
 * </pre>
 */
public final class WorldDraw {
    private final WorldPainter painter;
    private boolean throughWalls;
    private double[] camera;

    public WorldDraw(WorldPainter painter) {
        this.painter = painter;
    }

    /** From now on (this frame), shapes show through walls, or stop doing so. Starts off. */
    public WorldDraw throughWalls(boolean on) {
        throughWalls = on;
        return this;
    }

    /** An outline around one block. */
    public void block(int x, int y, int z, Object color) {
        box(x, y, z, x + 1, y + 1, z + 1, color);
    }

    /** A block filled with see-through color, with an outline. */
    public void filledBlock(int x, int y, int z, Object color) {
        filledBox(x, y, z, x + 1, y + 1, z + 1, color);
    }

    /** The outline of a box from one corner to the other. */
    public void box(double x1, double y1, double z1, double x2, double y2, double z2, Object color) {
        painter.box(x1, y1, z1, x2, y2, z2, Colors.of(color), 2.5f, 0, throughWalls);
    }

    /**
     * A box filled with color, with an outline. A color without see-through (like "red") is made a quarter solid,
     * so what's inside still shows; give 0x80FF0000 for your own amount.
     */
    public void filledBox(double x1, double y1, double z1, double x2, double y2, double z2, Object color) {
        int argb = Colors.of(color);
        int fill = (argb >>> 24) == 0xFF ? Colors.withAlpha(argb, 0.25) : argb;
        painter.box(x1, y1, z1, x2, y2, z2, argb | 0xFF000000, 2.5f, fill, throughWalls);
    }

    /** A line from one spot to another. */
    public void line(double x1, double y1, double z1, double x2, double y2, double z2, Object color) {
        line(x1, y1, z1, x2, y2, z2, color, 2.5f);
    }

    /** A line, this many pixels wide. */
    public void line(double x1, double y1, double z1, double x2, double y2, double z2, Object color, float width) {
        painter.line(x1, y1, z1, x2, y2, z2, Colors.of(color), width, throughWalls);
    }

    /** Text floating at a spot, always facing you. */
    public void text(Object text, double x, double y, double z, Object color) {
        text(text, x, y, z, color, 1);
    }

    /** Floating text, this many times Minecraft's size for it. */
    public void text(Object text, double x, double y, double z, Object color, double size) {
        painter.text(String.valueOf(text), x, y, z, Colors.of(color), (float) size, throughWalls);
    }

    /**
     * A waypoint at a block: an outline, a beam of light up into the sky, and its name with how far away it is, which
     * show through walls and stay readable from far away.
     */
    public void waypoint(Object name, int x, int y, int z, Object color) {
        int argb = Colors.of(color);
        double cx = x + 0.5;
        double cz = z + 0.5;
        painter.box(x, y, z, x + 1, y + 1, z + 1, argb, 2.5f, Colors.withAlpha(argb, 0.2), false);
        painter.line(cx, y + 1, cz, cx, y + 256, cz, Colors.withAlpha(argb, 0.6), 4f, false);
        double[] eye = camera();
        double dx = cx - eye[0];
        double dy = y + 1.5 - eye[1];
        double dz = cz - eye[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Far away, the name is drawn nearer along the way there (beyond that the game wouldn't draw it), and bigger,
        // so it always reads about the same size
        double shown = Math.min(distance, 48);
        double scale = distance < 0.01 ? 0 : shown / distance;
        String label = name + " (" + Math.round(distance) + "m)";
        painter.text(label, eye[0] + dx * scale, eye[1] + dy * scale, eye[2] + dz * scale, argb | 0xFF000000,
                (float) Math.max(1, shown / 8), true);
    }

    /** How far the camera is from a spot, in blocks. */
    public double distanceTo(double x, double y, double z) {
        double[] eye = camera();
        double dx = x - eye[0];
        double dy = y - eye[1];
        double dz = z - eye[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double[] camera() {
        if (camera == null) camera = painter.camera(); // once a frame
        return camera;
    }
}
