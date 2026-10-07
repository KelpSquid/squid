package squidreplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Camera keyframes, Skate 3 style: drop a few along the timeline (where the camera is, where it looks, how wide the
 * lens is) and the camera glides smoothly through them as the replay plays. Plain math, so it's tested on its own.
 */
public final class CameraPath {
    /** One keyframe: at a time (in ticks), the camera was here, looking this way, with this field of view. */
    public record Key(double time, double x, double y, double z, float yaw, float pitch, float fov) {
    }

    private final List<Key> keys = new ArrayList<>();

    /** Adds a keyframe; one already within half a tick of it is replaced. */
    public void add(Key key) {
        keys.removeIf(k -> Math.abs(k.time() - key.time()) < 0.5);
        keys.add(key);
        keys.sort(Comparator.comparingDouble(Key::time));
    }

    /** Removes the keyframe nearest a time (within a second), and says whether there was one. */
    public boolean removeNear(double time) {
        Key nearest = null;
        for (Key k : keys) {
            if (Math.abs(k.time() - time) <= 20 && (nearest == null || Math.abs(k.time() - time) < Math.abs(nearest.time() - time))) nearest = k;
        }
        return nearest != null && keys.remove(nearest);
    }

    public List<Key> keys() {
        return List.copyOf(keys);
    }

    public int size() {
        return keys.size();
    }

    /**
     * Where the camera is at a time: on a smooth curve through the keyframes (Catmull-Rom), held still before the
     * first and after the last. Needs at least one keyframe.
     */
    public Key at(double time) {
        if (keys.isEmpty()) throw new IllegalStateException("no keyframes");
        if (time <= keys.get(0).time()) return keys.get(0);
        Key last = keys.get(keys.size() - 1);
        if (time >= last.time()) return last;
        int i = 0;
        while (keys.get(i + 1).time() < time) i++;
        Key p1 = keys.get(i);
        Key p2 = keys.get(i + 1);
        Key p0 = i > 0 ? keys.get(i - 1) : p1;
        Key p3 = i + 2 < keys.size() ? keys.get(i + 2) : p2;
        double t = (time - p1.time()) / (p2.time() - p1.time());
        return new Key(time, spline(p0.x(), p1.x(), p2.x(), p3.x(), t), spline(p0.y(), p1.y(), p2.y(), p3.y(), t),
                spline(p0.z(), p1.z(), p2.z(), p3.z(), t), Timeline.angle(p1.yaw(), p2.yaw(), (float) smooth(t)),
                (float) (p1.pitch() + (p2.pitch() - p1.pitch()) * smooth(t)), (float) (p1.fov() + (p2.fov() - p1.fov()) * smooth(t)));
    }

    /** A Catmull-Rom curve: passes through b at 0 and c at 1, bending smoothly toward a before and d after. */
    static double spline(double a, double b, double c, double d, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
    }

    /** Eases in and out, so turns start and stop gently. */
    static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /** The yaw and pitch (Minecraft's degrees) for looking from one point at another. */
    public static float[] lookAt(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[] {yaw + 0f, pitch + 0f}; // + 0 turns -0 into 0
    }
}
