package squidmods;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import squid.WorldPainter;

/**
 * Draws mods' shapes in the world (squid.api.WorldDraw) with Minecraft's own "gizmos": the boxes, lines and floating
 * text Minecraft uses for its debug views and game tests. They're handed to Minecraft while it gathers what to draw
 * for a frame (the extract step), and Minecraft's new renderer draws them with the rest of the world.
 */
final class WorldGizmos implements WorldPainter {
    static final WorldGizmos PAINTER = new WorldGizmos();

    private WorldGizmos() {
    }

    @Override
    public void box(double x1, double y1, double z1, double x2, double y2, double z2, int stroke, float width, int fill, boolean onTop) {
        GizmoStyle style = stroke != 0 && fill != 0 ? GizmoStyle.strokeAndFill(stroke, width, fill)
                : fill != 0 ? GizmoStyle.fill(fill) : GizmoStyle.stroke(stroke, width);
        show(Gizmos.cuboid(new AABB(x1, y1, z1, x2, y2, z2), style), onTop);
    }

    @Override
    public void line(double x1, double y1, double z1, double x2, double y2, double z2, int color, float width, boolean onTop) {
        show(Gizmos.line(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), color, width), onTop);
    }

    @Override
    public void text(String text, double x, double y, double z, int color, float scale, boolean onTop) {
        TextGizmo.Style style = TextGizmo.Style.forColorAndCentered(color).withScale(TextGizmo.Style.DEFAULT_SCALE * scale);
        show(Gizmos.billboardText(text, new Vec3(x, y, z), style), onTop);
    }

    private static void show(GizmoProperties gizmo, boolean onTop) {
        if (onTop) gizmo.setAlwaysOnTop();
    }

    @Override
    public double[] camera() {
        Vec3 at = Minecraft.getInstance().gameRenderer.mainCamera().position();
        return new double[] {at.x, at.y, at.z};
    }
}
