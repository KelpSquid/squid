package squid.audio;

import java.util.Arrays;

/**
 * An MP3 decoder (MPEG-1, 2 and 2.5, Layer III), written for Squid from the format's description. An MP3 is a row of
 * frames. Each frame's sound is split into 576 frequency lines per granule; the frame stores how loud each band of
 * frequencies is (scalefactors) and the lines themselves, squeezed with Huffman codes. Decoding undoes that, turns
 * middle/side stereo back into left/right, smooths the edges between the 32 sub-bands (alias reduction), turns
 * frequencies back into sound with an inverse MDCT, and joins the 32 sub-bands into one signal with the synthesis
 * filter bank. The silent padding encoders add at the start and end is trimmed using the LAME tag, when there is one.
 */
public final class Mp3 {
    private static final int[] BITRATE_V1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] BITRATE_V2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[][] RATES = {{11025, 12000, 8000}, {0, 0, 0}, {22050, 24000, 16000}, {44100, 48000, 32000}};
    private static final int[][] SLEN = {{0, 0}, {0, 1}, {0, 2}, {0, 3}, {3, 0}, {1, 1}, {1, 2}, {1, 3}, {2, 1}, {2, 2}, {2, 3}, {3, 1}, {3, 2}, {3, 3}, {4, 2}, {4, 3}};
    private static final int[] PRETAB = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 3, 2, 0};
    // MPEG-2 scalefactor partitions: [kind][block: long, short, mixed][part]
    private static final int[][][] LSF_PARTS = {
            {{6, 5, 5, 5}, {9, 9, 9, 9}, {6, 9, 9, 9}},
            {{6, 5, 7, 3}, {9, 9, 12, 6}, {6, 9, 12, 6}},
            {{11, 10, 0, 0}, {18, 18, 0, 0}, {15, 18, 0, 0}},
            {{7, 7, 7, 0}, {12, 12, 12, 0}, {6, 15, 12, 0}},
            {{6, 6, 6, 3}, {12, 9, 9, 6}, {6, 12, 9, 6}},
            {{8, 8, 5, 0}, {15, 12, 9, 0}, {6, 18, 9, 0}}};
    // Scalefactor band edges, long and short, for each sample rate (in the order of RATES above, then by index)
    private static final int[][] SFB_LONG = {
            {0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 52, 62, 74, 90, 110, 134, 162, 196, 238, 288, 342, 418, 576},   // 44.1k
            {0, 4, 8, 12, 16, 20, 24, 30, 36, 42, 50, 60, 72, 88, 106, 128, 156, 190, 230, 276, 330, 384, 576},   // 48k
            {0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 54, 66, 82, 102, 126, 156, 194, 240, 296, 364, 448, 550, 576},  // 32k
            {0, 6, 12, 18, 24, 30, 36, 44, 54, 66, 80, 96, 116, 140, 168, 200, 238, 284, 336, 396, 464, 522, 576}, // 22.05k
            {0, 6, 12, 18, 24, 30, 36, 44, 54, 66, 80, 96, 114, 136, 162, 194, 232, 278, 332, 394, 464, 540, 576}, // 24k
            {0, 6, 12, 18, 24, 30, 36, 44, 54, 66, 80, 96, 116, 140, 168, 200, 238, 284, 336, 396, 464, 522, 576}, // 16k
            {0, 6, 12, 18, 24, 30, 36, 44, 54, 66, 80, 96, 116, 140, 168, 200, 238, 284, 336, 396, 464, 522, 576}, // 11.025k
            {0, 6, 12, 18, 24, 30, 36, 44, 54, 66, 80, 96, 116, 140, 168, 200, 238, 284, 336, 396, 464, 522, 576}, // 12k
            {0, 12, 24, 36, 48, 60, 72, 88, 108, 132, 160, 192, 232, 280, 336, 400, 476, 566, 568, 570, 572, 574, 576}}; // 8k
    private static final int[][] SFB_SHORT = {
            {0, 4, 8, 12, 16, 22, 30, 40, 52, 66, 84, 106, 136, 192},
            {0, 4, 8, 12, 16, 22, 28, 38, 50, 64, 80, 100, 126, 192},
            {0, 4, 8, 12, 16, 22, 30, 42, 58, 78, 104, 138, 180, 192},
            {0, 4, 8, 12, 18, 24, 32, 42, 56, 74, 100, 132, 174, 192},
            {0, 4, 8, 12, 18, 26, 36, 48, 62, 80, 104, 136, 180, 192},
            {0, 4, 8, 12, 18, 26, 36, 48, 62, 80, 104, 134, 174, 192},
            {0, 4, 8, 12, 18, 26, 36, 48, 62, 80, 104, 134, 174, 192},
            {0, 4, 8, 12, 18, 26, 36, 48, 62, 80, 104, 134, 174, 192},
            {0, 8, 16, 24, 36, 52, 72, 96, 124, 160, 162, 164, 166, 192}};

    private static final float[] CS = new float[8];
    private static final float[] CA = new float[8];
    private static final float[][] WINDOW = new float[4][36];
    private static final float[] SHORT_WINDOW = new float[12];
    private static final float[] POW43 = new float[8207];
    private static final float[][] COS36 = new float[36][18];
    private static final float[][] COS12 = new float[12][6];
    private static final float[][] SYNTH_COS = new float[64][32];

    static {
        double[] ci = {-0.6, -0.535, -0.33, -0.185, -0.095, -0.041, -0.0142, -0.0037};
        for (int i = 0; i < 8; i++) {
            double sq = Math.sqrt(1 + ci[i] * ci[i]);
            CS[i] = (float) (1 / sq);
            CA[i] = (float) (ci[i] / sq);
        }
        for (int i = 0; i < 36; i++) WINDOW[0][i] = (float) Math.sin(Math.PI / 36 * (i + 0.5));
        for (int i = 0; i < 18; i++) WINDOW[1][i] = WINDOW[0][i];
        for (int i = 18; i < 24; i++) WINDOW[1][i] = 1;
        for (int i = 24; i < 30; i++) WINDOW[1][i] = (float) Math.sin(Math.PI / 12 * (i - 18 + 0.5));
        for (int i = 6; i < 12; i++) WINDOW[3][i] = (float) Math.sin(Math.PI / 12 * (i - 6 + 0.5));
        for (int i = 12; i < 18; i++) WINDOW[3][i] = 1;
        for (int i = 18; i < 36; i++) WINDOW[3][i] = WINDOW[0][i];
        for (int i = 0; i < 12; i++) SHORT_WINDOW[i] = (float) Math.sin(Math.PI / 12 * (i + 0.5));
        for (int i = 0; i < POW43.length; i++) POW43[i] = (float) Math.pow(i, 4.0 / 3);
        for (int i = 0; i < 36; i++) {
            for (int k = 0; k < 18; k++) COS36[i][k] = (float) Math.cos(Math.PI / 72 * (2 * i + 1 + 18) * (2 * k + 1));
        }
        for (int i = 0; i < 12; i++) {
            for (int k = 0; k < 6; k++) COS12[i][k] = (float) Math.cos(Math.PI / 24 * (2 * i + 1 + 6) * (2 * k + 1));
        }
        for (int i = 0; i < 64; i++) {
            for (int k = 0; k < 32; k++) SYNTH_COS[i][k] = (float) Math.cos((16 + i) * (2 * k + 1) * Math.PI / 64);
        }
    }

    private Mp3() {
    }

    /** Whether the bytes look like an MP3: an ID3 tag, or a Layer III frame header right away. */
    public static boolean is(byte[] d) {
        if (d.length >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') return true;
        return d.length >= 4 && header(d, 0) != null;
    }

    /** One frame's header. */
    private record Header(int version, boolean crc, int bitrate, int rate, int rateIndex, boolean padding, int mode, int modeExtension,
                          int length) {
        boolean mpeg1() {
            return version == 3;
        }

        int channels() {
            return mode == 3 ? 1 : 2;
        }

        int granules() {
            return mpeg1() ? 2 : 1;
        }

        int sideInfoSize() {
            return mpeg1() ? (mode == 3 ? 17 : 32) : (mode == 3 ? 9 : 17);
        }

        /** Index into the band tables. */
        int table() {
            return switch (version) {
                case 3 -> rateIndex;
                case 2 -> 3 + rateIndex;
                default -> 6 + rateIndex;
            };
        }
    }

    private static Header header(byte[] d, int at) {
        if (at + 4 > d.length) return null;
        int h = ((d[at] & 0xFF) << 24) | ((d[at + 1] & 0xFF) << 16) | ((d[at + 2] & 0xFF) << 8) | (d[at + 3] & 0xFF);
        if ((h >>> 21) != 0x7FF) return null;
        int version = (h >>> 19) & 3;
        int layer = (h >>> 17) & 3;
        int bitrateIndex = (h >>> 12) & 15;
        int rateIndex = (h >>> 10) & 3;
        if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null;
        int bitrate = (version == 3 ? BITRATE_V1 : BITRATE_V2)[bitrateIndex] * 1000;
        int rate = RATES[version][rateIndex];
        boolean padding = ((h >>> 9) & 1) == 1;
        int length = (version == 3 ? 144 : 72) * bitrate / rate + (padding ? 1 : 0);
        return new Header(version, ((h >>> 16) & 1) == 0, bitrate, rate, rateIndex, padding, (h >>> 6) & 3, (h >>> 4) & 3, length);
    }

    // ---- Side info: how each granule and channel was squeezed ----

    private static final class Granule {
        int part23Length;
        int bigValues;
        int globalGain;
        int scalefacCompress;
        boolean windowSwitching;
        int blockType;
        boolean mixed;
        final int[] tableSelect = new int[3];
        final int[] subblockGain = new int[3];
        int region1Start;
        int region2Start;
        boolean preflag;
        int scalefacScale;
        int count1Table;
    }

    /** Decodes a whole MP3 file. */
    public static Pcm decode(byte[] d) {
        int at = 0;
        // Skip an ID3v2 tag (its size is "syncsafe": 7 bits per byte)
        if (d.length >= 10 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            int size = ((d[6] & 0x7F) << 21) | ((d[7] & 0x7F) << 14) | ((d[8] & 0x7F) << 7) | (d[9] & 0x7F);
            at = 10 + size + ((d[5] & 0x10) != 0 ? 10 : 0);
        }
        State state = null;
        short[] out = new short[1 << 20];
        int written = 0;
        int startTrim = 0;
        int endTrim = 0;
        boolean first = true;
        while (at + 4 <= d.length) {
            Header h = header(d, at);
            // A real frame header is followed by another one where it says (unless it's the last)
            if (h == null || (at + h.length() + 4 <= d.length && header(d, at + h.length()) == null && at + h.length() < d.length - 128)) {
                at++;
                continue;
            }
            if (state == null) state = new State(h.channels(), h.rate());
            if (h.channels() != state.channels || h.rate() != state.rate) break; // a different stream glued on: stop here
            int end = Math.min(d.length, at + h.length());
            if (first) {
                first = false;
                int[] trim = lameTrim(d, at, h);
                if (trim != null) { // the first frame is a Xing/Info header with no sound: skip it, and note the padding
                    startTrim = trim[0] + 529;
                    endTrim = Math.max(0, trim[1] - 529);
                    at = end;
                    continue;
                }
            }
            short[] pcm;
            try {
                pcm = state.frame(d, at, end, h);
            } catch (RuntimeException e) {
                pcm = new short[576 * h.granules() * h.channels()]; // a damaged frame: a moment of silence, not the end
            }
            if (written + pcm.length > out.length) out = Arrays.copyOf(out, Math.max(out.length * 2, written + pcm.length));
            System.arraycopy(pcm, 0, out, written, pcm.length);
            written += pcm.length;
            at = end;
        }
        if (state == null) throw new IllegalArgumentException("no MP3 frames found");
        int channels = state.channels;
        int from = Math.min(written, startTrim * channels);
        int to = Math.max(from, written - endTrim * channels);
        return new Pcm(Arrays.copyOfRange(out, from, to), channels, state.rate);
    }

    /** The encoder's delay and padding from a LAME tag in the first frame, or null if that frame is a normal one. */
    private static int[] lameTrim(byte[] d, int at, Header h) {
        int info = at + 4 + (h.crc() ? 2 : 0) + h.sideInfoSize();
        if (info + 8 > d.length) return null;
        String tag = new String(d, info, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!tag.equals("Xing") && !tag.equals("Info")) return null;
        int flags = ((d[info + 4] & 0xFF) << 24) | ((d[info + 5] & 0xFF) << 16) | ((d[info + 6] & 0xFF) << 8) | (d[info + 7] & 0xFF);
        int p = info + 8;
        if ((flags & 1) != 0) p += 4;   // frame count
        if ((flags & 2) != 0) p += 4;   // byte count
        if ((flags & 4) != 0) p += 100; // seek table
        if ((flags & 8) != 0) p += 4;   // quality
        // The LAME extension: 9 bytes saying the encoder, then (21 bytes in) 12 bits of delay and 12 of padding
        String encoder = p + 4 <= d.length ? new String(d, p, 4, java.nio.charset.StandardCharsets.ISO_8859_1) : "";
        if (p + 24 <= d.length && (encoder.equals("LAME") || encoder.equals("Lavc") || encoder.equals("Lavf"))) { // LAME, or ffmpeg's LAME-style tag
            int q = p + 21;
            int delay = ((d[q] & 0xFF) << 4) | ((d[q + 1] & 0xF0) >> 4);
            int padding = ((d[q + 1] & 0x0F) << 8) | (d[q + 2] & 0xFF);
            return new int[] {delay, padding};
        }
        return new int[] {0, 0};
    }

    /** What carries over from frame to frame: the bit reservoir, the overlap of the MDCT, the filter bank's memory. */
    private static final class State {
        final int channels;
        final int rate;
        byte[] reservoir = new byte[0];
        final float[][] overlap;
        final float[][] synthV;
        final int[] synthOffset;

        State(int channels, int rate) {
            this.channels = channels;
            this.rate = rate;
            overlap = new float[channels][576];
            synthV = new float[channels][1024];
            synthOffset = new int[channels];
        }

        short[] frame(byte[] d, int at, int end, Header h) {
            int sideStart = at + 4 + (h.crc() ? 2 : 0);
            Bits side = new Bits(d, sideStart, Math.min(end, sideStart + h.sideInfoSize()));
            int channels = h.channels();
            int granules = h.granules();
            boolean mpeg1 = h.mpeg1();
            int mainDataBegin = side.read(mpeg1 ? 9 : 8);
            side.read(mpeg1 ? (channels == 1 ? 5 : 3) : (channels == 1 ? 1 : 2)); // private bits
            int[][] scfsi = new int[channels][4];
            if (mpeg1) for (int c = 0; c < channels; c++) for (int b = 0; b < 4; b++) scfsi[c][b] = side.read(1);
            Granule[][] g = new Granule[granules][channels];
            int table = h.table();
            for (int gr = 0; gr < granules; gr++) {
                for (int c = 0; c < channels; c++) {
                    Granule x = g[gr][c] = new Granule();
                    x.part23Length = side.read(12);
                    x.bigValues = Math.min(288, side.read(9));
                    x.globalGain = side.read(8);
                    x.scalefacCompress = side.read(mpeg1 ? 4 : 9);
                    x.windowSwitching = side.read(1) == 1;
                    if (x.windowSwitching) {
                        x.blockType = side.read(2);
                        x.mixed = side.read(1) == 1;
                        for (int i = 0; i < 2; i++) x.tableSelect[i] = side.read(5);
                        for (int i = 0; i < 3; i++) x.subblockGain[i] = side.read(3);
                        x.region1Start = x.blockType == 2 && !x.mixed ? SFB_SHORT[table][3] * 3 : SFB_LONG[table][8];
                        x.region2Start = 576;
                    } else {
                        for (int i = 0; i < 3; i++) x.tableSelect[i] = side.read(5);
                        int r0 = side.read(4);
                        int r1 = side.read(3);
                        x.region1Start = SFB_LONG[table][Math.min(22, r0 + 1)];
                        x.region2Start = SFB_LONG[table][Math.min(22, r0 + r1 + 2)];
                    }
                    if (mpeg1) x.preflag = side.read(1) == 1;
                    x.scalefacScale = side.read(1);
                    x.count1Table = side.read(1);
                }
            }

            // The bit reservoir: this frame's main data can start in earlier frames' leftover bytes
            int mainStart = sideStart + h.sideInfoSize();
            byte[] fresh = Arrays.copyOfRange(d, Math.min(mainStart, end), end);
            byte[] all = new byte[reservoir.length + fresh.length];
            System.arraycopy(reservoir, 0, all, 0, reservoir.length);
            System.arraycopy(fresh, 0, all, reservoir.length, fresh.length);
            int startByte = reservoir.length - mainDataBegin;
            reservoir = all.length > 4096 ? Arrays.copyOfRange(all, all.length - 4096, all.length) : all;
            short[] pcm = new short[576 * granules * channels];
            if (startByte < 0) return pcm; // the reservoir isn't filled yet (the stream was cut): silence

            Bits main = new Bits(all, startByte, all.length);
            int[][][] scalefactors = new int[granules][channels][39];
            float[][] xr = new float[channels][576];
            for (int gr = 0; gr < granules; gr++) {
                int[] rightLast = new int[1];
                int[][] isPos = new int[channels][];
                for (int c = 0; c < channels; c++) {
                    Granule x = g[gr][c];
                    long part2Start = main.bitPosition();
                    int[] sf = scalefactors[gr][c];
                    boolean intensityRight = !mpeg1 && c == 1 && (h.modeExtension() & 1) != 0;
                    if (mpeg1) scalefactorsMpeg1(main, x, sf, gr > 0 ? scalefactors[0][c] : null, scfsi[c], gr);
                    else isPos[c] = scalefactorsMpeg2(main, x, sf, intensityRight);
                    int[] is = new int[576];
                    int nonzero = huffman(main, x, is, part2Start + x.part23Length, table);
                    if (c == 1) rightLast[0] = nonzero;
                    main.seekBit(part2Start + x.part23Length);
                    requantize(x, is, sf, xr[c], table, mpeg1);
                }
                if (channels == 2) stereo(h, g[gr], xr, scalefactors[gr][1], isPos[1], table, mpeg1);
                for (int c = 0; c < channels; c++) {
                    Granule x = g[gr][c];
                    reorder(x, xr[c], table);
                    antialias(x, xr[c]);
                    float[] time = imdct(x, xr[c], overlap[c]);
                    synthesize(time, c, pcm, gr * 576 * channels, channels);
                }
            }
            return pcm;
        }

        // ---- Scalefactors: how loud each frequency band is ----

        private void scalefactorsMpeg1(Bits b, Granule x, int[] sf, int[] previous, int[] scfsi, int gr) {
            int slen1 = SLEN[x.scalefacCompress][0];
            int slen2 = SLEN[x.scalefacCompress][1];
            if (x.windowSwitching && x.blockType == 2) {
                if (x.mixed) {
                    for (int s = 0; s < 8; s++) sf[s] = b.read(slen1);
                    for (int s = 3; s < 12; s++) {
                        int len = s < 6 ? slen1 : slen2;
                        for (int w = 0; w < 3; w++) sf[8 + (s - 3) * 3 + w] = b.read(len);
                    }
                } else {
                    for (int s = 0; s < 12; s++) {
                        int len = s < 6 ? slen1 : slen2;
                        for (int w = 0; w < 3; w++) sf[s * 3 + w] = b.read(len);
                    }
                }
            } else {
                int[][] groups = {{0, 6}, {6, 11}, {11, 16}, {16, 21}};
                for (int i = 0; i < 4; i++) {
                    for (int s = groups[i][0]; s < groups[i][1]; s++) {
                        if (gr > 0 && scfsi[i] == 1) sf[s] = previous[s]; // shared with the first granule
                        else sf[s] = b.read(i < 2 ? slen1 : slen2);
                    }
                }
            }
        }

        /** MPEG-2's scalefactors, and (for intensity stereo's right channel) the illegal positions to skip. */
        private int[] scalefactorsMpeg2(Bits b, Granule x, int[] sf, boolean intensityRight) {
            int[] slen = new int[4];
            int kind;
            int sfc = x.scalefacCompress;
            if (!intensityRight) {
                if (sfc < 400) {
                    slen = new int[] {(sfc >> 4) / 5, (sfc >> 4) % 5, (sfc & 15) >> 2, sfc & 3};
                    kind = 0;
                } else if (sfc < 500) {
                    sfc -= 400;
                    slen = new int[] {(sfc >> 2) / 5, (sfc >> 2) % 5, sfc & 3, 0};
                    kind = 1;
                } else {
                    sfc -= 500;
                    slen = new int[] {sfc / 3, sfc % 3, 0, 0};
                    kind = 2;
                    x.preflag = true;
                }
            } else {
                sfc >>= 1;
                if (sfc < 180) {
                    slen = new int[] {sfc / 36, (sfc % 36) / 6, (sfc % 36) % 6, 0};
                    kind = 3;
                } else if (sfc < 244) {
                    sfc -= 180;
                    slen = new int[] {(sfc & 63) >> 4, (sfc & 15) >> 2, sfc & 3, 0};
                    kind = 4;
                } else {
                    sfc -= 244;
                    slen = new int[] {sfc / 3, sfc % 3, 0, 0};
                    kind = 5;
                }
            }
            int block = x.windowSwitching && x.blockType == 2 ? (x.mixed ? 2 : 1) : 0;
            int[] counts = LSF_PARTS[kind][block];
            int[] illegal = new int[39];
            int k = 0;
            for (int part = 0; part < 4; part++) {
                int max = (1 << slen[part]) - 1;
                for (int i = 0; i < counts[part]; i++, k++) {
                    sf[k] = b.read(slen[part]);
                    illegal[k] = max;
                }
            }
            return illegal;
        }

        // ---- Huffman: the frequency lines themselves ----

        /** Reads the lines into is, and says how many lines might not be 0. */
        private int huffman(Bits b, Granule x, int[] is, long end, int table) {
            int i = 0;
            int bigEnd = Math.min(576, x.bigValues * 2);
            int[] pair = new int[4];
            while (i < bigEnd) {
                int region = i < x.region1Start ? 0 : i < x.region2Start ? 1 : 2;
                int t = x.tableSelect[region];
                pair(b, t, pair);
                is[i++] = pair[0];
                is[i++] = pair[1];
            }
            // The count1 part: groups of four lines that are each -1, 0 or 1
            int quadTable = x.count1Table == 0 ? 32 : 33;
            while (i + 4 <= 576 && b.bitPosition() < end) {
                quad(b, quadTable, pair);
                if (b.bitPosition() > end) break; // that last group ran past the end: it isn't real
                is[i++] = pair[0];
                is[i++] = pair[1];
                is[i++] = pair[2];
                is[i++] = pair[3];
            }
            return i;
        }

        private static int leaf(Bits b, int t) {
            int start = Mp3Tables.TREE_START[t];
            if (start < 0) return 0;
            int point = 0;
            for (int guard = 0; guard < 32; guard++) {
                int entry = Mp3Tables.TREE[start + point] & 0xFFFF;
                if ((entry & 0xFF00) == 0) return entry;
                if (b.bit() == 1) {
                    while ((Mp3Tables.TREE[start + point] & 0xFF) >= 250) point += Mp3Tables.TREE[start + point] & 0xFF;
                    point += Mp3Tables.TREE[start + point] & 0xFF;
                } else {
                    while (((Mp3Tables.TREE[start + point] & 0xFFFF) >> 8) >= 250) point += (Mp3Tables.TREE[start + point] & 0xFFFF) >> 8;
                    point += (Mp3Tables.TREE[start + point] & 0xFFFF) >> 8;
                }
                if (point >= Mp3Tables.TREE_LENGTH[t]) throw new IllegalStateException("bad Huffman code");
            }
            throw new IllegalStateException("bad Huffman code");
        }

        private static void pair(Bits b, int t, int[] out) {
            int v = leaf(b, t);
            int x = (v >> 4) & 15;
            int y = v & 15;
            int linbits = Mp3Tables.LINBITS[t];
            if (linbits > 0 && x == 15) x += b.read(linbits);
            if (x != 0 && b.bit() == 1) x = -x;
            if (linbits > 0 && y == 15) y += b.read(linbits);
            if (y != 0 && b.bit() == 1) y = -y;
            out[0] = x;
            out[1] = y;
        }

        private static void quad(Bits b, int t, int[] out) {
            int v = leaf(b, t);
            for (int k = 0; k < 4; k++) {
                int value = (v >> (3 - k)) & 1;
                if (value != 0 && b.bit() == 1) value = -value;
                out[k] = value;
            }
        }

        // ---- Requantizing: back to real loudness ----

        private void requantize(Granule x, int[] is, int[] sf, float[] xr, int table, boolean mpeg1) {
            double multiplier = x.scalefacScale == 1 ? 1 : 0.5;
            int[] longs = SFB_LONG[table];
            int[] shorts = SFB_SHORT[table];
            boolean shortBlocks = x.windowSwitching && x.blockType == 2;
            int longEnd = shortBlocks ? (x.mixed ? 36 : 0) : 576; // mixed blocks: the first two sub-bands are long
            // Long bands
            int band = 0;
            for (int i = 0; i < longEnd; i++) {
                while (i >= longs[band + 1]) band++;
                int scale = sf[band] + (x.preflag ? PRETAB[band] : 0);
                double exponent = 0.25 * (x.globalGain - 210) - multiplier * scale;
                xr[i] = value(is[i], exponent);
            }
            if (!shortBlocks) return;
            // Short bands: each band has three windows, one after another
            int s = x.mixed ? 3 : 0;
            int base = x.mixed ? (mpeg1 ? 8 : 6) : 0; // where the short scalefactors start, after the long ones
            int i = longEnd;
            while (i < 576 && s < 13) {
                int width = shorts[s + 1] - shorts[s];
                for (int w = 0; w < 3; w++) {
                    int scale = s < 12 ? sf[base + (s - (x.mixed ? 3 : 0)) * 3 + w] : 0;
                    double exponent = 0.25 * (x.globalGain - 210 - 8 * x.subblockGain[w]) - multiplier * scale;
                    for (int k = 0; k < width && i < 576; k++, i++) xr[i] = value(is[i], exponent);
                }
                s++;
            }
        }

        private static float value(int q, double exponent) {
            if (q == 0) return 0;
            float magnitude = POW43[Math.min(POW43.length - 1, Math.abs(q))];
            return (float) ((q < 0 ? -magnitude : magnitude) * Math.pow(2, exponent));
        }

        // ---- Stereo: middle/side and intensity back to left/right ----

        private void stereo(Header h, Granule[] g, float[][] xr, int[] rightScalefactors, int[] illegal, int table, boolean mpeg1) {
            if (h.mode() != 1) return; // only joint stereo uses these
            boolean ms = (h.modeExtension() & 2) != 0;
            boolean intensity = (h.modeExtension() & 1) != 0;
            int intensityStart = 576;
            if (intensity) {
                // Intensity applies above the last band where the right channel has sound
                int last = 0;
                for (int i = 575; i >= 0; i--) {
                    if (xr[1][i] != 0) {
                        last = i + 1;
                        break;
                    }
                }
                Granule right = g[1];
                boolean shortBlocks = right.windowSwitching && right.blockType == 2;
                if (!shortBlocks) {
                    int[] longs = SFB_LONG[table];
                    int band = 0;
                    while (band < 21 && longs[band] < last) band++;
                    intensityStart = longs[band];
                    for (int b = band; b < 21; b++) {
                        int pos = rightScalefactors[b];
                        intensityBand(xr, longs[b], longs[b + 1], pos, illegal == null ? 7 : illegal[b], right, mpeg1);
                    }
                    // The last band uses band 20's position
                    intensityBand(xr, longs[21], 576, rightScalefactors[20], illegal == null ? 7 : illegal[20], right, mpeg1);
                }
                // (Short-block intensity stereo is rare; those bands keep the plain left/right from middle/side.)
            }
            if (ms) {
                float k = (float) (1 / Math.sqrt(2));
                for (int i = 0; i < intensityStart; i++) {
                    float m = xr[0][i];
                    float s = xr[1][i];
                    xr[0][i] = (m + s) * k;
                    xr[1][i] = (m - s) * k;
                }
            }
        }

        private void intensityBand(float[][] xr, int from, int to, int pos, int illegal, Granule right, boolean mpeg1) {
            if (pos == illegal) return; // not intensity-coded: left as it is
            double kl;
            double kr;
            if (mpeg1) {
                if (pos >= 7) return;
                double ratio = Math.tan(pos * Math.PI / 12);
                kl = ratio / (1 + ratio);
                kr = 1 / (1 + ratio);
                if (pos == 6) { // tan(90 degrees): all on the left
                    kl = 1;
                    kr = 0;
                }
            } else {
                double base = (right.scalefacCompress & 1) == 1 ? Math.sqrt(0.5) : Math.pow(2, -0.25);
                if (pos == 0) {
                    kl = 1;
                    kr = 1;
                } else if ((pos & 1) == 1) {
                    kl = Math.pow(base, (pos + 1) / 2.0);
                    kr = 1;
                } else {
                    kl = 1;
                    kr = Math.pow(base, pos / 2.0);
                }
            }
            for (int i = from; i < to && i < 576; i++) {
                float v = xr[0][i];
                xr[0][i] = (float) (v * kl);
                xr[1][i] = (float) (v * kr);
            }
        }

        // ---- Reordering short blocks, so each sub-band's 3 windows sit together ----

        private void reorder(Granule x, float[] xr, int table) {
            if (!(x.windowSwitching && x.blockType == 2)) return;
            int[] shorts = SFB_SHORT[table];
            float[] copy = xr.clone();
            int s = x.mixed ? 3 : 0;
            int i = x.mixed ? 36 : 0;
            while (s < 13 && i < 576) {
                int start = shorts[s];
                int width = shorts[s + 1] - start;
                for (int w = 0; w < 3; w++) {
                    for (int k = 0; k < width; k++) {
                        int from = i + w * width + k;
                        int to = start * 3 + k * 3 + w;
                        if (from < 576 && to < 576) xr[to] = copy[from];
                    }
                }
                i += width * 3;
                s++;
            }
        }

        // ---- Alias reduction: smoothing the seams between the 32 sub-bands ----

        private void antialias(Granule x, float[] xr) {
            if (x.windowSwitching && x.blockType == 2 && !x.mixed) return;
            int bands = x.windowSwitching && x.blockType == 2 ? 1 : 31; // mixed: only between the two long sub-bands
            for (int sb = 0; sb < bands; sb++) {
                for (int i = 0; i < 8; i++) {
                    int lower = sb * 18 + 17 - i;
                    int upper = sb * 18 + 18 + i;
                    float a = xr[lower];
                    float b = xr[upper];
                    xr[lower] = a * CS[i] - b * CA[i];
                    xr[upper] = b * CS[i] + a * CA[i];
                }
            }
        }

        // ---- Inverse MDCT: frequencies back to sound, per sub-band, overlapping with the last granule ----

        private float[] imdct(Granule x, float[] xr, float[] overlap) {
            float[] out = new float[576];
            float[] raw = new float[36];
            for (int sb = 0; sb < 32; sb++) {
                int type = x.windowSwitching ? x.blockType : 0;
                if (x.windowSwitching && x.mixed && sb < 2) type = 0;
                Arrays.fill(raw, 0);
                if (type == 2) {
                    for (int w = 0; w < 3; w++) {
                        for (int i = 0; i < 12; i++) {
                            float sum = 0;
                            for (int k = 0; k < 6; k++) sum += xr[sb * 18 + k * 3 + w] * COS12[i][k];
                            raw[6 + w * 6 + i] += sum * SHORT_WINDOW[i];
                        }
                    }
                } else {
                    float[] window = WINDOW[type];
                    for (int i = 0; i < 36; i++) {
                        float sum = 0;
                        for (int k = 0; k < 18; k++) sum += xr[sb * 18 + k] * COS36[i][k];
                        raw[i] = sum * window[i];
                    }
                }
                for (int i = 0; i < 18; i++) {
                    out[sb * 18 + i] = raw[i] + overlap[sb * 18 + i];
                    overlap[sb * 18 + i] = raw[18 + i];
                }
                // Every other sample of every other sub-band is flipped (the filter bank expects it)
                if ((sb & 1) == 1) for (int i = 1; i < 18; i += 2) out[sb * 18 + i] = -out[sb * 18 + i];
            }
            return out;
        }

        // ---- The synthesis filter bank: 32 sub-bands into one signal ----

        private void synthesize(float[] time, int c, short[] pcm, int offset, int channels) {
            float[] v = synthV[c];
            float[] s = new float[32];
            for (int slot = 0; slot < 18; slot++) {
                for (int sb = 0; sb < 32; sb++) s[sb] = time[sb * 18 + slot];
                int o = synthOffset[c] = (synthOffset[c] - 64) & 1023;
                for (int i = 0; i < 64; i++) {
                    float sum = 0;
                    float[] row = SYNTH_COS[i];
                    for (int k = 0; k < 32; k++) sum += row[k] * s[k];
                    v[(o + i) & 1023] = sum;
                }
                for (int j = 0; j < 32; j++) {
                    float sum = 0;
                    for (int i = 0; i < 8; i++) {
                        sum += v[(o + i * 128 + j) & 1023] * Mp3Tables.SYNTH_WINDOW[i * 64 + j];
                        sum += v[(o + i * 128 + 96 + j) & 1023] * Mp3Tables.SYNTH_WINDOW[i * 64 + 32 + j];
                    }
                    int sample = Math.round(sum * 32768);
                    pcm[offset + (slot * 32 + j) * channels + c] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
                }
            }
        }
    }
}
