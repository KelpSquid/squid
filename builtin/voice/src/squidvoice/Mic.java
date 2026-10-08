package squidvoice;

import squid.audio.VoiceCodec;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.util.function.Consumer;

/**
 * The microphone. It's only open while you're on a server with voice chat and someone else is there, and it only
 * sends while you talk (holding the talk key, or speaking out loud in "Always on"). Each 20 ms of sound is squeezed
 * with Squid Voice and handed on with a sequence number, so the other end can tell when one went missing.
 */
final class Mic {
    private static final AudioFormat FORMAT = new AudioFormat(VoiceCodec.RATE, 16, 1, true, false);
    /** In Always on, sound louder than this counts as talking (about -44 dB, a quiet voice close to the mic). */
    private static final double GATE = 0.006;
    /** And talking goes on this many frames after the voice stops, so word endings aren't cut. */
    private static final int HOLD = 15;

    private final Consumer<byte[]> send;
    private volatile Thread thread;
    private volatile boolean pushed;
    private volatile boolean alwaysOn;
    private volatile int gain = 100;
    private volatile boolean talking;
    private volatile float level;
    private volatile String problem;

    Mic(Consumer<byte[]> send) {
        this.send = send;
    }

    /** Opens the microphone (if it isn't open yet). */
    synchronized void open() {
        if (thread != null) return;
        Thread t = new Thread(this::run, "Squid Voice mic");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Closes the microphone. */
    synchronized void close() {
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        talking = false;
        level = 0;
    }

    boolean isOpen() {
        return thread != null;
    }

    void set(boolean pushed, boolean alwaysOn, int gain) {
        this.pushed = pushed;
        this.alwaysOn = alwaysOn;
        this.gain = gain;
    }

    /** Whether your voice is being sent right now. */
    boolean talking() {
        return talking;
    }

    /** How loud the mic is (0 to 1), for the level bar. */
    float level() {
        return level;
    }

    /** Why the mic couldn't open, or null. */
    String problem() {
        return problem;
    }

    private void run() {
        TargetDataLine line;
        try {
            line = AudioSystem.getTargetDataLine(FORMAT);
            line.open(FORMAT, VoiceCodec.FRAME * 2 * 4);
            line.start();
            problem = null;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            problem = e.getMessage() == null ? e.toString() : e.getMessage();
            thread = null;
            return;
        }
        byte[] bytes = new byte[VoiceCodec.FRAME * 2];
        short[] frame = new short[VoiceCodec.FRAME];
        VoiceCodec.Encoder encoder = null;
        int sequence = 0;
        int hold = 0;
        try {
            while (thread == Thread.currentThread()) {
                int got = 0;
                while (got < bytes.length && thread == Thread.currentThread()) got += line.read(bytes, got, bytes.length - got);
                if (got < bytes.length) break;
                double sum = 0;
                float g = gain / 100f;
                for (int i = 0; i < frame.length; i++) {
                    int v = Math.round(((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8)) * g);
                    frame[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
                    sum += (double) frame[i] * frame[i];
                }
                double rms = Math.sqrt(sum / frame.length) / 32768;
                level = (float) Math.min(1, rms * 8);
                boolean now;
                if (alwaysOn) {
                    if (rms > GATE) hold = HOLD;
                    now = hold-- > 0;
                } else {
                    now = pushed;
                }
                if (now && encoder == null) encoder = new VoiceCodec.Encoder(); // a fresh start, so nothing old leaks in
                if (!now) encoder = null;
                talking = now;
                if (now) {
                    byte[] packet = encoder.encode(frame);
                    byte[] message = new byte[2 + packet.length];
                    message[0] = (byte) (sequence >> 8);
                    message[1] = (byte) sequence;
                    System.arraycopy(packet, 0, message, 2, packet.length);
                    sequence = (sequence + 1) & 0xFFFF;
                    send.accept(message);
                }
            }
        } finally {
            line.stop();
            line.close();
            talking = false;
            level = 0;
        }
    }
}
