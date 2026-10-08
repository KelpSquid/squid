package squid;

/**
 * What draws mods' shapes in the world (see {@link squid.api.WorldDraw}). Squid is built without Minecraft, so a
 * built-in part that's built with it (Squid Mods) gives one, which draws with Minecraft's own "gizmos". Colors are
 * 0xAARRGGBB, and onTop means it shows through walls.
 */
public interface WorldPainter {
    /** A box from one corner to the other: its edges in stroke and its sides in fill (either can be 0 for none). */
    void box(double x1, double y1, double z1, double x2, double y2, double z2, int stroke, float width, int fill, boolean onTop);

    void line(double x1, double y1, double z1, double x2, double y2, double z2, int color, float width, boolean onTop);

    /** Text that always faces you, centered on the spot. scale 1 is Minecraft's own size for floating text. */
    void text(String text, double x, double y, double z, int color, float scale, boolean onTop);

    /** Where the camera is: x, y, z. */
    double[] camera();
}
