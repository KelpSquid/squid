package squid.audio;

/**
 * Listens to a sound and finds things out about it, so nobody has to type them in: its tempo (BPM) and where the
 * first beat is, and a loop that sounds seamless.
 *
 * Both start the same way. The sound is mixed to mono and slowed down to 11025 samples a second (plenty for
 * rhythm), then cut into short overlapping slices. Each slice becomes how loud it is in 12 bands from deep bass to
 * treble, and how much louder it just got (an "onset": drums and new notes make those).
 *
 * - Tempo: beats come at a steady pace, so the onsets line up best with one particular spacing. Squid tries every
 *   tempo from 60 to 200 BPM, in small steps, and every starting point, and keeps the one whose beats land on the
 *   most onsets across the whole song.
 * - Loop: a seamless loop jumps from a moment back to an earlier moment that sounds the same, so the music carries on
 *   as if nothing happened. Squid compares a few seconds around every pair of moments (on the beat, if it knows the
 *   tempo, so loops are whole bars), keeps the pair that sounds most alike, then lines the two up to the exact
 *   sample so the waves meet.
 */
public final class Analysis {
    private Analysis() {
    }

    static final int RATE = 11025;
    static final int HOP = 256;
    static final int WINDOW = 1024;
    static final int BANDS = 12;
    static final double FPS = RATE / (double) HOP; // slices a second
    /** A slice is heard at its middle: slice 0 is about 0.046 s in, not at 0. */
    static final double CENTER = WINDOW / 2.0 / RATE;

    /** A tempo in beats a minute, when the first beat is (seconds), and how sure, 0 to 1. */
    public record Tempo(double bpm, double offset, double confidence) {
    }

    /** Loop points in seconds, and how alike the two sides sound, 0 to 1. */
    public record LoopPoints(double start, double end, double confidence) {
    }

    /** The slices: each one's loudness per band (log), and its onset strength. */
    record Features(float[][] bands, float[] onset) {
        int length() {
            return onset.length;
        }
    }

    // ---- Tempo ----

    /** The sound's tempo, or null if it's too short or has no steady beat. */
    public static Tempo tempo(Pcm pcm) {
        return tempo(features(pcm));
    }

    static Tempo tempo(Features f) {
        float[] onset = smoothOnsets(f.onset());
        int n = onset.length;
        if (n < FPS * 4) return null; // under 4 seconds: not enough beats to be sure
        double bestBpm = 0;
        double bestPhase = 0;
        double bestScore = -1;
        double total = 0;
        int tried = 0;
        // Coarse first (every 0.5 BPM), then finer around the best
        for (double bpm = 60; bpm <= 200; bpm += 0.5) {
            double[] r = bestPhase(onset, bpm);
            double score = r[1] * prior(bpm);
            total += score;
            tried++;
            if (score > bestScore) {
                bestScore = score;
                bestBpm = bpm;
                bestPhase = r[0];
            }
        }
        double mean = total / tried;
        for (double bpm = bestBpm - 0.5; bpm <= bestBpm + 0.5; bpm += 0.02) {
            double[] r = bestPhase(onset, bpm);
            double score = r[1] * prior(bpm);
            if (score > bestScore) {
                bestScore = score;
                bestBpm = bpm;
                bestPhase = r[0];
            }
        }
        // Most music is written at a whole number of BPM
        if (Math.abs(bestBpm - Math.rint(bestBpm)) < 0.2) bestBpm = Math.rint(bestBpm);
        double confidence = Math.clamp((bestScore / Math.max(1e-9, mean) - 1) / 1.5, 0, 1);
        if (confidence < 0.05) return null;
        return new Tempo(bestBpm, bestPhase / FPS + CENTER, confidence);
    }

    /**
     * Gently prefers tempos around 120 BPM, so a song isn't called 70 when it's 140: both fit the same beats, and
     * people tap along at the faster one.
     */
    private static double prior(double bpm) {
        double octaves = Math.log(bpm / 120) / Math.log(2);
        return Math.exp(-0.5 * octaves * octaves / (0.9 * 0.9));
    }

    /** For a tempo, the starting point (in slices) whose beats land on the most onsets, and how much they land on. */
    private static double[] bestPhase(float[] onset, double bpm) {
        double period = FPS * 60 / bpm;
        double best = -1;
        double bestPhase = 0;
        for (double phase = 0; phase < period; phase += 0.5) {
            double sum = 0;
            int count = 0;
            for (double t = phase; t < onset.length - 1; t += period) {
                int i = (int) t;
                double frac = t - i;
                sum += onset[i] * (1 - frac) + onset[i + 1] * frac;
                count++;
            }
            double score = count == 0 ? 0 : sum / count;
            if (score > best) {
                best = score;
                bestPhase = phase;
            }
        }
        return new double[] {bestPhase, best};
    }

    /** Onsets with the slowly changing part taken away, so only sudden hits count. */
    private static float[] smoothOnsets(float[] onset) {
        int n = onset.length;
        float[] out = new float[n];
        int r = 8;
        double sum = 0;
        int count = 0;
        // A running average over 17 slices
        for (int i = 0; i < Math.min(n, r); i++) {
            sum += onset[i];
            count++;
        }
        for (int i = 0; i < n; i++) {
            if (i + r < n) {
                sum += onset[i + r];
                count++;
            }
            if (i - r - 1 >= 0) {
                sum -= onset[i - r - 1];
                count--;
            }
            out[i] = (float) Math.max(0, onset[i] - sum / count);
        }
        return out;
    }

    // ---- Loops ----

    /**
     * A loop that sounds seamless, at least minSeconds long (or a third of the sound, if that's longer), or null if
     * the sound is too short to have one. tempo can be null: then loops can start anywhere, not just on a bar.
     */
    public static LoopPoints loop(Pcm pcm, Tempo tempo, double minSeconds) {
        Features f = features(pcm);
        int n = f.length();
        int w = (int) Math.round(FPS * 1.5); // compare 1.5 seconds on each side of both moments
        double seconds = n / FPS;
        int minLength = (int) Math.round(FPS * Math.max(minSeconds, seconds / 3));
        if (n < 2 * w + minLength + 2) return null;
        float[][] z = normalized(f.bands());

        // Where loops may start and end: on bars if the tempo is known, else every few slices
        java.util.List<Integer> points = new java.util.ArrayList<>();
        boolean onBars = tempo != null && tempo.confidence() > 0.2;
        if (onBars) {
            double bar = FPS * 60 / tempo.bpm() * 4;
            for (double t = (tempo.offset() - CENTER) * FPS; t < n; t += bar) points.add((int) Math.round(t));
        } else {
            for (int t = 0; t < n; t += 4) points.add(t);
        }
        double bestScore = Double.MAX_VALUE;
        int bestS = -1;
        int bestE = -1;
        double sumD = 0;
        int countD = 0;
        for (int a = 0; a < points.size(); a++) {
            int s = points.get(a);
            if (s < w) continue;
            if (s > n * 0.6) break; // a loop starts in the first part of a sound
            for (int b = a + 1; b < points.size(); b++) {
                int e = points.get(b);
                if (e - s < minLength) continue;
                if (e > n - w - 1) break;
                double d = distance(z, s, e, w, 3);
                sumD += d;
                countD++;
                // A small reward for longer loops: more of the song plays before it repeats
                double score = d * (1 - 0.15 * (e - s) / (double) n);
                if (score < bestScore) {
                    bestScore = score;
                    bestS = s;
                    bestE = e;
                }
            }
        }
        if (bestS < 0) return null;
        // Finer: every slice near the best pair (keeping whole bars if it's on bars: both move together)
        double best = distance(z, bestS, bestE, w, 1);
        int fineS = bestS;
        int fineE = bestE;
        for (int ds = -3; ds <= 3; ds++) {
            for (int de = -3; de <= 3; de++) {
                if (onBars && ds != de) continue;
                int s = bestS + ds;
                int e = bestE + de;
                if (s < w || e > n - w - 1 || e - s < minLength) continue;
                double d = distance(z, s, e, w, 1);
                if (d < best) {
                    best = d;
                    fineS = s;
                    fineE = e;
                }
            }
        }
        double scale = pcm.rate() / (double) RATE * HOP; // one slice, in the sound's own samples
        long middle = Math.round(CENTER * pcm.rate());
        long start = Math.round(fineS * scale) + middle;
        long end = align(pcm, start, Math.round(fineE * scale) + middle, (int) Math.round(scale));
        double mean = countD == 0 ? best : sumD / countD;
        double confidence = Math.clamp(1 - best / Math.max(1e-9, mean), 0, 1);
        return new LoopPoints(start / (double) pcm.rate(), end / (double) pcm.rate(), confidence);
    }

    /** How different two moments sound: the average squared difference of their bands, around each, every `step` slices. */
    private static double distance(float[][] z, int s, int e, int w, int step) {
        double sum = 0;
        int count = 0;
        for (int j = -w; j <= w; j += step) {
            float[] x = z[s + j];
            float[] y = z[e + j];
            for (int b = 0; b < BANDS; b++) {
                double d = x[b] - y[b];
                sum += d * d;
            }
            count++;
        }
        return sum / count;
    }

    /** Each band scaled to the same range over the whole sound, so loud bass doesn't drown out the rest. */
    private static float[][] normalized(float[][] bands) {
        int n = bands.length;
        float[][] z = new float[n][BANDS];
        for (int b = 0; b < BANDS; b++) {
            double mean = 0;
            for (float[] f : bands) mean += f[b];
            mean /= Math.max(1, n);
            double var = 0;
            for (float[] f : bands) var += (f[b] - mean) * (f[b] - mean);
            double sd = Math.sqrt(var / Math.max(1, n)) + 1e-6;
            for (int i = 0; i < n; i++) z[i][b] = (float) ((bands[i][b] - mean) / sd);
        }
        return z;
    }

    /**
     * Moves the loop's end a little (up to one slice either way) so the wave just before it continues into the wave
     * just after the start: the two meet in step instead of with a click.
     */
    static long align(Pcm pcm, long start, long end, int reach) {
        float[] mono = mono(pcm, pcm.rate());
        int span = Math.min(2048, (int) Math.min(start, mono.length - end - 1));
        if (span < 64) return end;
        long bestEnd = end;
        double best = -Double.MAX_VALUE;
        for (long e = Math.max(start + 512, end - reach); e <= Math.min(mono.length - span - 1, end + reach); e++) {
            double dot = 0;
            double ea = 0;
            double eb = 0;
            for (int i = -span; i < span; i += 2) {
                double a = mono[(int) (start + i)];
                double b = mono[(int) (e + i)];
                dot += a * b;
                ea += a * a;
                eb += b * b;
            }
            double c = dot / Math.sqrt(ea * eb + 1e-9);
            if (c > best) {
                best = c;
                bestEnd = e;
            }
        }
        return bestEnd;
    }

    // ---- Slices ----

    static Features features(Pcm pcm) {
        float[] mono = mono(pcm, RATE);
        int n = mono.length < WINDOW ? 0 : (mono.length - WINDOW) / HOP + 1;
        float[][] bands = new float[n][BANDS];
        float[] onset = new float[n];
        int[] edges = bandEdges();
        float[] window = new float[WINDOW];
        for (int i = 0; i < WINDOW; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / WINDOW));
        double[] re = new double[WINDOW];
        double[] im = new double[WINDOW];
        for (int h = 0; h < n; h++) {
            int at = h * HOP;
            for (int i = 0; i < WINDOW; i++) {
                re[i] = mono[at + i] * window[i];
                im[i] = 0;
            }
            fft(re, im);
            for (int b = 0; b < BANDS; b++) {
                double energy = 0;
                for (int k = edges[b]; k < edges[b + 1]; k++) energy += re[k] * re[k] + im[k] * im[k];
                bands[h][b] = (float) Math.log(1e-4 + energy);
                if (h > 0) onset[h] += Math.max(0, bands[h][b] - bands[h - 1][b]);
            }
        }
        return new Features(bands, onset);
    }

    /** FFT bins where each band starts: spaced evenly in pitch from 50 Hz to 5 kHz. */
    private static int[] bandEdges() {
        int[] edges = new int[BANDS + 1];
        for (int b = 0; b <= BANDS; b++) {
            double hz = 50 * Math.pow(5000 / 50.0, b / (double) BANDS);
            edges[b] = Math.max(b == 0 ? 1 : edges[b - 1] + 1, (int) Math.round(hz * WINDOW / RATE));
        }
        return edges;
    }

    /** The sound as one channel, at `rate` samples a second (averaging, which also takes out what's too high to keep). */
    static float[] mono(Pcm pcm, int rate) {
        int ch = pcm.channels();
        short[] s = pcm.samples();
        int frames = s.length / ch;
        if (rate == pcm.rate()) {
            float[] out = new float[frames];
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < ch; c++) sum += s[i * ch + c];
                out[i] = sum / (32768f * ch);
            }
            return out;
        }
        double step = pcm.rate() / (double) rate;
        int n = (int) (frames / step);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int from = (int) (i * step);
            int to = Math.max(from + 1, Math.min(frames, (int) ((i + 1) * step)));
            double sum = 0;
            for (int j = from; j < to; j++) for (int c = 0; c < ch; c++) sum += s[j * ch + c];
            out[i] = (float) (sum / ((to - from) * ch * 32768.0));
        }
        return out;
    }

    /** A plain radix-2 FFT, in place. The length must be a power of two. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wr = Math.cos(angle);
            double wi = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
