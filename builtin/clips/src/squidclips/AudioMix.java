package squidclips;

import java.util.List;

/**
 * Mixes a video's sound track from scratch: every sound that played is placed at its moment, made quieter the farther
 * it was from the camera, panned left or right by where it was, and sped up or slowed down by its pitch. Nothing here
 * needs Minecraft, so it can be tested on its own. The result is 44.1 kHz stereo, 16-bit, like a CD.
 */
public final class AudioMix {
    public static final int RATE = 44_100;

    /**
     * One sound: when it started (seconds into the video), its samples (mono, or stereo left/right pairs), how loud and
     * high, and where it was. Relative sounds (like your own UI) play in the middle at full volume; with linear set,
     * the sound fades to nothing at distance blocks away, the way Minecraft fades it.
     */
    public record Hit(double time, short[] samples, int channels, int sampleRate, float volume, float pitch,
                      double x, double y, double z, boolean relative, boolean linear, float distance) {
    }

    /** Where the camera was, and which way it faced, at a moment of the video. */
    public interface Listener {
        /** x, y, z and yaw (Minecraft's degrees) at a time in seconds. */
        double[] at(double seconds);
    }

    private AudioMix() {
    }

    /** The mixed sound track, as left/right pairs, seconds long. */
    public static short[] mix(List<Hit> hits, Listener listener, double seconds) {
        int length = (int) Math.ceil(seconds * RATE);
        float[] left = new float[length];
        float[] right = new float[length];
        for (Hit hit : hits) add(hit, listener, left, right);
        short[] out = new short[length * 2];
        for (int i = 0; i < length; i++) {
            out[i * 2] = clip(left[i]);
            out[i * 2 + 1] = clip(right[i]);
        }
        return out;
    }

    private static void add(Hit hit, Listener listener, float[] left, float[] right) {
        if (hit.samples().length == 0 || hit.pitch() <= 0) return;
        float gainLeft;
        float gainRight;
        boolean stereo = hit.channels() == 2;
        if (hit.relative() || stereo) { // stereo sounds (like music) aren't placed in the world, in Minecraft either
            gainLeft = gainRight = hit.volume();
        } else {
            double[] ear = listener.at(hit.time());
            double dx = hit.x() - ear[0];
            double dy = hit.y() - ear[1];
            double dz = hit.z() - ear[2];
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float gain = hit.volume();
            if (hit.linear()) gain *= (float) Math.max(0, 1 - distance / Math.max(1, hit.distance()));
            if (gain <= 0) return;
            // Left or right: how far the sound is toward the camera's right-hand side (-1 left to 1 right)
            double yaw = Math.toRadians(ear[3]);
            double sideways = distance < 1e-6 ? 0 : (dx * -Math.cos(yaw) + dz * -Math.sin(yaw)) / distance;
            double pan = Math.max(-1, Math.min(1, sideways));
            gainLeft = (float) (gain * Math.sqrt((1 - pan) / 2) * Math.sqrt(2));
            gainRight = (float) (gain * Math.sqrt((1 + pan) / 2) * Math.sqrt(2));
        }
        int frames = hit.samples().length / hit.channels();
        double step = hit.sampleRate() / (double) RATE * hit.pitch(); // how far through the sound each output sample moves
        int start = (int) Math.round(hit.time() * RATE);
        for (int i = Math.max(0, -start); start + i < left.length; i++) {
            double at = i * step;
            int k = (int) at;
            if (k + 1 >= frames) break;
            double f = at - k;
            if (stereo) {
                left[start + i] += gainLeft * (float) lerp(hit.samples()[k * 2], hit.samples()[k * 2 + 2], f);
                right[start + i] += gainRight * (float) lerp(hit.samples()[k * 2 + 1], hit.samples()[k * 2 + 3], f);
            } else {
                float sample = (float) lerp(hit.samples()[k], hit.samples()[k + 1], f);
                left[start + i] += gainLeft * sample;
                right[start + i] += gainRight * sample;
            }
        }
    }

    private static double lerp(short a, short b, double f) {
        return a + (b - a) * f;
    }

    /** Keeps a sample inside 16 bits, softly, so many loud sounds at once crunch less. */
    private static short clip(float v) {
        if (Math.abs(v) > 24_000) v = Math.signum(v) * (24_000 + (float) Math.tanh((Math.abs(v) - 24_000) / 8_767.0) * 8_767);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
    }
}
