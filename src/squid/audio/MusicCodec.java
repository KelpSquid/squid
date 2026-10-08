package squid.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Squid Music: Squid's own codec for music and sounds, the sound inside .sqda files. It works like AAC and Vorbis:
 *
 * - The sound is cut into frames of 1024 samples. Each frame is turned into 1024 frequencies with an MDCT, using a
 *   2048-sample window that overlaps the frames on each side, so the joins never click.
 * - Where a sharp sound starts (a drum hit), the frame is cut into 8 short blocks instead, so the hit doesn't smear
 *   into a soft "pre-echo" before it.
 * - The frequencies are grouped into bands the way ears hear them. A hearing model works out how much noise each band
 *   can hide: loud sounds hide quieter ones near them (a hiss hides more than a clear note does), and sounds too
 *   quiet to hear at all aren't sent. Each band gets just enough detail that its noise stays hidden.
 * - Stereo bands that are nearly the same on both sides are sent as middle and side, which is smaller.
 * - Bands left out high up are filled with soft noise at their loudness, so high sounds don't go hollow.
 * - Everything is packed with a range coder whose models learn what this music is like as it goes.
 *
 * Quality runs from 0 (small) to 10 (closest to the original), 6 by default. Every frame can be decoded with only
 * the ones since the last multiple of GROUP before it, so music can loop and skip.
 */
public final class MusicCodec {
    public static final int FRAME = 1024;
    static final int N = FRAME * 2;
    static final int SHORT_N = 256;
    static final int SHORTS = 8;
    static final int SHORT_LINES = SHORT_N / 2;
    /** Where the 8 short blocks start inside a frame's 2048-sample window (they cover its middle). */
    static final int SHORT_START = N / 4 - SHORT_N / 4; // 448
    static final int LONG = 0;
    static final int START = 1;
    static final int SHORT = 2;
    static final int STOP = 3;
    /** The models start over every this many frames, so playback can start at any multiple of it. */
    public static final int GROUP = 16;
    public static final int DEFAULT_QUALITY = 6;

    static final int[] LONG_BANDS = bands(FRAME, 0.09);
    static final int[] SHORT_BANDS = bands(SHORT_LINES, 0.16);
    private static final float[] LONG_WINDOW = sine(N);
    private static final float[] SHORT_WINDOW = sine(SHORT_N);
    private static final float[][] WINDOWS = {window(LONG), window(START), null, window(STOP)};

    /** The smallest step between levels is 2^-24, and each scale step is a quarter of a doubling (1.5 dB). */
    private static final double BASE = Math.pow(2, -24);
    private static final int SCALES = 160;
    private static final int NOISES = 128;

    private MusicCodec() {
    }

    /** Band edges in frequency lines: 4 lines wide at the bottom, then growing by `growth` each band, in fours. */
    static int[] bands(int lines, double growth) {
        List<Integer> edges = new ArrayList<>();
        edges.add(0);
        int at = 0;
        while (at < lines) {
            int width = Math.max(4, (int) (at * growth) / 4 * 4);
            at = Math.min(lines, at + width);
            edges.add(at);
        }
        return edges.stream().mapToInt(Integer::intValue).toArray();
    }

    private static float[] sine(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) w[i] = (float) Math.sin(Math.PI / n * (i + 0.5));
        return w;
    }

    /**
     * The window over a long frame. Its left half matches the frame before: a long fade, or (after short blocks) a
     * short fade in the middle. Its right half does the same for the frame after.
     */
    private static float[] window(int type) {
        float[] w = new float[N];
        int s = SHORT_START;
        for (int i = 0; i < FRAME; i++) {
            if (type == STOP) w[i] = i < s ? 0 : i < s + SHORT_LINES ? SHORT_WINDOW[i - s] : 1;
            else w[i] = LONG_WINDOW[i];
        }
        for (int i = FRAME; i < N; i++) {
            int j = i - FRAME;
            if (type == START) w[i] = j < s ? 1 : j < s + SHORT_LINES ? SHORT_WINDOW[SHORT_LINES + j - s] : 0;
            else w[i] = LONG_WINDOW[i];
        }
        return w;
    }

    // ---- The MDCT (through Vorbis's DCT-IV, which goes through an FFT) ----

    /** n samples into n/2 frequencies: X[k] = sum of x[i] cos(pi / (n/2) (i + 1/2 + n/4)(k + 1/2)). */
    static float[] mdct(float[] x, int n) {
        int m = n / 2;
        int h = m / 2;
        float[] u = new float[m];
        for (int i = 0; i < m; i++) {
            float v = -x[3 * h - 1 - i];
            if (i >= h) v += x[i - h];
            else v -= x[i + 3 * h];
            u[i] = v;
        }
        double[] c = Vorbis.dct4(u, m);
        float[] out = new float[m];
        for (int k = 0; k < m; k++) out[k] = (float) c[k];
        return out;
    }

    /** n/2 frequencies back into n samples, scaled so that windowed blocks overlapped add back to the sound. */
    static float[] imdct(float[] x, int n) {
        float[] y = Vorbis.imdct(x, n);
        float scale = 2f / (n / 2);
        for (int i = 0; i < y.length; i++) y[i] *= scale;
        return y;
    }

    // ---- What both ends keep ----

    /** The models both ends learn as they go, the same way. Made fresh every GROUP frames. */
    static final class Models {
        final short[] type = RangeCoder.models(4);
        final short[] stereo = RangeCoder.models(64);
        final short[] state = RangeCoder.models(3 * 4 * 3);
        final short[] scale = RangeCoder.models(4 * 256);
        final short[] noise = RangeCoder.models(2 * 128);
        final short[] width = RangeCoder.models(17 * 32);
        final short[] small = RangeCoder.models(5 * 3 * 4 * 32);
        final short[] top = RangeCoder.models(17 * 3 * 4 * 16);
    }

    /** One block of one channel: each band's fate (0 left out, 1 sent, 2 noise), its scale or noise level, and the levels. */
    static final class Block {
        final int[] state;
        final int[] scale;
        final int[] noise;
        final int[] levels;
        /** The encoder's frequencies: the levels are worked out from them once each band's scale is settled. */
        float[] spectrum;

        Block(int bands, int lines) {
            state = new int[bands];
            scale = new int[bands];
            noise = new int[bands];
            levels = new int[lines];
        }
    }

    /** Which models a band's numbers use: 4 zones from low to high frequencies. */
    private static int zone(int band, int bands) {
        return Math.min(3, band * 4 / bands);
    }

    private static int context(int level) {
        return Math.min(2, level);
    }

    /**
     * One block, written by the encoder or read by the decoder: the same code for both, so they can't disagree.
     * Each band: sent or not; if not, whether it's filled with noise (and how loud); if sent, its scale (as a change
     * from the band below), how many bits its biggest level needs, then the levels, each guessed from the one below.
     */
    static void codeBlock(RangeCoder.Coder rc, Models m, Block block, int channel, boolean isShort) {
        int[] edges = isShort ? SHORT_BANDS : LONG_BANDS;
        int bandCount = edges.length - 1;
        int belowScale = 100;
        int belowNoise = 40;
        int belowState = 1;
        int belowWidth = 0;
        for (int band = 0; band < bandCount; band++) {
            int from = edges[band];
            int to = edges[band + 1];
            int zone = zone(band, bandCount);
            int stateBase = (belowState * 4 + zone) * 3;
            if (rc.tree(m.state, stateBase, 1, block.state[band] == 1 ? 1 : 0) == 0) {
                boolean fill = rc.bit(m.state, stateBase + 2, block.state[band] == 2 ? 1 : 0) == 1;
                block.state[band] = fill ? 2 : 0;
                if (fill) {
                    int delta = rc.tree(m.noise, zone < 2 ? 0 : 128, 7, Math.clamp(block.noise[band] - belowNoise + 64, 0, 127));
                    belowNoise = Math.clamp(delta - 64 + belowNoise, 0, NOISES - 1);
                    block.noise[band] = belowNoise;
                }
                Arrays.fill(block.levels, from, to, 0);
                belowState = block.state[band];
                continue;
            }
            block.state[band] = 1;
            // The scale
            int delta = rc.tree(m.scale, zone * 256, 8, Math.clamp(block.scale[band] - belowScale + 128, 0, 255));
            int scale = Math.clamp(delta - 128 + belowScale, 0, SCALES - 1);
            block.scale[band] = scale;
            belowScale = scale;
            // The encoder's levels, now that the step is settled
            if (block.spectrum != null) {
                double step = step(scale);
                for (int k = from; k < to; k++) block.levels[k] = Math.clamp(quantize(block.spectrum[k], step), -65535, 65535);
            }
            int biggest = 0;
            for (int k = from; k < to; k++) biggest = Math.max(biggest, Math.abs(block.levels[k]));
            // How many bits the biggest level needs
            int bits = rc.tree(m.width, Math.min(16, belowWidth) * 32, 5, 32 - Integer.numberOfLeadingZeros(biggest));
            belowWidth = bits;
            // The levels
            int previous = 0;
            for (int k = from; k < to; k++) {
                int v = Math.abs(block.levels[k]);
                int ctx = context(previous);
                if (bits <= 4) {
                    v = rc.tree(m.small, ((bits * 3 + ctx) * 4 + zone) * 32, bits, v);
                } else {
                    int high = rc.tree(m.top, ((bits * 3 + ctx) * 4 + zone) * 16, 4, v >>> (bits - 4));
                    v = (high << (bits - 4)) | rc.direct(v & ((1 << (bits - 4)) - 1), bits - 4);
                }
                boolean negative = v != 0 && rc.direct(block.levels[k] < 0 ? 1 : 0, 1) == 1;
                block.levels[k] = negative ? -v : v;
                previous = v;
            }
            belowState = 1;
        }
    }

    /** A whole frame, written or read: its type, which stereo bands are middle-and-side, then every block. */
    static int codeFrame(RangeCoder.Coder rc, Models m, int type, boolean[] middleSide, Block[][] blocks, int channels) {
        type = rc.tree(m.type, 0, 2, type);
        boolean isShort = type == SHORT;
        int bandCount = (isShort ? SHORT_BANDS : LONG_BANDS).length - 1;
        if (channels == 2) {
            for (int band = 0; band < bandCount; band++) middleSide[band] = rc.bit(m.stereo, Math.min(63, band), middleSide[band] ? 1 : 0) == 1;
        }
        int count = isShort ? SHORTS : 1;
        for (int b = 0; b < count; b++) {
            for (int c = 0; c < channels; c++) {
                if (blocks[b][c] == null) blocks[b][c] = new Block(bandCount, isShort ? SHORT_LINES : FRAME);
                codeBlock(rc, m, blocks[b][c], c, isShort);
            }
        }
        return type;
    }

    // ---- Encoding ----

    /** Music squeezed with Squid Music: the frames, and what's needed to play them back. */
    public record Encoded(int rate, int channels, long samples, List<byte[]> frames) {
        /** Average bits per second (counting the 2 bytes each frame's length takes in a file). */
        public double bitrate() {
            long bytes = 0;
            for (byte[] f : frames) bytes += f.length + 2;
            return bytes * 8.0 / Math.max(1e-9, samples / (double) rate);
        }
    }

    /** Squeezes a whole sound. Quality is 0 (smallest) to 10 (closest to the original). */
    public static Encoded encode(Pcm pcm, int quality) {
        int channels = pcm.channels();
        if (channels < 1 || channels > 2) throw new IllegalArgumentException("Squid Music is for mono or stereo sound");
        long samples = pcm.samples().length / channels;
        int frames = (int) ((samples + FRAME - 1) / FRAME) + 1;
        // The sound as floats, with a frame of silence in front (the first window starts before the sound)
        float[][] x = new float[channels][(frames + 1) * FRAME];
        for (int i = 0; i < samples; i++) {
            for (int c = 0; c < channels; c++) x[c][FRAME + i] = pcm.samples()[i * channels + c] / 32768f;
        }
        boolean[] sharp = new boolean[frames + 1];
        for (int f = 0; f < frames; f++) sharp[f] = hasHit(x, f);
        Hearing hearing = new Hearing(pcm.rate(), quality);
        List<byte[]> out = new ArrayList<>();
        Models models = null;
        int previous = LONG;
        for (int f = 0; f < frames; f++) {
            // Short blocks where there's a hit, with START and STOP frames easing in and out of them
            boolean afterShort = previous == SHORT || previous == START;
            int type;
            if (sharp[f]) type = afterShort ? SHORT : START;
            else if (sharp[f + 1]) type = afterShort ? SHORT : START;
            else type = afterShort ? STOP : LONG;
            if (f % GROUP == 0) models = new Models();
            out.add(encodeFrame(x, f, type, channels, hearing, models));
            previous = type;
        }
        return new Encoded(pcm.rate(), channels, samples, out);
    }

    /** Whether a frame has a sharp start in it: one short piece far louder (in its highs) than the ones before. */
    private static boolean hasHit(float[][] x, int frame) {
        int from = frame * FRAME;
        double[] energy = new double[16];
        for (float[] channel : x) {
            float last = from > 0 ? channel[from - 1] : 0;
            for (int i = 0; i < N; i++) {
                float v = channel[from + i];
                float high = v - last; // a simple high pass: hits are mostly highs
                last = v;
                energy[i / 128] += high * high;
            }
        }
        for (int i = 4; i < 13; i++) {
            double before = (energy[i - 1] + energy[i - 2] + energy[i - 3] + energy[i - 4]) / 4;
            if (energy[i] > 1e-5 && energy[i] > 10 * before) return true;
        }
        return false;
    }

    private static byte[] encodeFrame(float[][] x, int frame, int type, int channels, Hearing hearing, Models m) {
        boolean isShort = type == SHORT;
        int blocks = isShort ? SHORTS : 1;
        int lines = isShort ? SHORT_LINES : FRAME;
        int[] edges = isShort ? SHORT_BANDS : LONG_BANDS;
        int bandCount = edges.length - 1;
        // The frequencies of each channel, each block
        float[][][] spectrum = new float[channels][blocks][];
        for (int c = 0; c < channels; c++) {
            int from = frame * FRAME;
            if (isShort) {
                for (int b = 0; b < SHORTS; b++) {
                    float[] block = new float[SHORT_N];
                    int start = from + SHORT_START + b * SHORT_LINES;
                    for (int i = 0; i < SHORT_N; i++) block[i] = x[c][start + i] * SHORT_WINDOW[i];
                    spectrum[c][b] = mdct(block, SHORT_N);
                }
            } else {
                float[] w = WINDOWS[type];
                float[] block = new float[N];
                for (int i = 0; i < N; i++) block[i] = x[c][from + i] * w[i];
                spectrum[c][0] = mdct(block, N);
            }
        }
        // How much noise each band can hide (per channel, per block)
        double[][][] allowed = new double[channels][blocks][];
        for (int c = 0; c < channels; c++) {
            for (int b = 0; b < blocks; b++) allowed[c][b] = hearing.allowed(spectrum[c][b], edges, isShort);
        }
        // Stereo: middle and side for bands where both sides are alike
        boolean[] middleSide = new boolean[bandCount];
        if (channels == 2) {
            for (int band = 0; band < bandCount; band++) {
                double l = 0;
                double r = 0;
                double s = 0;
                for (int b = 0; b < blocks; b++) {
                    for (int k = edges[band]; k < edges[band + 1]; k++) {
                        float a = spectrum[0][b][k];
                        float d = spectrum[1][b][k];
                        l += a * a;
                        r += d * d;
                        s += (a - d) * (a - d) / 4;
                    }
                }
                middleSide[band] = s < 0.3 * Math.min(l, r) || (l + r) < 1e-12;
            }
            for (int b = 0; b < blocks; b++) {
                for (int band = 0; band < bandCount; band++) {
                    if (!middleSide[band]) continue;
                    for (int k = edges[band]; k < edges[band + 1]; k++) {
                        float a = spectrum[0][b][k];
                        float d = spectrum[1][b][k];
                        spectrum[0][b][k] = (a + d) / 2;
                        spectrum[1][b][k] = (a - d) / 2;
                    }
                    // Left is middle + side and right is middle - side, so each half keeps half the quieter side's limit
                    double limit = Math.min(allowed[0][b][band], allowed[1][b][band]) / 2;
                    allowed[0][b][band] = limit;
                    allowed[1][b][band] = limit;
                }
            }
        }
        Block[][] planned = new Block[blocks][channels];
        for (int b = 0; b < blocks; b++) {
            for (int c = 0; c < channels; c++) {
                Block block = new Block(bandCount, lines);
                block.spectrum = spectrum[c][b];
                plan(block, allowed[c][b], edges, lines, hearing, isShort);
                planned[b][c] = block;
            }
        }
        RangeCoder.Encoder rc = new RangeCoder.Encoder();
        codeFrame(rc, m, type, middleSide, planned, channels);
        return rc.finish();
    }

    /**
     * Picks each band's fate: left out (its noise would be hidden anyway), filled with noise, or sent with the
     * biggest step that keeps its noise hidden.
     */
    private static void plan(Block block, double[] allowed, int[] edges, int lines, Hearing hearing, boolean isShort) {
        float[] spectrum = block.spectrum;
        for (int band = 0; band < edges.length - 1; band++) {
            int from = edges[band];
            int to = edges[band + 1];
            int width = to - from;
            double energy = 0;
            for (int k = from; k < to; k++) energy += spectrum[k] * spectrum[k];
            if (energy <= allowed[band]) {
                // High up, put back matching noise; otherwise silence
                int level = noiseLevel(energy / width);
                boolean fill = from >= lines / 8 && level > 0 && energy > hearing.quietest(band, isShort);
                block.state[band] = fill ? 2 : 0;
                block.noise[band] = level;
                continue;
            }
            block.state[band] = 1;
            int s = scaleFor(Math.sqrt(12 * allowed[band] / width));
            while (s > 0 && noise(spectrum, from, to, step(s)) > allowed[band]) s--;
            while (s + 1 < SCALES && noise(spectrum, from, to, step(s + 1)) <= allowed[band]) s++;
            block.scale[band] = s;
        }
    }

    static double step(int scale) {
        return BASE * Math.pow(2, scale / 4.0);
    }

    private static int scaleFor(double step) {
        if (step <= BASE) return 0;
        return Math.clamp((int) Math.floor(4 * Math.log(step / BASE) / Math.log(2)), 0, SCALES - 1);
    }

    /** Rounds a little toward zero, which saves bits for almost no extra noise. */
    private static int quantize(float v, double step) {
        int q = (int) (Math.abs(v) / step + 0.4);
        return v < 0 ? -q : q;
    }

    private static double noise(float[] spectrum, int from, int to, double step) {
        double sum = 0;
        for (int k = from; k < to; k++) {
            double d = spectrum[k] - quantize(spectrum[k], step) * step;
            sum += d * d;
        }
        return sum;
    }

    /** A noise loudness (energy per line) in 1.5 dB steps. */
    private static int noiseLevel(double perLine) {
        if (perLine <= BASE * BASE) return 0;
        return Math.clamp((int) Math.round(2 * Math.log(perLine) / Math.log(2) + 48), 0, NOISES - 1);
    }

    private static double noiseRms(int level) {
        return Math.sqrt(Math.pow(2, (level - 48) / 2.0));
    }

    // ---- The hearing model (only the encoder needs it) ----

    /**
     * How much noise ears won't notice in each band. A loud band hides noise in the bands around it, more upward
     * (toward higher sounds) than downward; a hiss hides more than a clear note; and nothing quieter than the
     * quietest sound people can hear counts at all.
     */
    static final class Hearing {
        private final double[][] spreadLong;
        private final double[][] spreadShort;
        private final double[] quietLong;
        private final double[] quietShort;
        private final double shift;

        Hearing(int rate, int quality) {
            shift = (Math.clamp(quality, 0, 10) - DEFAULT_QUALITY) * 1.5; // each quality step keeps noise 1.5 dB lower
            spreadLong = spread(rate, LONG_BANDS, FRAME);
            spreadShort = spread(rate, SHORT_BANDS, SHORT_LINES);
            quietLong = quietest(rate, LONG_BANDS, FRAME, N);
            quietShort = quietest(rate, SHORT_BANDS, SHORT_LINES, SHORT_N);
        }

        private static double bark(double hz) {
            return 13 * Math.atan(0.00076 * hz) + 3.5 * Math.atan(Math.pow(hz / 7500, 2));
        }

        private static double[][] spread(int rate, int[] edges, int lines) {
            int bands = edges.length - 1;
            double[] z = new double[bands];
            for (int b = 0; b < bands; b++) z[b] = bark((edges[b] + edges[b + 1]) / 2.0 * rate / 2.0 / lines);
            double[][] s = new double[bands][bands];
            for (int masker = 0; masker < bands; masker++) {
                for (int b = 0; b < bands; b++) {
                    double dz = z[b] - z[masker];
                    double db = dz >= 0 ? -10 * dz : 25 * dz; // spreads up 10 dB per bark, down 25
                    s[masker][b] = Math.pow(10, db / 10);
                }
            }
            return s;
        }

        /**
         * The quietest sound people can hear in each band (Terhardt's curve), as band energy, taking a full-scale sine
         * as about 96 dB, like a loud speaker.
         */
        private static double[] quietest(int rate, int[] edges, int lines, int n) {
            int bands = edges.length - 1;
            double[] out = new double[bands];
            double fullScale = Math.pow(n / 4.0, 2) / 2; // about a full-scale sine's energy in its band, through this MDCT
            for (int b = 0; b < bands; b++) {
                double least = Double.MAX_VALUE;
                for (int k = edges[b]; k < edges[b + 1]; k++) {
                    double khz = Math.max(0.02, (k + 0.5) * rate / 2.0 / lines / 1000);
                    double db = 3.64 * Math.pow(khz, -0.8) - 6.5 * Math.exp(-0.6 * Math.pow(khz - 3.3, 2)) + 1e-3 * Math.pow(khz, 4);
                    least = Math.min(least, db);
                }
                out[b] = fullScale * Math.pow(10, (Math.min(least, 96) - 96) / 10);
            }
            return out;
        }

        double quietest(int band, boolean isShort) {
            return (isShort ? quietShort : quietLong)[band];
        }

        double[] allowed(float[] spectrum, int[] edges, boolean isShort) {
            int bands = edges.length - 1;
            double[] energy = new double[bands];
            double[] hides = new double[bands];
            for (int b = 0; b < bands; b++) {
                // How note-like the band is, from how uneven its lines are: the geometric mean over the plain mean is
                // 1 for flat noise and near 0 for a clear note
                double logSum = 0;
                int width = edges[b + 1] - edges[b];
                for (int k = edges[b]; k < edges[b + 1]; k++) {
                    double p = spectrum[k] * spectrum[k];
                    energy[b] += p;
                    logSum += Math.log(p + 1e-30);
                }
                double flatness = energy[b] <= 1e-30 ? 1 : Math.exp(logSum / width) / (energy[b] / width);
                double tone = Math.clamp(-10 * Math.log10(Math.max(flatness, 1e-10)) / 25, 0, 1);
                // A hiss hides noise 15 dB under itself, a clear note 30 dB under (careful values: a little more
                // detail than ears strictly need, so it stays clean on good headphones too)
                hides[b] = energy[b] * Math.pow(10, -(15 + 15 * tone + shift) / 10);
            }
            double[][] s = isShort ? spreadShort : spreadLong;
            double[] quiet = isShort ? quietShort : quietLong;
            double[] out = new double[bands];
            for (int b = 0; b < bands; b++) {
                double mask = 0;
                for (int masker = 0; masker < bands; masker++) mask += hides[masker] * s[masker][b];
                out[b] = Math.max(mask, quiet[b]);
            }
            return out;
        }
    }

    // ---- Decoding ----

    /** Turns frames back into sound, one after another. Each frame gives 1024 samples per channel. */
    public static final class Decoder {
        private final int channels;
        private final float[][] overlap;
        private Models models;
        private int frame;
        private long noise = 0x5EED5EEDL;

        public Decoder(int channels) {
            this.channels = channels;
            overlap = new float[channels][FRAME];
        }

        /** Where the next frame is: frames come in order from a multiple of GROUP. Its first frame's sound is a warm-up. */
        public void startAt(int frame) {
            if (frame % GROUP != 0) throw new IllegalArgumentException("decoding starts at a multiple of " + GROUP);
            this.frame = frame;
            models = null;
            for (float[] o : overlap) Arrays.fill(o, 0);
        }

        /** One frame into 1024 samples per channel, channel after channel for each moment. */
        public short[] decode(byte[] data) {
            if (frame % GROUP == 0 || models == null) models = new Models();
            noise = 0x5EED5EEDL ^ (frame * 0x9E3779B97F4A7C15L); // the same noise for a frame however playback got there
            frame++;
            RangeCoder.Decoder rc = new RangeCoder.Decoder(data);
            boolean[] middleSide = new boolean[LONG_BANDS.length];
            Block[][] coded = new Block[SHORTS][channels];
            int type = codeFrame(rc, models, 0, middleSide, coded, channels);
            boolean isShort = type == SHORT;
            int blocks = isShort ? SHORTS : 1;
            int[] edges = isShort ? SHORT_BANDS : LONG_BANDS;
            int bandCount = edges.length - 1;
            float[][][] spectrum = new float[channels][blocks][];
            for (int b = 0; b < blocks; b++) {
                for (int c = 0; c < channels; c++) spectrum[c][b] = rebuild(coded[b][c], edges, isShort ? SHORT_LINES : FRAME);
            }
            if (channels == 2) {
                for (int b = 0; b < blocks; b++) {
                    for (int band = 0; band < bandCount; band++) {
                        if (!middleSide[band]) continue;
                        for (int k = edges[band]; k < edges[band + 1]; k++) {
                            float mid = spectrum[0][b][k];
                            float side = spectrum[1][b][k];
                            spectrum[0][b][k] = mid + side;
                            spectrum[1][b][k] = mid - side;
                        }
                    }
                }
            }
            short[] out = new short[FRAME * channels];
            for (int c = 0; c < channels; c++) {
                float[] block = new float[N];
                if (isShort) {
                    for (int b = 0; b < SHORTS; b++) {
                        float[] y = imdct(spectrum[c][b], SHORT_N);
                        int start = SHORT_START + b * SHORT_LINES;
                        for (int i = 0; i < SHORT_N; i++) block[start + i] += y[i] * SHORT_WINDOW[i];
                    }
                } else {
                    float[] y = imdct(spectrum[c][0], N);
                    float[] w = WINDOWS[type];
                    for (int i = 0; i < N; i++) block[i] = y[i] * w[i];
                }
                for (int i = 0; i < FRAME; i++) {
                    float v = (overlap[c][i] + block[i]) * 32768;
                    out[i * channels + c] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
                }
                System.arraycopy(block, FRAME, overlap[c], 0, FRAME);
            }
            return out;
        }

        /** A block's frequencies from its levels and scales, with noise where the encoder asked for it. */
        private float[] rebuild(Block block, int[] edges, int lines) {
            float[] spectrum = new float[lines];
            for (int band = 0; band < edges.length - 1; band++) {
                if (block.state[band] == 1) {
                    double step = step(block.scale[band]);
                    for (int k = edges[band]; k < edges[band + 1]; k++) spectrum[k] = (float) (block.levels[k] * step);
                } else if (block.state[band] == 2) {
                    // Random noise with the band's loudness (uniform from -1 to 1 has an rms of 1/sqrt(3))
                    double rms = noiseRms(block.noise[band]) * Math.sqrt(3);
                    for (int k = edges[band]; k < edges[band + 1]; k++) spectrum[k] = (float) (nextNoise() * rms);
                }
            }
            return spectrum;
        }

        private double nextNoise() {
            noise = noise * 6364136223846793005L + 1442695040888963407L;
            return ((noise >>> 40) / (double) (1L << 24)) * 2 - 1;
        }
    }

    /** Decodes a whole sound (for short sounds, and tests). */
    public static Pcm decode(Encoded e) {
        Decoder d = new Decoder(e.channels());
        short[] out = new short[(int) e.samples() * e.channels()];
        int at = -FRAME * e.channels(); // the first frame's sound is the silence in front
        for (byte[] f : e.frames()) {
            short[] got = d.decode(f);
            for (int i = 0; i < got.length; i++) {
                int to = at + i;
                if (to >= 0 && to < out.length) out[to] = got[i];
            }
            at += got.length;
        }
        return new Pcm(out, e.channels(), e.rate());
    }
}
