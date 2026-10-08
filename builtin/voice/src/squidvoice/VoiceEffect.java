package squidvoice;

/**
 * A voice changer for voice chat, run on your microphone 20 ms at a time before it's sent: Robot, Chipmunk (higher),
 * Giant (deeper) and Echo. Chipmunk and Giant change the pitch without changing the speed, so you can still talk
 * normally. Nothing here needs Minecraft, so it can be tested on its own.
 */
final class VoiceEffect {
    static final String NONE = "None";
    static final String[] ALL = {NONE, "Robot", "Chipmunk", "Giant", "Echo"};

    private final String kind;
    private final int rate;
    // The pitch changer: the last moments of your voice, read back faster or slower by two crossfading read heads
    private final float[] past = new float[8192];
    private long written;
    private double phase;
    private final int window;
    // The robot's hum and the echo's delay
    private long samples;
    private final float[] echo;
    private int echoAt;

    VoiceEffect(String kind, int rate) {
        this.kind = kind;
        this.rate = rate;
        this.window = Math.max(64, rate * 40 / 1000); // 40 ms
        this.echo = new float[Math.max(1, rate / 4)]; // a quarter of a second
    }

    String kind() {
        return kind;
    }

    /** Changes a frame of sound in place. */
    void process(short[] frame) {
        switch (kind) {
            case "Robot" -> robot(frame);
            case "Chipmunk" -> pitch(frame, 1.5);
            case "Giant" -> pitch(frame, 0.7);
            case "Echo" -> echo(frame);
            default -> {
            }
        }
    }

    /** Multiplied by a low hum, the classic robot voice. The hum carries on from frame to frame, so there are no clicks. */
    private void robot(short[] frame) {
        for (int i = 0; i < frame.length; i++) {
            double hum = Math.sin(2 * Math.PI * 50 * samples++ / rate);
            frame[i] = clamp(frame[i] * hum * 1.4);
        }
    }

    /**
     * Higher or lower without getting faster or slower: two read heads go through the last 40 ms of sound at the new
     * speed, each fading in and out (a triangle) half a window apart, so one is always loud while the other jumps back.
     */
    private void pitch(short[] frame, double factor) {
        int size = past.length;
        double step = (1 - factor) / window; // how fast the delay changes
        for (int i = 0; i < frame.length; i++) {
            past[(int) Math.floorMod(written, (long) size)] = frame[i];
            double out = 0;
            for (int head = 0; head < 2; head++) {
                double p = phase + head * 0.5;
                p -= Math.floor(p);
                double delay = p * window + 1;
                double at = written - delay;
                long a = (long) Math.floor(at);
                double between = at - a;
                float x0 = past[(int) Math.floorMod(a, (long) size)];
                float x1 = past[(int) Math.floorMod(a + 1, (long) size)];
                double gain = 1 - Math.abs(2 * p - 1);
                out += (x0 + (x1 - x0) * between) * gain;
            }
            phase += step;
            phase -= Math.floor(phase);
            written++;
            frame[i] = clamp(out);
        }
    }

    /** What you said, again a quarter of a second later, quieter each time. */
    private void echo(short[] frame) {
        for (int i = 0; i < frame.length; i++) {
            float delayed = echo[echoAt];
            float out = frame[i] + delayed * 0.45f;
            echo[echoAt] = out;
            echoAt = (echoAt + 1) % echo.length;
            frame[i] = clamp(out * 0.8);
        }
    }

    private static short clamp(double v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
    }
}
