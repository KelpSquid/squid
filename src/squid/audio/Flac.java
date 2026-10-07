package squid.audio;

/**
 * A FLAC decoder, from scratch. FLAC squeezes sound without losing anything: each block of samples is described as a
 * guess (a prediction from the samples before it) plus the small differences between the guess and the real sound,
 * and those differences are written with Rice codes, which make small numbers short. Decoding redoes the guesses
 * and adds the differences back. Stereo is often stored as "middle and side" (the average and the difference of left
 * and right), which this turns back into left and right. Any bit depth comes out as 16 bits.
 */
public final class Flac {
    private Flac() {
    }

    public static boolean is(byte[] data) {
        return data.length >= 4 && data[0] == 'f' && data[1] == 'L' && data[2] == 'a' && data[3] == 'C';
    }

    public static Pcm decode(byte[] data) {
        if (!is(data)) throw new IllegalArgumentException("not a FLAC file");
        // The header blocks: STREAMINFO (always first) says the rate, channels and bit depth
        int at = 4;
        int rate = 0;
        int channels = 0;
        int bitsPerSample = 0;
        long totalSamples = 0;
        boolean last = false;
        while (!last) {
            if (at + 4 > data.length) throw new IllegalArgumentException("the FLAC header is cut off");
            last = (data[at] & 0x80) != 0;
            int type = data[at] & 0x7F;
            int length = ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
            if (type == 0) {
                Bits info = new Bits(data, at + 4, at + 4 + length);
                info.read(16); // smallest block
                info.read(16); // biggest block
                info.read(24); // smallest frame
                info.read(24); // biggest frame
                rate = info.read(20);
                channels = info.read(3) + 1;
                bitsPerSample = info.read(5) + 1;
                totalSamples = info.readLong(36);
            }
            at += 4 + length;
        }
        if (rate == 0) throw new IllegalArgumentException("the FLAC file has no STREAMINFO");

        int capacity = totalSamples > 0 ? (int) Math.min(Integer.MAX_VALUE / 2, totalSamples * channels) : 1 << 20;
        short[] out = new short[capacity];
        int written = 0;
        Bits bits = new Bits(data, at, data.length);
        while (!bits.atEnd()) {
            // Find the next frame's sync code (14 bits: 11111111111110)
            bits.alignToByte();
            int p = bits.bytePosition();
            if (p + 1 >= data.length) break;
            if ((data[p] & 0xFF) != 0xFF || (data[p + 1] & 0xFC) != 0xF8) {
                bits.skipBytes(1);
                continue;
            }
            Frame frame;
            long start = bits.bitPosition();
            try {
                frame = frame(bits, rate, channels, bitsPerSample);
            } catch (RuntimeException e) {
                bits.seekBit(start + 8); // not really a frame (or a broken one): look for the next
                continue;
            }
            int n = frame.samples[0].length;
            if (written + n * channels > out.length) out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, written + n * channels));
            int shift = frame.bitsPerSample - 16;
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < channels; c++) {
                    int s = frame.samples[c][i];
                    s = shift > 0 ? s >> shift : s << -shift;
                    out[written++] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
                }
            }
        }
        return new Pcm(java.util.Arrays.copyOf(out, written), channels, rate);
    }

    private record Frame(int[][] samples, int bitsPerSample) {
    }

    private static Frame frame(Bits bits, int streamRate, int streamChannels, int streamBits) {
        bits.read(14); // sync
        if (bits.read(1) != 0) throw new IllegalStateException("reserved bit set");
        bits.read(1); // fixed or variable block size: the header says the size either way
        int sizeCode = bits.read(4);
        int rateCode = bits.read(4);
        int channelCode = bits.read(4);
        int bitsCode = bits.read(3);
        if (bits.read(1) != 0) throw new IllegalStateException("reserved bit set");
        // The frame's number, written like UTF-8: count the leading 1 bits of the first byte
        int first = bits.read(8);
        int extra = 0;
        while (extra < 7 && (first & (0x80 >> extra)) != 0) extra++;
        if (extra == 1 || extra > 7) throw new IllegalStateException("bad frame number");
        for (int i = 1; i < extra; i++) bits.read(8);

        int blockSize = switch (sizeCode) {
            case 0 -> throw new IllegalStateException("reserved block size");
            case 1 -> 192;
            case 2, 3, 4, 5 -> 576 << (sizeCode - 2);
            case 6 -> bits.read(8) + 1;
            case 7 -> bits.read(16) + 1;
            default -> 256 << (sizeCode - 8);
        };
        switch (rateCode) {
            case 12 -> bits.read(8);
            case 13, 14 -> bits.read(16);
            case 15 -> throw new IllegalStateException("bad sample rate");
            default -> {
            }
        }
        int bitsPerSample = switch (bitsCode) {
            case 0 -> streamBits;
            case 1 -> 8;
            case 2 -> 12;
            case 4 -> 16;
            case 5 -> 20;
            case 6 -> 24;
            case 7 -> 32;
            default -> throw new IllegalStateException("reserved bit depth");
        };
        bits.read(8); // CRC-8 of the header

        int channels = channelCode < 8 ? channelCode + 1 : 2;
        if (channelCode > 10) throw new IllegalStateException("reserved channel layout");
        if (channels != streamChannels) throw new IllegalStateException("channel count changed");
        int[][] samples = new int[channels][];
        for (int c = 0; c < channels; c++) {
            // The "side" channel (a difference of two channels) needs one more bit
            boolean side = (channelCode == 8 && c == 1) || (channelCode == 9 && c == 0) || (channelCode == 10 && c == 1);
            samples[c] = subframe(bits, blockSize, bitsPerSample + (side ? 1 : 0));
        }
        bits.alignToByte();
        bits.read(16); // CRC-16 of the frame

        // Back from left/side, side/right or mid/side to left and right
        if (channelCode == 8) {
            for (int i = 0; i < blockSize; i++) samples[1][i] = samples[0][i] - samples[1][i];
        } else if (channelCode == 9) {
            for (int i = 0; i < blockSize; i++) samples[0][i] = samples[0][i] + samples[1][i];
        } else if (channelCode == 10) {
            for (int i = 0; i < blockSize; i++) {
                int side = samples[1][i];
                int mid = (samples[0][i] << 1) | (side & 1);
                samples[0][i] = (mid + side) >> 1;
                samples[1][i] = (mid - side) >> 1;
            }
        }
        return new Frame(samples, bitsPerSample);
    }

    private static int[] subframe(Bits bits, int n, int bitsPerSample) {
        if (bits.read(1) != 0) throw new IllegalStateException("bad subframe");
        int type = bits.read(6);
        int wasted = 0;
        if (bits.read(1) == 1) wasted = bits.unary() + 1; // low bits that are always 0, left out
        int size = bitsPerSample - wasted;
        int[] s = new int[n];
        if (type == 0) { // the same sample all the way
            int value = bits.readSigned(size);
            java.util.Arrays.fill(s, value);
        } else if (type == 1) { // written out plainly
            for (int i = 0; i < n; i++) s[i] = bits.readSigned(size);
        } else if (type >= 8 && type <= 12) { // a fixed guess: the samples before, extended in a straight or curved line
            int order = type - 8;
            for (int i = 0; i < order; i++) s[i] = bits.readSigned(size);
            residual(bits, s, order, n);
            for (int i = order; i < n; i++) {
                long guess = switch (order) {
                    case 0 -> 0;
                    case 1 -> s[i - 1];
                    case 2 -> 2L * s[i - 1] - s[i - 2];
                    case 3 -> 3L * s[i - 1] - 3L * s[i - 2] + s[i - 3];
                    default -> 4L * s[i - 1] - 6L * s[i - 2] + 4L * s[i - 3] - s[i - 4];
                };
                s[i] += (int) guess;
            }
        } else if (type >= 32) { // a guess from a weighted sum of the samples before (linear prediction)
            int order = type - 31;
            for (int i = 0; i < order; i++) s[i] = bits.readSigned(size);
            int precision = bits.read(4) + 1;
            if (precision == 16) throw new IllegalStateException("bad precision");
            int shift = bits.readSigned(5);
            if (shift < 0) throw new IllegalStateException("negative shift");
            int[] weights = new int[order];
            for (int i = 0; i < order; i++) weights[i] = bits.readSigned(precision);
            residual(bits, s, order, n);
            for (int i = order; i < n; i++) {
                long sum = 0;
                for (int j = 0; j < order; j++) sum += (long) weights[j] * s[i - 1 - j];
                s[i] += (int) (sum >> shift);
            }
        } else {
            throw new IllegalStateException("reserved subframe type " + type);
        }
        if (wasted > 0) for (int i = 0; i < n; i++) s[i] <<= wasted;
        return s;
    }

    /** The differences between the guesses and the real samples, Rice-coded in partitions. */
    private static void residual(Bits bits, int[] s, int order, int n) {
        int method = bits.read(2);
        if (method > 1) throw new IllegalStateException("reserved residual coding");
        int paramBits = method == 0 ? 4 : 5;
        int escape = method == 0 ? 15 : 31;
        int partitionOrder = bits.read(4);
        int partitions = 1 << partitionOrder;
        int i = order;
        for (int p = 0; p < partitions; p++) {
            int count = (n >> partitionOrder) - (p == 0 ? order : 0);
            int param = bits.read(paramBits);
            if (param == escape) {
                int raw = bits.read(5);
                for (int k = 0; k < count; k++) s[i++] = bits.readSigned(raw);
            } else {
                for (int k = 0; k < count; k++) s[i++] = bits.rice(param);
            }
        }
    }
}
