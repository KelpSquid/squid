package squidvoice;

import squid.audio.VoiceCodec;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Everyone you hear. Each speaker gets their own decoder and a small waiting line of packets (two, 40 ms), which
 * smooths out the network's bumps without a noticeable delay. Every 20 ms the mixer takes one frame from each
 * speaker, makes it quieter with distance and moves it left or right by where they stand from you, and plays the sum.
 */
final class Speakers {
    private static final AudioFormat FORMAT = new AudioFormat(VoiceCodec.RATE, 16, 2, true, false);
    private static final byte[] LOST = new byte[0];
    /** Packets waited for before a speaker starts, and the most kept (more means the network caught up in a rush). */
    private static final int START = 2;
    private static final int MOST = 6;

    /** Where you hear from: x, y, z and which way you face (degrees, Minecraft's yaw). */
    record Ear(double x, double y, double z, float yaw) {
    }

    static final class Speaker {
        final UUID id;
        private final VoiceCodec.Decoder decoder = new VoiceCodec.Decoder();
        private final ArrayDeque<byte[]> waiting = new ArrayDeque<>();
        private int lastSequence = -1;
        private boolean playing;
        private int missing;
        volatile boolean near;
        volatile float x, y, z;
        volatile long heard;

        Speaker(UUID id) {
            this.id = id;
        }

        synchronized void add(int sequence, byte[] packet) {
            if (lastSequence >= 0) {
                int gap = (sequence - lastSequence) & 0xFFFF;
                if (gap == 0 || gap > 0x8000) return; // a repeat, or late: it's already been played or skipped
                for (int i = 1; i < Math.min(gap, 4); i++) waiting.add(LOST); // a few missing ones are smoothed over
            }
            lastSequence = sequence;
            waiting.add(packet);
            while (waiting.size() > MOST) waiting.poll();
            heard = System.currentTimeMillis();
        }

        /** The next 20 ms of this speaker, or null if they're quiet. */
        synchronized short[] next() {
            if (!playing) {
                if (waiting.size() < START) return null;
                playing = true;
                missing = 0;
            }
            byte[] packet = waiting.poll();
            if (packet == null) {
                if (++missing > 4) {
                    playing = false; // they stopped talking
                    return null;
                }
                return decoder.decode(null);
            }
            missing = 0;
            return decoder.decode(packet == LOST ? null : packet);
        }

        boolean talking() {
            return System.currentTimeMillis() - heard < 300;
        }
    }

    private final Map<UUID, Speaker> speakers = new ConcurrentHashMap<>();
    private final Supplier<Ear> ear;
    private volatile Thread thread;
    private volatile int volume = 100;
    private volatile int distance = 48;
    private volatile String problem;

    Speakers(Supplier<Ear> ear) {
        this.ear = ear;
    }

    void setVolume(int volume) {
        this.volume = volume;
    }

    void setDistance(int distance) {
        this.distance = distance;
    }

    String problem() {
        return problem;
    }

    Map<UUID, Speaker> all() {
        return speakers;
    }

    /** A voice message from the server: who, near or not, the sequence number, where they are, and the packet. */
    void received(byte[] data) {
        if (data.length < 31) return;
        ByteBuffer in = ByteBuffer.wrap(data);
        UUID id = new UUID(in.getLong(), in.getLong());
        boolean near = in.get() == 0;
        int sequence = in.getShort() & 0xFFFF;
        float x = in.getFloat();
        float y = in.getFloat();
        float z = in.getFloat();
        byte[] packet = new byte[in.remaining()];
        in.get(packet);
        Speaker s = speakers.computeIfAbsent(id, Speaker::new);
        s.near = near;
        s.x = x;
        s.y = y;
        s.z = z;
        s.add(sequence, packet);
    }

    void remove(UUID id) {
        speakers.remove(id);
    }

    void clear() {
        speakers.clear();
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    /** Starts playing (if it isn't already). Stops by itself after a while of nobody talking. */
    synchronized void start() {
        if (thread != null) return;
        Thread t = new Thread(this::run, "Squid Voice speakers");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** How loud and how far left or right one speaker is: {left, right}. */
    static float[] place(Ear ear, Speaker s, int distance) {
        if (!s.near || ear == null) return new float[] {0.75f, 0.75f};
        double dx = s.x - ear.x();
        double dy = s.y - ear.y();
        double dz = s.z - ear.z();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Full volume up close, then fading smoothly to nothing at the server's distance
        double fade = d <= 4 ? 1 : Math.max(0, 1 - (d - 4) / Math.max(1, distance - 4));
        fade *= fade;
        // Minecraft's yaw: 0 faces +z, 90 faces -x. Your right hand points to (-cos, -sin) of it.
        double yaw = Math.toRadians(ear.yaw());
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double flat = Math.sqrt(dx * dx + dz * dz);
        double pan = flat < 0.5 ? 0 : (dx * rightX + dz * rightZ) / flat; // -1 left, 1 right
        double angle = (pan + 1) * Math.PI / 4;
        return new float[] {(float) (fade * Math.cos(angle) * Math.sqrt(2) * 0.75), (float) (fade * Math.sin(angle) * Math.sqrt(2) * 0.75)};
    }

    private void run() {
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, VoiceCodec.FRAME * 4 * 5); // 100 ms
            line.start();
            problem = null;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            problem = e.getMessage() == null ? e.toString() : e.getMessage();
            thread = null;
            return;
        }
        byte[] out = new byte[VoiceCodec.FRAME * 4];
        int[] left = new int[VoiceCodec.FRAME];
        int[] right = new int[VoiceCodec.FRAME];
        long quietSince = System.currentTimeMillis();
        try {
            while (thread == Thread.currentThread()) {
                java.util.Arrays.fill(left, 0);
                java.util.Arrays.fill(right, 0);
                Ear e = ear.get();
                float master = volume / 100f;
                boolean any = false;
                for (Speaker s : speakers.values()) {
                    short[] frame = s.next();
                    if (frame == null) continue;
                    any = true;
                    float[] g = place(e, s, distance);
                    for (int i = 0; i < frame.length; i++) {
                        left[i] += Math.round(frame[i] * g[0] * master);
                        right[i] += Math.round(frame[i] * g[1] * master);
                    }
                }
                if (any) quietSince = System.currentTimeMillis();
                else if (System.currentTimeMillis() - quietSince > 10_000) break; // nobody's talked for a while: let the sound device rest
                for (int i = 0; i < left.length; i++) {
                    int l = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, left[i]));
                    int r = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, right[i]));
                    out[4 * i] = (byte) l;
                    out[4 * i + 1] = (byte) (l >> 8);
                    out[4 * i + 2] = (byte) r;
                    out[4 * i + 3] = (byte) (r >> 8);
                }
                line.write(out, 0, out.length); // waits until there's room, which keeps this at one frame per 20 ms
            }
        } finally {
            line.drain();
            line.close();
            synchronized (this) {
                if (thread == Thread.currentThread()) thread = null;
            }
        }
    }
}
