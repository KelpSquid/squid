package squidpaint;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.util.function.Consumer;

/**
 * Records a short sound from the microphone for the Sound Swapper, so you can be the pig. It records until you press
 * Stop (10 seconds at most), then the quiet bits at the start and end are cut off and it's made nice and loud. The
 * microphone is only open while it records.
 */
final class Recorder {
    static final int RATE = 44_100;
    static final int MOST_SECONDS = 10;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);

    private volatile Thread thread;
    private volatile float level;
    private volatile long started;

    /** Whether it's recording right now. */
    boolean recording() {
        return thread != null;
    }

    /** How loud the microphone is (0 to 1), for the level bar. */
    float level() {
        return level;
    }

    /** How long it's been recording, in seconds. */
    double seconds() {
        return thread == null ? 0 : (System.nanoTime() - started) / 1e9;
    }

    /**
     * Starts recording. When it's stopped (or the 10 seconds are up) done gets the sound, already tidied, which is
     * empty if nothing was heard; or problem gets why the microphone couldn't open. Both are called on the
     * recording thread.
     */
    synchronized void start(Consumer<short[]> done, Consumer<String> problem) {
        if (thread != null) return;
        Thread t = new Thread(() -> run(done, problem), "Squid Sound Swapper mic");
        t.setDaemon(true);
        thread = t;
        started = System.nanoTime();
        t.start();
    }

    /** Stops recording; the sound goes to done. */
    synchronized void stop() {
        thread = null;
    }

    private void run(Consumer<short[]> done, Consumer<String> problem) {
        TargetDataLine line;
        try {
            line = AudioSystem.getTargetDataLine(FORMAT);
            line.open(FORMAT, RATE / 10 * 2); // a tenth of a second
            line.start();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            if (thread == Thread.currentThread()) thread = null;
            problem.accept(e.getMessage() == null ? e.toString() : e.getMessage());
            return;
        }
        short[] sound = new short[RATE * MOST_SECONDS];
        int got = 0;
        byte[] bytes = new byte[RATE / 50 * 2]; // 20 ms at a time
        try {
            while (thread == Thread.currentThread() && got < sound.length) {
                int n = line.read(bytes, 0, bytes.length);
                double sum = 0;
                for (int i = 0; i + 1 < n && got < sound.length; i += 2) {
                    short v = (short) ((bytes[i] & 0xFF) | (bytes[i + 1] << 8));
                    sound[got++] = v;
                    sum += (double) v * v;
                }
                level = (float) Math.min(1, Math.sqrt(sum / Math.max(1, n / 2)) / 32768 * 8);
            }
        } finally {
            line.stop();
            line.close();
            if (thread == Thread.currentThread()) thread = null; // and not a new recording started since
            level = 0;
        }
        short[] recorded = new short[got];
        System.arraycopy(sound, 0, recorded, 0, got);
        done.accept(tidy(recorded, RATE));
    }

    /**
     * Cuts off the quiet at the start and end (keeping a little, so the sound doesn't start with a click), fades the
     * very edges, and makes the loudest part nearly as loud as a sound can be. Empty if it was all quiet.
     */
    static short[] tidy(short[] sound, int rate) {
        int window = Math.max(1, rate / 100); // 10 ms
        int windows = sound.length / window;
        double loudest = 0;
        double[] rms = new double[windows];
        for (int w = 0; w < windows; w++) {
            double sum = 0;
            for (int i = w * window; i < (w + 1) * window; i++) sum += (double) sound[i] * sound[i];
            rms[w] = Math.sqrt(sum / window);
            loudest = Math.max(loudest, rms[w]);
        }
        // Quiet means much quieter than the loudest part, and anything under about -50 dB is quiet anyway
        double quiet = Math.max(loudest * 0.08, 100);
        int first = -1;
        int last = -1;
        for (int w = 0; w < windows; w++) {
            if (rms[w] > quiet) {
                if (first < 0) first = w;
                last = w;
            }
        }
        if (first < 0) return new short[0];
        int pad = 5; // 50 ms
        int from = Math.max(0, first - pad) * window;
        int to = Math.min(sound.length, (last + 1 + pad) * window);
        int peak = 1;
        for (int i = from; i < to; i++) peak = Math.max(peak, Math.abs(sound[i]));
        double gain = Math.min(20, 0.9 * 32767 / peak); // up to 20 times louder, so a far-away mic still works
        int fade = Math.min((to - from) / 2, rate / 200); // 5 ms
        short[] out = new short[to - from];
        for (int i = 0; i < out.length; i++) {
            double edge = Math.min(1, Math.min(i, out.length - 1 - i) / (double) Math.max(1, fade));
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sound[from + i] * gain * edge)));
        }
        return out;
    }
}
