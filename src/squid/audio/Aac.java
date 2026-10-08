package squid.audio;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An AAC decoder (the sound in .m4a files from iTunes and phones, and raw .aac files), written for Squid from the
 * AAC standard (ISO/IEC 14496-3). It plays AAC-LC, the kind almost every music file uses. HE-AAC files play too,
 * at their core quality (the extra "SBR" treble layer is skipped).
 *
 * How it goes:
 * - The container: an .m4a (MP4) file lists where each frame is (the stsz, stsc and stco boxes) and how much silence
 *   the encoder put at the start and end (an edit list, or iTunes' iTunSMPB note), which is trimmed off. A raw .aac
 *   file is frames one after another, each with a small ADTS header.
 * - Each frame: per channel, a gain, the codebook for each band, the band scales, and the frequencies themselves,
 *   packed with Huffman codes (the numbers in {@link AacTables} are fixed by the standard).
 * - Back to sound: frequencies are rebuilt (each value to the power 4/3, times its band's scale), the stereo tricks
 *   undone (mid/side, intensity, noise bands), the TNS filter run, then the inverse MDCT (long blocks of 2048, or 8
 *   short ones for sharp sounds), windowed and overlapped into 1024 new samples per channel.
 */
final class Aac {
    private Aac() {
    }

    static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    /** Whether the bytes are an .m4a/.mp4 file with AAC in it, or a raw AAC (ADTS) stream. */
    static boolean is(byte[] d) {
        if (d.length >= 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') return true;
        int at = skipId3(d);
        return d.length >= at + 7 && (d[at] & 0xFF) == 0xFF && (d[at + 1] & 0xF6) == 0xF0;
    }

    private static int skipId3(byte[] d) {
        if (d.length >= 10 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            int size = ((d[6] & 0x7F) << 21) | ((d[7] & 0x7F) << 14) | ((d[8] & 0x7F) << 7) | (d[9] & 0x7F);
            return 10 + size + ((d[5] & 0x10) != 0 ? 10 : 0);
        }
        return 0;
    }

    /** The whole sound. */
    static Pcm decode(byte[] d) {
        Track track;
        try {
            track = d.length >= 8 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p' ? mp4(d) : adts(d);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException damaged) {
            throw new IllegalArgumentException("the AAC file is damaged (" + damaged.getMessage() + ")");
        }
        if (track.objectType != 2 && track.objectType != 5 && track.objectType != 29 && track.objectType != 1 && track.objectType != 4) {
            throw new IllegalArgumentException("this AAC kind (" + track.objectType + ") isn't supported, only AAC-LC");
        }
        Decoder decoder = new Decoder(track.rateIndex, track.channels);
        int channels = Math.max(1, track.channels);
        long total = (long) track.frames.size() * 1024;
        if ((total - track.skip) * channels > Audio.MAX_SAMPLES) throw new IllegalArgumentException("the AAC file is far too long");
        short[] out = new short[(int) Math.max(0, total * channels)];
        int written = 0;
        for (int[] frame : track.frames) {
            float[][] pcm;
            try {
                pcm = decoder.frame(d, frame[0], frame[1]);
            } catch (RuntimeException damaged) {
                pcm = new float[channels][1024]; // a damaged frame is a moment of silence
                decoder.forgetOverlap();
            }
            int outChannels = Math.min(channels, pcm.length);
            for (int i = 0; i < 1024; i++) {
                for (int c = 0; c < channels; c++) {
                    float v = c < outChannels ? pcm[c][i] : 0;
                    int s = Math.round(v);
                    out[written++] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
                }
            }
        }
        // Trim the encoder's warm-up at the start, and its padding at the end
        long start = Math.min(track.skip, total);
        long length = track.length > 0 ? Math.min(track.length, total - start) : total - start;
        short[] trimmed = new short[(int) (length * channels)];
        System.arraycopy(out, (int) (start * channels), trimmed, 0, trimmed.length);
        return reorder(new Pcm(trimmed, channels, RATES[Math.min(track.rateIndex, RATES.length - 1)]), track.channels);
    }

    /** AAC lists surround channels center first (C L R ...); Squid works in WAV's order (L R C LFE ...). */
    private static Pcm reorder(Pcm pcm, int config) {
        int[] from = switch (pcm.channels()) {
            case 3 -> new int[] {1, 2, 0};
            case 5 -> new int[] {1, 2, 0, 3, 4};
            case 6 -> new int[] {1, 2, 0, 5, 3, 4};
            case 8 -> new int[] {1, 2, 0, 7, 5, 6, 3, 4};
            default -> null;
        };
        if (from == null) return pcm;
        int ch = pcm.channels();
        short[] in = pcm.samples();
        short[] out = new short[in.length];
        for (int i = 0; i < in.length / ch; i++) {
            for (int c = 0; c < ch; c++) out[i * ch + c] = in[i * ch + from[c]];
        }
        return new Pcm(out, ch, pcm.rate());
    }

    // ---- Containers ----

    /** Where the frames are, what's in them, and how much to trim off the start (skip) and how long it really is. */
    record Track(int objectType, int rateIndex, int channels, List<int[]> frames, long skip, long length) {
    }

    /** A raw AAC stream: frames one after another, each with a 7 (or 9) byte ADTS header. */
    static Track adts(byte[] d) {
        int at = skipId3(d);
        List<int[]> frames = new ArrayList<>();
        int objectType = 2;
        int rateIndex = 4;
        int channels = 2;
        boolean first = true;
        while (at + 7 <= d.length) {
            if ((d[at] & 0xFF) != 0xFF || (d[at + 1] & 0xF6) != 0xF0) {
                at++; // not a header here: look for the next one
                continue;
            }
            boolean noCrc = (d[at + 1] & 1) == 1;
            int length = ((d[at + 3] & 0x03) << 11) | ((d[at + 4] & 0xFF) << 3) | ((d[at + 5] & 0xE0) >> 5);
            int blocks = (d[at + 6] & 0x03) + 1;
            int header = noCrc ? 7 : 9;
            if (length < header || at + length > d.length) break;
            if (first) {
                objectType = ((d[at + 2] & 0xC0) >> 6) + 1;
                rateIndex = (d[at + 2] & 0x3C) >> 2;
                channels = ((d[at + 2] & 0x01) << 2) | ((d[at + 3] & 0xC0) >> 6);
                first = false;
            }
            if (blocks == 1) frames.add(new int[] {at + header, length - header});
            at += length;
        }
        if (frames.isEmpty()) throw new IllegalArgumentException("the AAC file has no frames");
        return new Track(objectType, rateIndex, channels == 0 ? 2 : channels, frames, 0, 0);
    }

    /** An .m4a/.mp4 file: finds the first AAC track and where every one of its frames is. */
    static Track mp4(byte[] d) {
        Mp4 m = new Mp4();
        m.walk(d, 0, d.length, 0);
        if (m.config == null) throw new IllegalArgumentException("the file has no AAC sound in it");
        // AudioSpecificConfig: the kind, the sample rate and the channels
        Bits b = new Bits(m.config, 0, m.config.length);
        int objectType = b.read(5);
        if (objectType == 31) objectType = 32 + b.read(6);
        int rateIndex = b.read(4);
        if (rateIndex == 15) {
            int rate = b.read(24);
            rateIndex = nearestRate(rate);
        }
        int channels = b.read(4);
        if (channels == 0) channels = m.channels > 0 ? m.channels : 2;
        if (channels == 7) channels = 8;
        if (objectType == 5 || objectType == 29) {
            // HE-AAC: the real sample rate follows, then the core's kind. The core is plain AAC-LC at half the rate.
            int extRate = b.read(4);
            if (extRate == 15) b.read(24);
            objectType = b.read(5);
        }
        if (m.sizes == null || m.offsets == null) throw new IllegalArgumentException("the file doesn't say where its sound is");
        // Frames: chunks hold runs of frames, as stsc says; stco says where each chunk starts
        List<int[]> frames = new ArrayList<>();
        int sample = 0;
        for (int chunk = 0; chunk < m.offsets.length && sample < m.sizes.length; chunk++) {
            int perChunk = m.samplesPerChunk(chunk + 1);
            long at = m.offsets[chunk];
            for (int s = 0; s < perChunk && sample < m.sizes.length; s++) {
                int size = m.sizes[sample++];
                if (at < 0 || at + size > d.length) throw new IllegalArgumentException("the file is cut short");
                frames.add(new int[] {(int) at, size});
                at += size;
            }
        }
        long skip = 0;
        long length = 0;
        if (m.smpb != null) {
            // iTunes' note: " 00000000 00000840 000001CA 0000000000A24B56 ...": warm-up, padding, real length
            String[] parts = m.smpb.trim().split("\\s+");
            if (parts.length >= 4) {
                try {
                    skip = Long.parseLong(parts[1], 16);
                    length = Long.parseLong(parts[3], 16);
                } catch (NumberFormatException ignored) {
                    skip = 0;
                }
            }
        } else if (m.editStart >= 0) {
            skip = m.editStart * Math.max(1, 1) * (long) RATES[Math.min(rateIndex, RATES.length - 1)] / Math.max(1, m.mediaScale);
            if (m.editDuration > 0 && m.movieScale > 0) {
                length = m.editDuration * RATES[Math.min(rateIndex, RATES.length - 1)] / m.movieScale;
            }
        }
        return new Track(objectType, rateIndex, channels, frames, skip, length);
    }

    private static int nearestRate(int rate) {
        int best = 4;
        for (int i = 0; i < RATES.length; i++) if (Math.abs(RATES[i] - rate) < Math.abs(RATES[best] - rate)) best = i;
        return best;
    }

    /** The boxes an MP4 file is made of, read for what the decoder needs. */
    private static final class Mp4 {
        byte[] config;
        int channels;
        int[] sizes;
        long[] offsets;
        int[][] stsc; // first chunk (1-based), frames per chunk
        long editStart = -1;
        long editDuration;
        long mediaScale = 1;
        long movieScale = 1;
        String smpb;
        boolean audioTrack;
        boolean done;

        int samplesPerChunk(int chunk) {
            if (stsc == null || stsc.length == 0) return 1;
            int per = stsc[0][1];
            for (int[] entry : stsc) {
                if (entry[0] <= chunk) per = entry[1];
                else break;
            }
            return per;
        }

        void walk(byte[] d, int from, int to, int depth) {
            int at = from;
            while (at + 8 <= to && depth < 16) {
                long size = u32(d, at);
                String type = new String(d, at + 4, 4, StandardCharsets.ISO_8859_1);
                int header = 8;
                if (size == 1) {
                    if (at + 16 > to) return;
                    size = u64(d, at + 8);
                    header = 16;
                } else if (size == 0) {
                    size = to - at;
                }
                if (size < header || at + size > to) return;
                int body = at + header;
                int end = (int) (at + size);
                switch (type) {
                    case "moov", "mdia", "minf", "stbl", "edts", "udta", "ilst" -> walk(d, body, end, depth + 1);
                    case "trak" -> {
                        if (!done) {
                            boolean before = audioTrack;
                            audioTrack = false;
                            Mp4 trial = new Mp4();
                            trial.movieScale = movieScale;
                            trial.walk(d, body, end, depth + 1);
                            if (trial.config != null && !done) {
                                config = trial.config;
                                channels = trial.channels;
                                sizes = trial.sizes;
                                offsets = trial.offsets;
                                stsc = trial.stsc;
                                editStart = trial.editStart;
                                editDuration = trial.editDuration;
                                mediaScale = trial.mediaScale;
                                if (trial.smpb != null) smpb = trial.smpb;
                                done = true;
                            }
                            audioTrack = before;
                        }
                    }
                    case "meta" -> walk(d, body + 4, end, depth + 1); // a version and flags come first
                    case "mvhd" -> movieScale = d[body] == 1 ? u32(d, body + 20) : u32(d, body + 12);
                    case "mdhd" -> mediaScale = d[body] == 1 ? u32(d, body + 20) : u32(d, body + 12);
                    case "elst" -> {
                        int version = d[body];
                        long count = u32(d, body + 4);
                        int p = body + 8;
                        // The first edit with sound: where the sound starts in the track, and for how long
                        for (int i = 0; i < count && p + (version == 1 ? 20 : 12) <= end; i++) {
                            long duration = version == 1 ? u64(d, p) : u32(d, p);
                            long mediaTime = version == 1 ? u64(d, p + 8) : (int) u32(d, p + 4);
                            p += version == 1 ? 20 : 12;
                            if (mediaTime >= 0) {
                                editStart = mediaTime;
                                editDuration = duration;
                                break;
                            }
                        }
                    }
                    case "stsd" -> walk(d, body + 8, end, depth + 1); // version, flags and a count come first
                    case "mp4a" -> {
                        // An audio sample entry: 28 bytes of details (the channels at 16), then its boxes
                        if (body + 28 <= end) {
                            channels = (d[body + 16] & 0xFF) << 8 | (d[body + 17] & 0xFF);
                            int version = (d[body + 8] & 0xFF) << 8 | (d[body + 9] & 0xFF);
                            int extra = version == 1 ? 16 : version == 2 ? 36 : 0; // QuickTime's longer entries
                            walk(d, body + 28 + extra, end, depth + 1);
                        }
                    }
                    case "wave" -> walk(d, body, end, depth + 1);
                    case "esds" -> config = esds(d, body + 4, end);
                    case "stsz" -> {
                        long fixed = u32(d, body + 4);
                        long count = u32(d, body + 8);
                        if (count > (end - body) / 4 + (fixed != 0 ? Integer.MAX_VALUE : 0) || count > 50_000_000) return;
                        sizes = new int[(int) count];
                        for (int i = 0; i < count; i++) sizes[i] = fixed != 0 ? (int) fixed : (int) u32(d, body + 12 + 4 * i);
                    }
                    case "stsc" -> {
                        long count = u32(d, body + 4);
                        if (count > (end - body) / 12) return;
                        stsc = new int[(int) count][2];
                        for (int i = 0; i < count; i++) {
                            stsc[i][0] = (int) u32(d, body + 8 + 12 * i);
                            stsc[i][1] = (int) u32(d, body + 12 + 12 * i);
                        }
                    }
                    case "stco" -> {
                        long count = u32(d, body + 4);
                        if (count > (end - body) / 4) return;
                        offsets = new long[(int) count];
                        for (int i = 0; i < count; i++) offsets[i] = u32(d, body + 8 + 4 * i);
                    }
                    case "co64" -> {
                        long count = u32(d, body + 4);
                        if (count > (end - body) / 8) return;
                        offsets = new long[(int) count];
                        for (int i = 0; i < count; i++) offsets[i] = u64(d, body + 8 + 8 * i);
                    }
                    case "----" -> {
                        // iTunes' own notes: a "name" box, then a "data" box. iTunSMPB holds the warm-up and length.
                        String text = new String(d, body, end - body, StandardCharsets.ISO_8859_1);
                        int name = text.indexOf("iTunSMPB");
                        int data = text.indexOf("data", Math.max(0, name));
                        if (name >= 0 && data >= 0 && data + 12 <= text.length()) smpb = text.substring(data + 12);
                    }
                    default -> {
                        // a box Squid doesn't need
                    }
                }
                at = end;
            }
        }

        /** The esds box's descriptors: ES, then DecoderConfig, then DecoderSpecificInfo (the AudioSpecificConfig). */
        private static byte[] esds(byte[] d, int at, int end) {
            while (at < end) {
                int tag = d[at++] & 0xFF;
                int length = 0;
                for (int i = 0; i < 4 && at < end; i++) {
                    int b = d[at++] & 0xFF;
                    length = (length << 7) | (b & 0x7F);
                    if ((b & 0x80) == 0) break;
                }
                switch (tag) {
                    case 0x03 -> {
                        int flags = d[at + 2] & 0xFF;
                        at += 3;
                        if ((flags & 0x80) != 0) at += 2;
                        if ((flags & 0x40) != 0) at += 1 + (d[at] & 0xFF);
                        if ((flags & 0x20) != 0) at += 2;
                    }
                    case 0x04 -> {
                        if ((d[at] & 0xFF) != 0x40 && (d[at] & 0xFF) != 0x66 && (d[at] & 0xFF) != 0x67 && (d[at] & 0xFF) != 0x68) {
                            return null; // not AAC (0x40 is MPEG-4 audio, 0x66-0x68 MPEG-2 AAC)
                        }
                        at += 13;
                    }
                    case 0x05 -> {
                        if (at + length > end) return null;
                        byte[] config = new byte[length];
                        System.arraycopy(d, at, config, 0, length);
                        return config;
                    }
                    default -> at += length;
                }
            }
            return null;
        }

        private static long u32(byte[] d, int at) {
            if (at + 4 > d.length) return 0;
            return ((d[at] & 0xFFL) << 24) | ((d[at + 1] & 0xFFL) << 16) | ((d[at + 2] & 0xFFL) << 8) | (d[at + 3] & 0xFFL);
        }

        private static long u64(byte[] d, int at) {
            return (u32(d, at) << 32) | u32(d, at + 4);
        }
    }

    // ---- The decoder ----

    static final int ONLY_LONG = 0;
    static final int LONG_START = 1;
    static final int EIGHT_SHORT = 2;
    static final int LONG_STOP = 3;
    static final int ZERO_HCB = 0;
    static final int ESC_HCB = 11;
    static final int NOISE_HCB = 13;
    static final int INTENSITY_HCB2 = 14;
    static final int INTENSITY_HCB = 15;

    /** Scale factor band edges, from the standard: long blocks (1024 lines) and short blocks (128 lines), per sample rate. */
    static final int[][] SWB_LONG = {
            {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 156, 172, 188, 212,
                    240, 276, 320, 384, 448, 512, 576, 640, 704, 768, 832, 896, 960, 1024},
            {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 88, 100, 112, 124, 140, 156, 172, 192, 216, 240,
                    268, 304, 344, 384, 424, 464, 504, 544, 584, 624, 664, 704, 744, 784, 824, 864, 904, 944, 984, 1024},
            {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 160, 176, 196, 216, 240, 264,
                    292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768, 800, 832, 864, 896, 928, 1024},
            {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 160, 176, 196, 216, 240, 264,
                    292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768, 800, 832, 864, 896, 928, 960, 992, 1024},
            {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 52, 60, 68, 76, 84, 92, 100, 108, 116, 124, 136, 148, 160, 172, 188, 204,
                    220, 240, 260, 284, 308, 336, 364, 396, 432, 468, 508, 552, 600, 652, 704, 768, 832, 896, 960, 1024},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 100, 112, 124, 136, 148, 160, 172, 184, 196, 212, 228, 244, 260, 280, 300,
                    320, 344, 368, 396, 424, 456, 492, 532, 572, 616, 664, 716, 772, 832, 896, 960, 1024},
            {0, 12, 24, 36, 48, 60, 72, 84, 96, 108, 120, 132, 144, 156, 172, 188, 204, 220, 236, 252, 268, 288, 308, 328, 348, 372,
                    396, 420, 448, 476, 508, 544, 580, 620, 664, 712, 764, 820, 880, 944, 1024},
    };
    static final int[][] SWB_SHORT = {
            {0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 92, 128},
            {0, 4, 8, 12, 16, 20, 28, 36, 44, 56, 68, 80, 96, 112, 128},
            {0, 4, 8, 12, 16, 20, 24, 28, 36, 44, 52, 64, 76, 92, 108, 128},
            {0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 60, 72, 88, 108, 128},
            {0, 4, 8, 12, 16, 20, 24, 28, 36, 44, 52, 60, 72, 88, 108, 128},
    };
    /** Which band table each sample rate index uses. */
    static final int[] LONG_TABLE = {0, 0, 1, 2, 2, 3, 4, 4, 5, 5, 5, 6, 6};
    static final int[] SHORT_TABLE = {0, 0, 0, 1, 1, 1, 2, 2, 3, 3, 3, 4, 4};
    /** The highest band TNS filters, per sample rate (AAC-LC). */
    static final int[] TNS_MAX_LONG = {31, 31, 34, 40, 42, 51, 46, 46, 42, 42, 42, 39, 39};
    static final int[] TNS_MAX_SHORT = {9, 9, 10, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14};

    private static final Huffman[] BOOKS = new Huffman[12];
    private static final Huffman SCF = new Huffman(AacTables.CODES_SCF, AacTables.LENGTHS_SCF);
    private static final float[] POW43 = new float[8192 + 1];
    private static final float[] SINE_LONG = sine(2048);
    private static final float[] SINE_SHORT = sine(256);
    private static final float[] KBD_LONG = kbd(2048, 4);
    private static final float[] KBD_SHORT = kbd(256, 6);

    static {
        int[][] codes = {null, AacTables.CODES_1, AacTables.CODES_2, AacTables.CODES_3, AacTables.CODES_4, AacTables.CODES_5,
                AacTables.CODES_6, AacTables.CODES_7, AacTables.CODES_8, AacTables.CODES_9, AacTables.CODES_10, AacTables.CODES_11};
        int[][] lengths = {null, AacTables.LENGTHS_1, AacTables.LENGTHS_2, AacTables.LENGTHS_3, AacTables.LENGTHS_4, AacTables.LENGTHS_5,
                AacTables.LENGTHS_6, AacTables.LENGTHS_7, AacTables.LENGTHS_8, AacTables.LENGTHS_9, AacTables.LENGTHS_10, AacTables.LENGTHS_11};
        for (int i = 1; i <= 11; i++) BOOKS[i] = new Huffman(codes[i], lengths[i]);
        for (int i = 0; i < POW43.length; i++) POW43[i] = (float) Math.pow(i, 4.0 / 3);
    }

    /** A Huffman codebook as a tree, read a bit at a time. */
    static final class Huffman {
        private final int[] zero;
        private final int[] one;

        Huffman(int[] codes, int[] lengths) {
            int nodes = 1;
            int[] z = new int[codes.length * 20 + 2];
            int[] o = new int[codes.length * 20 + 2];
            for (int value = 0; value < codes.length; value++) {
                int node = 0;
                for (int bit = lengths[value] - 1; bit >= 0; bit--) {
                    boolean isOne = ((codes[value] >>> bit) & 1) == 1;
                    int[] side = isOne ? o : z;
                    if (bit == 0) {
                        side[node] = -(value + 1); // a leaf: the value
                    } else {
                        if (side[node] <= 0) side[node] = nodes++;
                        node = side[node];
                    }
                }
            }
            zero = java.util.Arrays.copyOf(z, nodes);
            one = java.util.Arrays.copyOf(o, nodes);
        }

        int read(Bits b) {
            int node = 0;
            for (int depth = 0; depth < 24; depth++) {
                int next = b.bit() == 1 ? one[node] : zero[node];
                if (next < 0) return -next - 1;
                if (next == 0) throw new IllegalStateException("a code the codebook doesn't have");
                node = next;
            }
            throw new IllegalStateException("a code that's too long");
        }
    }

    private static float[] sine(int n) {
        float[] w = new float[n / 2];
        for (int i = 0; i < n / 2; i++) w[i] = (float) Math.sin(Math.PI / n * (i + 0.5));
        return w;
    }

    /** The Kaiser-Bessel derived window's rising half. */
    private static float[] kbd(int n, double alpha) {
        int half = n / 2;
        double[] kaiser = new double[half + 1];
        double sum = 0;
        for (int i = 0; i <= half; i++) {
            double x = 2.0 * i / half - 1;
            kaiser[i] = bessel(Math.PI * alpha * Math.sqrt(1 - x * x));
            sum += kaiser[i];
        }
        float[] w = new float[half];
        double running = 0;
        for (int i = 0; i < half; i++) {
            running += kaiser[i];
            w[i] = (float) Math.sqrt(running / sum);
        }
        return w;
    }

    /** The modified Bessel function I0, by its series. */
    private static double bessel(double x) {
        double sum = 1;
        double term = 1;
        for (int k = 1; k < 50; k++) {
            term *= (x / (2 * k)) * (x / (2 * k));
            sum += term;
            if (term < sum * 1e-12) break;
        }
        return sum;
    }

    /** What one channel's block says: the window, the bands, their codebooks and scales, and the frequencies. */
    static final class Ics {
        int windowSequence;
        int windowShape;
        int maxSfb;
        int groups = 1;
        int[] groupLength = {1};
        int globalGain;
        int[][] codebook = new int[8][64]; // [group][sfb]
        int[][] scale = new int[8][64];
        float[] spectrum = new float[1024];
        // TNS: per window, its filters
        boolean tns;
        int[] tnsFilters = new int[8];
        int[][] tnsLength = new int[8][4];
        int[][] tnsOrder = new int[8][4];
        boolean[][] tnsDown = new boolean[8][4];
        float[][][] tnsCoef = new float[8][4][];

        boolean isShort() {
            return windowSequence == EIGHT_SHORT;
        }
    }

    /** Decodes frames one after another (each needs the one before, for the overlap). */
    static final class Decoder {
        private final int rateIndex;
        private final int[] swbLong;
        private final int[] swbShort;
        private float[][] overlap = new float[8][1024];
        private int[] previousShape = new int[8];
        private final java.util.Random noise = new java.util.Random(0x5EED);

        Decoder(int rateIndex, int channels) {
            this.rateIndex = Math.min(Math.max(rateIndex, 0), 12);
            swbLong = SWB_LONG[LONG_TABLE[this.rateIndex]];
            swbShort = SWB_SHORT[SHORT_TABLE[this.rateIndex]];
        }

        void forgetOverlap() {
            overlap = new float[8][1024];
        }

        /** One frame into 1024 samples per channel, in the order the channels come in the frame. */
        float[][] frame(byte[] d, int offset, int length) {
            Bits b = new Bits(d, offset, offset + length);
            List<float[]> out = new ArrayList<>();
            int channel = 0;
            while (true) {
                int id = b.read(3);
                if (id == 7) break; // END
                switch (id) {
                    case 0, 3 -> { // SCE (one channel), LFE (the bass channel)
                        b.read(4);
                        Ics ics = new Ics();
                        stream(b, ics, false);
                        out.add(toSound(ics, channel++));
                    }
                    case 1 -> { // CPE: two channels, often sharing their window and using stereo tricks
                        b.read(4);
                        Ics left = new Ics();
                        Ics right = new Ics();
                        boolean common = b.read(1) == 1;
                        int msMask = 0;
                        boolean[][] ms = new boolean[8][64];
                        if (common) {
                            icsInfo(b, left);
                            copyInfo(left, right);
                            msMask = b.read(2);
                            if (msMask == 1) {
                                for (int g = 0; g < left.groups; g++) {
                                    for (int sfb = 0; sfb < left.maxSfb; sfb++) ms[g][sfb] = b.read(1) == 1;
                                }
                            }
                        }
                        stream(b, left, common);
                        stream(b, right, common);
                        if (common) stereo(left, right, msMask, ms);
                        out.add(toSound(left, channel++));
                        out.add(toSound(right, channel++));
                    }
                    case 4 -> { // DSE: data for players, skipped
                        b.read(4);
                        boolean align = b.read(1) == 1;
                        int count = b.read(8);
                        if (count == 255) count += b.read(8);
                        if (align) b.alignToByte();
                        for (int i = 0; i < count; i++) b.read(8);
                    }
                    case 5 -> pce(b);
                    case 6 -> { // FIL: filler (and HE-AAC's extra layer), skipped
                        int count = b.read(4);
                        if (count == 15) count += b.read(8) - 1;
                        for (int i = 0; i < count; i++) b.read(8);
                    }
                    default -> throw new IllegalStateException("an AAC element Squid doesn't play (" + id + ")");
                }
                if (channel > 8) throw new IllegalStateException("too many channels");
            }
            return out.toArray(new float[0][]);
        }

        /** A program config element: its numbers are skipped (the channel count already came from the file). */
        private void pce(Bits b) {
            b.read(4);
            b.read(2);
            b.read(4);
            int front = b.read(4);
            int side = b.read(4);
            int back = b.read(4);
            int lfe = b.read(2);
            int assoc = b.read(3);
            int cc = b.read(4);
            if (b.read(1) == 1) b.read(4);
            if (b.read(1) == 1) b.read(4);
            if (b.read(1) == 1) b.read(3);
            for (int i = 0; i < front + side + back; i++) b.read(5);
            for (int i = 0; i < lfe; i++) b.read(4);
            for (int i = 0; i < assoc; i++) b.read(4);
            for (int i = 0; i < cc; i++) b.read(5);
            b.alignToByte();
            int comment = b.read(8);
            for (int i = 0; i < comment; i++) b.read(8);
        }

        private static void copyInfo(Ics from, Ics to) {
            to.windowSequence = from.windowSequence;
            to.windowShape = from.windowShape;
            to.maxSfb = from.maxSfb;
            to.groups = from.groups;
            to.groupLength = from.groupLength.clone();
        }

        private void icsInfo(Bits b, Ics ics) {
            b.read(1); // reserved
            ics.windowSequence = b.read(2);
            ics.windowShape = b.read(1);
            if (ics.isShort()) {
                ics.maxSfb = b.read(4);
                int grouping = b.read(7);
                int[] lengths = new int[8];
                int groups = 0;
                lengths[0] = 1;
                for (int w = 1; w < 8; w++) {
                    if (((grouping >> (6 - (w - 1))) & 1) == 1) {
                        lengths[groups]++;
                    } else {
                        groups++;
                        lengths[groups] = 1;
                    }
                }
                ics.groups = groups + 1;
                ics.groupLength = java.util.Arrays.copyOf(lengths, ics.groups);
                if (ics.maxSfb > swbShort.length - 1) throw new IllegalStateException("too many bands");
            } else {
                ics.maxSfb = b.read(6);
                if (b.read(1) == 1) throw new IllegalStateException("prediction (AAC Main) isn't supported");
                if (ics.maxSfb > swbLong.length - 1) throw new IllegalStateException("too many bands");
            }
        }

        /** One channel's stream: gain, window (unless shared), sections, scales, pulses, TNS and the frequencies. */
        private void stream(Bits b, Ics ics, boolean common) {
            ics.globalGain = b.read(8);
            if (!common) icsInfo(b, ics);
            sections(b, ics);
            scales(b, ics);
            boolean pulses = b.read(1) == 1;
            int[] pulseOffset = null;
            int[] pulseAmp = null;
            int pulseStart = 0;
            if (pulses) {
                if (ics.isShort()) throw new IllegalStateException("pulses in a short block");
                int count = b.read(2) + 1;
                pulseStart = b.read(6);
                pulseOffset = new int[count];
                pulseAmp = new int[count];
                for (int i = 0; i < count; i++) {
                    pulseOffset[i] = b.read(5);
                    pulseAmp[i] = b.read(4);
                }
            }
            ics.tns = b.read(1) == 1;
            if (ics.tns) tns(b, ics);
            if (b.read(1) == 1) throw new IllegalStateException("gain control (AAC SSR) isn't supported");
            int[] quant = spectral(b, ics);
            if (pulses) {
                int k = swbLong[Math.min(pulseStart, swbLong.length - 1)];
                for (int i = 0; i < pulseOffset.length; i++) {
                    k += pulseOffset[i];
                    if (k >= 1024) break;
                    quant[k] += quant[k] > 0 ? pulseAmp[i] : -pulseAmp[i];
                }
            }
            dequantize(ics, quant);
        }

        private void sections(Bits b, Ics ics) {
            int bits = ics.isShort() ? 3 : 5;
            int escape = (1 << bits) - 1;
            for (int g = 0; g < ics.groups; g++) {
                int k = 0;
                while (k < ics.maxSfb) {
                    int cb = b.read(4);
                    if (cb == 12) throw new IllegalStateException("a reserved codebook");
                    int length = 0;
                    int step;
                    do {
                        step = b.read(bits);
                        length += step;
                    } while (step == escape);
                    if (k + length > ics.maxSfb) throw new IllegalStateException("sections run past the last band");
                    for (int i = 0; i < length; i++) ics.codebook[g][k + i] = cb;
                    k += length;
                }
            }
        }

        private void scales(Bits b, Ics ics) {
            int gain = ics.globalGain;
            int intensity = 0;
            int noiseEnergy = ics.globalGain - 90;
            boolean firstNoise = true;
            for (int g = 0; g < ics.groups; g++) {
                for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                    int cb = ics.codebook[g][sfb];
                    if (cb == ZERO_HCB) {
                        ics.scale[g][sfb] = 0;
                    } else if (cb == INTENSITY_HCB || cb == INTENSITY_HCB2) {
                        intensity += SCF.read(b) - 60;
                        ics.scale[g][sfb] = intensity;
                    } else if (cb == NOISE_HCB) {
                        if (firstNoise) {
                            noiseEnergy += b.read(9) - 256;
                            firstNoise = false;
                        } else {
                            noiseEnergy += SCF.read(b) - 60;
                        }
                        ics.scale[g][sfb] = noiseEnergy;
                    } else {
                        gain += SCF.read(b) - 60;
                        if (gain < 0 || gain > 255) throw new IllegalStateException("a scale out of range");
                        ics.scale[g][sfb] = gain;
                    }
                }
            }
        }

        private void tns(Bits b, Ics ics) {
            boolean shortBlock = ics.isShort();
            int windows = shortBlock ? 8 : 1;
            for (int w = 0; w < windows; w++) {
                int filters = b.read(shortBlock ? 1 : 2);
                ics.tnsFilters[w] = filters;
                if (filters == 0) continue;
                int coefRes = b.read(1);
                for (int f = 0; f < filters; f++) {
                    ics.tnsLength[w][f] = b.read(shortBlock ? 4 : 6);
                    int order = b.read(shortBlock ? 3 : 5);
                    ics.tnsOrder[w][f] = order;
                    if (order == 0) continue;
                    ics.tnsDown[w][f] = b.read(1) == 1;
                    int compress = b.read(1);
                    int bits = coefRes + 3 - compress;
                    float[] coef = new float[order];
                    // Each coefficient: a small signed number, turned into a reflection coefficient with a sine
                    double iqfac = ((1 << (coefRes + 2)) - 0.5) / (Math.PI / 2);
                    double iqfacM = ((1 << (coefRes + 2)) + 0.5) / (Math.PI / 2);
                    for (int i = 0; i < order; i++) {
                        int raw = b.read(bits);
                        int value = (raw & (1 << (bits - 1))) != 0 ? raw - (1 << bits) : raw;
                        coef[i] = (float) Math.sin(value / (value >= 0 ? iqfac : iqfacM));
                    }
                    ics.tnsCoef[w][f] = toLpc(coef);
                }
            }
        }

        /** Reflection coefficients to the filter's own coefficients (the step-up recursion). */
        private static float[] toLpc(float[] parcor) {
            int order = parcor.length;
            float[] a = new float[order + 1];
            float[] tmp = new float[order + 1];
            a[0] = 1;
            for (int m = 1; m <= order; m++) {
                for (int i = 1; i < m; i++) tmp[i] = a[i] + parcor[m - 1] * a[m - i];
                for (int i = 1; i < m; i++) a[i] = tmp[i];
                a[m] = parcor[m - 1];
            }
            return a;
        }

        /** The frequencies, as whole numbers, decoded with each section's codebook. */
        private int[] spectral(Bits b, Ics ics) {
            int[] quant = new int[1024];
            if (ics.isShort()) {
                int window = 0;
                for (int g = 0; g < ics.groups; g++) {
                    for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                        int cb = ics.codebook[g][sfb];
                        int width = swbShort[sfb + 1] - swbShort[sfb];
                        for (int w = 0; w < ics.groupLength[g]; w++) {
                            int base = (window + w) * 128 + swbShort[sfb];
                            if (cb != ZERO_HCB && cb < NOISE_HCB) values(b, cb, quant, base, width);
                        }
                    }
                    window += ics.groupLength[g];
                }
            } else {
                for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                    int cb = ics.codebook[0][sfb];
                    if (cb != ZERO_HCB && cb < NOISE_HCB) values(b, cb, quant, swbLong[sfb], swbLong[sfb + 1] - swbLong[sfb]);
                }
            }
            return quant;
        }

        /** One band's worth of values with one codebook: 4 at a time (books 1-4) or 2 at a time (5-11). */
        private static void values(Bits b, int cb, int[] out, int base, int width) {
            Huffman book = BOOKS[cb];
            boolean unsigned = cb == 3 || cb == 4 || cb >= 7;
            if (cb <= 4) {
                for (int k = 0; k < width; k += 4) {
                    int index = book.read(b);
                    int[] v = unsigned
                            ? new int[] {index / 27, (index / 9) % 3, (index / 3) % 3, index % 3}
                            : new int[] {index / 27 - 1, (index / 9) % 3 - 1, (index / 3) % 3 - 1, index % 3 - 1};
                    if (unsigned) for (int i = 0; i < 4; i++) if (v[i] != 0 && b.bit() == 1) v[i] = -v[i];
                    for (int i = 0; i < 4; i++) out[base + k + i] = v[i];
                }
            } else {
                int modulo = cb <= 6 ? 9 : cb <= 8 ? 8 : cb <= 10 ? 13 : 17;
                int offset = cb <= 6 ? 4 : 0;
                for (int k = 0; k < width; k += 2) {
                    int index = book.read(b);
                    int y = index / modulo - offset;
                    int z = index % modulo - offset;
                    if (unsigned) {
                        if (y != 0 && b.bit() == 1) y = -y;
                        if (z != 0 && b.bit() == 1) z = -z;
                    }
                    if (cb == ESC_HCB) {
                        y = escape(b, y);
                        z = escape(b, z);
                    }
                    out[base + k] = y;
                    out[base + k + 1] = z;
                }
            }
        }

        /** Codebook 11's escape: 16 means a bigger number follows, as a count of 1 bits and then that many + 4 bits. */
        private static int escape(Bits b, int value) {
            int magnitude = Math.abs(value);
            if (magnitude != 16) return value;
            int n = 4;
            while (b.bit() == 1) {
                n++;
                if (n > 12) throw new IllegalStateException("an escape that's too long");
            }
            int big = (1 << n) + b.read(n);
            return value < 0 ? -big : big;
        }

        /** Whole numbers back to frequencies: each to the power 4/3, times 2^((scale - 100) / 4). Noise bands get noise. */
        private void dequantize(Ics ics, int[] quant) {
            float[] spec = ics.spectrum;
            java.util.Arrays.fill(spec, 0);
            int windowStart = 0;
            for (int g = 0; g < ics.groups; g++) {
                int[] swb = ics.isShort() ? swbShort : swbLong;
                int windows = ics.isShort() ? ics.groupLength[g] : 1;
                int lines = ics.isShort() ? 128 : 1024;
                for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                    int cb = ics.codebook[g][sfb];
                    if (cb == ZERO_HCB || cb == INTENSITY_HCB || cb == INTENSITY_HCB2) continue;
                    for (int w = 0; w < windows; w++) {
                        int base = (windowStart + w) * lines;
                        int from = base + swb[sfb];
                        int to = base + swb[sfb + 1];
                        if (cb == NOISE_HCB) {
                            // Perceptual noise: random values with the band's energy
                            double energy = 0;
                            for (int k = from; k < to; k++) {
                                spec[k] = (float) (noise.nextFloat() * 2 - 1);
                                energy += spec[k] * spec[k];
                            }
                            double scale = Math.pow(2, 0.25 * ics.scale[g][sfb]) / Math.sqrt(Math.max(energy, 1e-12));
                            for (int k = from; k < to; k++) spec[k] *= (float) scale;
                            continue;
                        }
                        float scale = (float) Math.pow(2, 0.25 * (ics.scale[g][sfb] - 100));
                        for (int k = from; k < to; k++) {
                            int q = quant[k];
                            int a = Math.abs(q);
                            float v = a < POW43.length ? POW43[a] : (float) Math.pow(a, 4.0 / 3);
                            spec[k] = (q < 0 ? -v : v) * scale;
                        }
                    }
                }
                windowStart += windows;
            }
        }

        /** Mid/side and intensity stereo: the right channel rebuilt from both, band by band. */
        private void stereo(Ics left, Ics right, int msMask, boolean[][] ms) {
            int windowStart = 0;
            boolean shortBlock = left.isShort();
            int[] swb = shortBlock ? swbShort : swbLong;
            int lines = shortBlock ? 128 : 1024;
            for (int g = 0; g < left.groups; g++) {
                int windows = shortBlock ? left.groupLength[g] : 1;
                for (int sfb = 0; sfb < left.maxSfb; sfb++) {
                    int cbRight = right.codebook[g][sfb];
                    boolean msBand = msMask == 2 || msMask == 1 && ms[g][sfb];
                    for (int w = 0; w < windows; w++) {
                        int base = (windowStart + w) * lines;
                        int from = base + swb[sfb];
                        int to = base + swb[sfb + 1];
                        if (cbRight == INTENSITY_HCB || cbRight == INTENSITY_HCB2) {
                            // Intensity: the right is the left, scaled (and maybe flipped)
                            float scale = (float) Math.pow(0.5, 0.25 * right.scale[g][sfb]);
                            boolean flip = (cbRight == INTENSITY_HCB2) != (msMask == 1 && ms[g][sfb]);
                            for (int k = from; k < to; k++) right.spectrum[k] = left.spectrum[k] * (flip ? -scale : scale);
                        } else if (msBand && cbRight != NOISE_HCB && left.codebook[g][sfb] != NOISE_HCB) {
                            for (int k = from; k < to; k++) {
                                float m = left.spectrum[k];
                                float s = right.spectrum[k];
                                left.spectrum[k] = m + s;
                                right.spectrum[k] = m - s;
                            }
                        }
                    }
                }
                windowStart += windows;
            }
        }

        /** The TNS filter, then the inverse MDCT, window and overlap: 1024 new samples. */
        private float[] toSound(Ics ics, int channel) {
            if (ics.tns) applyTns(ics);
            if (channel >= overlap.length) throw new IllegalStateException("too many channels");
            float[] saved = overlap[channel];
            float[] block = new float[2048];
            int shape = ics.windowShape;
            int previous = previousShape[channel];
            float[] longPrev = previous == 1 ? KBD_LONG : SINE_LONG;
            float[] longCur = shape == 1 ? KBD_LONG : SINE_LONG;
            float[] shortPrev = previous == 1 ? KBD_SHORT : SINE_SHORT;
            float[] shortCur = shape == 1 ? KBD_SHORT : SINE_SHORT;
            if (ics.isShort()) {
                for (int w = 0; w < 8; w++) {
                    float[] coef = java.util.Arrays.copyOfRange(ics.spectrum, w * 128, w * 128 + 128);
                    float[] y = imdct(coef, 256);
                    float[] rise = w == 0 ? shortPrev : shortCur;
                    int start = 448 + w * 128;
                    for (int i = 0; i < 128; i++) {
                        block[start + i] += y[i] * rise[i];
                        block[start + 128 + i] += y[128 + i] * shortCur[127 - i];
                    }
                }
            } else {
                float[] y = imdct(ics.spectrum, 2048);
                for (int i = 0; i < 1024; i++) {
                    float rise = switch (ics.windowSequence) {
                        case LONG_STOP -> i < 448 ? 0 : i < 576 ? shortPrev[i - 448] : 1;
                        default -> longPrev[i];
                    };
                    float fall = switch (ics.windowSequence) {
                        case LONG_START -> i < 448 ? 1 : i < 576 ? shortCur[127 - (i - 448)] : 0;
                        default -> longCur[1023 - i];
                    };
                    block[i] = y[i] * rise;
                    block[1024 + i] = y[1024 + i] * fall;
                }
            }
            float[] out = new float[1024];
            for (int i = 0; i < 1024; i++) out[i] = block[i] + saved[i];
            System.arraycopy(block, 1024, saved, 0, 1024);
            previousShape[channel] = shape;
            return out;
        }

        /** The inverse MDCT the standard uses, through Squid's fast one, scaled to sound level. */
        private static float[] imdct(float[] coef, int n) {
            float[] y = Vorbis.imdct(coef, n);
            float scale = 2f / n;
            for (int i = 0; i < y.length; i++) y[i] *= scale;
            return y;
        }

        /** TNS: an all-pole filter run along the frequencies of each filtered range, shaping noise in time. */
        private void applyTns(Ics ics) {
            boolean shortBlock = ics.isShort();
            int windows = shortBlock ? 8 : 1;
            int[] swb = shortBlock ? swbShort : swbLong;
            int maxBands = Math.min(shortBlock ? TNS_MAX_SHORT[rateIndex] : TNS_MAX_LONG[rateIndex], ics.maxSfb);
            int lines = shortBlock ? 128 : 1024;
            for (int w = 0; w < windows; w++) {
                int bottom = shortBlock ? swb.length - 1 : swb.length - 1;
                for (int f = 0; f < ics.tnsFilters[w]; f++) {
                    int top = bottom;
                    bottom = Math.max(top - ics.tnsLength[w][f], 0);
                    int order = ics.tnsOrder[w][f];
                    if (order == 0) continue;
                    int start = swb[Math.min(bottom, maxBands)];
                    int end = swb[Math.min(top, maxBands)];
                    int size = end - start;
                    if (size <= 0) continue;
                    float[] a = ics.tnsCoef[w][f];
                    float[] spec = ics.spectrum;
                    int base = w * lines;
                    float[] state = new float[order];
                    int index = ics.tnsDown[w][f] ? base + end - 1 : base + start;
                    int step = ics.tnsDown[w][f] ? -1 : 1;
                    for (int i = 0; i < size; i++, index += step) {
                        float y = spec[index];
                        for (int j = 0; j < order; j++) y -= state[j] * a[j + 1];
                        for (int j = order - 1; j > 0; j--) state[j] = state[j - 1];
                        state[0] = y;
                        spec[index] = y;
                    }
                }
            }
        }
    }
}
