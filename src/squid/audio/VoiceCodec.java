package squid.audio;

import java.util.Arrays;

/**
 * Squid Voice: Squid's own voice codec, made for talking in Minecraft. It squeezes 16 kHz speech into about 24 kbps
 * (a tenth of plain sound), the same basic way Opus and MP3 do: the sound is cut into 20 ms frames, each frame is
 * turned into 320 frequencies with an MDCT (overlapping the next frame by half, so the joins don't click), the
 * frequencies are grouped into 18 bands the way ears hear them (narrow low down, wide up high), and each band sends
 * its loudness plus its shape with as many bits as it deserves. Loud bands that ears notice get more bits; quiet ones
 * get none and are filled with matching soft noise instead of silence, so voices don't sound hollow.
 *
 * The bit budget is fixed per frame, and both ends work out the same bit split from the band loudnesses alone, so
 * nothing about the split has to be sent. Encoder and decoder each keep a little state (the overlap), so each player
 * gets their own pair.
 */
public final class VoiceCodec {
    public static final int RATE = 16_000;
    public static final int FRAME = 320;          // samples per 20 ms frame
    static final int N = FRAME * 2;               // MDCT window: two frames
    /** Band edges in frequency lines (0..320 is 0..8 kHz, so each line is 25 Hz). */
    static final int[] BANDS = {0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 52, 62, 74, 88, 106, 128, 160, 210, 320};
    static final int BAND_COUNT = BANDS.length - 1;
    /** Bits each frame gets (20 ms): 480 is 24 kbps. */
    public static final int BITS_PER_FRAME = 480;
    private static final float[] WINDOW = new float[N];

    static {
        for (int i = 0; i < N; i++) WINDOW[i] = (float) Math.sin(Math.PI / N * (i + 0.5));
    }

    private VoiceCodec() {
    }

    // ---- Encoding ----

    /** Turns 20 ms frames of sound into packets. One per talking player. */
    public static final class Encoder {
        private final float[] history = new float[FRAME]; // the previous frame, for the overlap
        private final int[] lastEnergy = new int[BAND_COUNT];

        /** One frame (320 samples) into a packet of BITS_PER_FRAME / 8 bytes. */
        public byte[] encode(short[] frame) {
            if (frame.length != FRAME) throw new IllegalArgumentException("a frame is " + FRAME + " samples");
            float[] block = new float[N];
            for (int i = 0; i < FRAME; i++) {
                block[i] = history[i] * WINDOW[i];
                block[FRAME + i] = frame[i] / 32768f * WINDOW[FRAME + i];
                history[i] = frame[i] / 32768f;
            }
            float[] spectrum = mdct(block);
            BitWriter out = new BitWriter(BITS_PER_FRAME);
            // Each band's loudness, in 1.5 dB steps (0 is silent), sent as a change from the band below
            int[] energy = new int[BAND_COUNT];
            for (int b = 0; b < BAND_COUNT; b++) {
                double sum = 0;
                for (int k = BANDS[b]; k < BANDS[b + 1]; k++) sum += spectrum[k] * spectrum[k];
                double rms = Math.sqrt(sum / (BANDS[b + 1] - BANDS[b]));
                energy[b] = rms < 1e-6 ? 0 : (int) Math.max(0, Math.min(63, Math.round(Math.log(rms / 1e-6) / Math.log(2) * 2)));
            }
            int previous = 0;
            for (int b = 0; b < BAND_COUNT; b++) {
                if (b == 0) out.write(energy[0], 6);
                else writeDelta(out, energy[b] - previous);
                previous = energy[b];
            }
            System.arraycopy(energy, 0, lastEnergy, 0, BAND_COUNT);
            // Each band's shape, with the bits the split gives it
            int[] bits = allocate(energy, BITS_PER_FRAME - out.used());
            for (int b = 0; b < BAND_COUNT; b++) {
                if (bits[b] == 0) continue;
                double scale = scale(energy[b]);
                int levels = 1 << bits[b];
                double step = STEP[bits[b]];
                for (int k = BANDS[b]; k < BANDS[b + 1]; k++) {
                    // Normalized to the band's loudness, then put in one of `levels` evenly spaced steps around 0
                    double v = spectrum[k] / scale;
                    int q = (int) Math.floor(v / step) + levels / 2;
                    out.write(Math.max(0, Math.min(levels - 1, q)), bits[b]);
                }
            }
            return out.bytes();
        }
    }

    // ---- Decoding ----

    /** Turns packets back into 20 ms frames of sound. One per player heard. */
    public static final class Decoder {
        private final float[] overlap = new float[FRAME];
        private long noise = 0x5EED;

        /** A packet back into one frame (320 samples). A null packet (lost) fades out what was playing. */
        public short[] decode(byte[] packet) {
            float[] spectrum = new float[FRAME];
            if (packet != null) {
                BitReader in = new BitReader(packet);
                int[] energy = new int[BAND_COUNT];
                int previous = 0;
                for (int b = 0; b < BAND_COUNT; b++) {
                    energy[b] = b == 0 ? in.read(6) : Math.max(0, Math.min(63, previous + readDelta(in)));
                    previous = energy[b];
                }
                int[] bits = allocate(energy, BITS_PER_FRAME - in.used());
                for (int b = 0; b < BAND_COUNT; b++) {
                    double scale = energy[b] == 0 ? 0 : scale(energy[b]);
                    if (bits[b] == 0) {
                        // No bits: soft noise at the band's loudness, so it doesn't go hollow
                        for (int k = BANDS[b]; k < BANDS[b + 1]; k++) spectrum[k] = (float) (scale * nextNoise() * 0.7);
                        continue;
                    }
                    int levels = 1 << bits[b];
                    double step = STEP[bits[b]];
                    for (int k = BANDS[b]; k < BANDS[b + 1]; k++) {
                        int q = in.read(bits[b]);
                        spectrum[k] = (float) ((q - levels / 2 + 0.5) * step * scale);
                    }
                }
            }
            float[] block = imdct(spectrum);
            short[] out = new short[FRAME];
            for (int i = 0; i < FRAME; i++) {
                float v = overlap[i] + block[i] * WINDOW[i];
                overlap[i] = block[FRAME + i] * WINDOW[FRAME + i];
                out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v * 32768)));
            }
            return out;
        }

        private double nextNoise() {
            noise = noise * 6364136223846793005L + 1442695040888963407L;
            return ((noise >>> 40) / (double) (1L << 24)) * 2 - 1;
        }
    }

    // ---- Shared by both ends ----

    /**
     * The step between levels for each number of bits: the best even spacing for values spread like speech
     * frequencies are (a bell curve), so a few bits still land close to the real value.
     */
    static final double[] STEP = {0, 1.596, 0.996, 0.586, 0.335, 0.188, 0.104};

    static double scale(int energy) {
        return 1e-6 * Math.pow(2, energy / 2.0);
    }

    /**
     * How many bits per line each band gets, from the band loudnesses alone: louder bands first (in 1.5 dB steps),
     * low ones a little ahead (voices live there), up to 6 bits a line. Both ends run exactly this, with whole numbers, so they agree.
     */
    static int[] allocate(int[] energy, int budget) {
        int[] bits = new int[BAND_COUNT];
        int[] priority = new int[BAND_COUNT];
        for (int b = 0; b < BAND_COUNT; b++) priority[b] = energy[b] == 0 ? -1000 : energy[b] * 4 - b * 2;
        int left = budget;
        while (true) {
            int best = -1;
            for (int b = 0; b < BAND_COUNT; b++) {
                int width = BANDS[b + 1] - BANDS[b];
                if (bits[b] >= 6 || priority[b] < 0 || width > left) continue;
                if (best < 0 || priority[b] > priority[best]) best = b;
            }
            if (best < 0) break;
            bits[best]++;
            left -= BANDS[best + 1] - BANDS[best];
            priority[best] -= 12; // each extra bit takes about 6 dB of noise away (16 is exactly that; 12 spreads bits a little wider, which measured best on voices)
        }
        return bits;
    }

    private static void writeDelta(BitWriter out, int delta) {
        // Small changes are common: 0 is "0", +-1 is "10x", +-2..5 is "110xx", bigger is "111" and 7 bits
        int d = Math.max(-63, Math.min(63, delta));
        int a = Math.abs(d);
        if (a == 0) {
            out.write(0, 1);
        } else if (a == 1) {
            out.write(0b10, 2);
            out.write(d < 0 ? 1 : 0, 1);
        } else if (a <= 5) {
            out.write(0b110, 3);
            out.write(a - 2, 2);
            out.write(d < 0 ? 1 : 0, 1);
        } else {
            out.write(0b111, 3);
            out.write(d + 63, 7);
        }
    }

    private static int readDelta(BitReader in) {
        if (in.read(1) == 0) return 0;
        if (in.read(1) == 0) return in.read(1) == 1 ? -1 : 1;
        if (in.read(1) == 0) {
            int a = in.read(2) + 2;
            return in.read(1) == 1 ? -a : a;
        }
        return in.read(7) - 63;
    }

    /** MDCT of N samples into N/2 frequencies (straightforward: frames are small). */
    static float[] mdct(float[] x) {
        int half = N / 2;
        float[] out = new float[half];
        for (int k = 0; k < half; k++) {
            double sum = 0;
            for (int n = 0; n < N; n++) sum += x[n] * COS[n * half + k];
            out[k] = (float) sum;
        }
        return out;
    }

    static float[] imdct(float[] x) {
        int half = N / 2;
        float[] out = new float[N];
        for (int n = 0; n < N; n++) {
            double sum = 0;
            for (int k = 0; k < half; k++) sum += x[k] * COS[n * half + k];
            out[n] = (float) (sum * 2 / half);
        }
        return out;
    }

    private static final float[] COS = new float[N * (N / 2)];

    static {
        int half = N / 2;
        for (int n = 0; n < N; n++) {
            for (int k = 0; k < half; k++) COS[n * half + k] = (float) Math.cos(Math.PI / half * (n + 0.5 + half / 2.0) * (k + 0.5));
        }
    }

    // ---- Bits in a packet ----

    static final class BitWriter {
        private final byte[] data;
        private int position;

        BitWriter(int bits) {
            data = new byte[(bits + 7) / 8];
        }

        void write(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                if (position >= data.length * 8) return; // the budget is full: the rest is dropped
                if (((value >>> i) & 1) != 0) data[position >>> 3] |= (byte) (0x80 >>> (position & 7));
                position++;
            }
        }

        int used() {
            return position;
        }

        byte[] bytes() {
            return Arrays.copyOf(data, data.length);
        }
    }

    static final class BitReader {
        private final byte[] data;
        private int position;

        BitReader(byte[] data) {
            this.data = data;
        }

        int read(int bits) {
            int v = 0;
            for (int i = 0; i < bits; i++) {
                int bit = position < data.length * 8 ? (data[position >>> 3] >>> (7 - (position & 7))) & 1 : 0;
                v = (v << 1) | bit;
                position++;
            }
            return v;
        }

        int used() {
            return position;
        }
    }
}
