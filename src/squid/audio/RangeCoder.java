package squid.audio;

import java.io.ByteArrayOutputStream;

/**
 * A range coder: it packs yes/no choices into bits, using fewer than one bit for a choice that's usually the same
 * way. Each kind of choice has a "model" (a guess of how likely yes is) that learns as it goes, on both ends alike,
 * so the guesses never have to be sent. It's the same kind of coder LZMA (7-Zip) uses, and works the same way.
 *
 * Bigger numbers are sent bit by bit down a tree of models ({@link Coder#tree}), so each bit learns from the ones
 * before it. The encoder and decoder share one {@link Coder} shape, so a format can be written once for both:
 * the encoder sends the value it's given, the decoder ignores it and returns what it read.
 */
final class RangeCoder {
    private static final int TOTAL = 1 << 11; // models are out of 2048
    private static final int ADAPT = 4;       // how fast a model learns (bigger is slower and steadier)
    private static final int TOP = 1 << 24;


    private RangeCoder() {
    }

    /** A fresh set of models, each starting at "could go either way". */
    static short[] models(int count) {
        short[] m = new short[count];
        java.util.Arrays.fill(m, (short) (TOTAL / 2));
        return m;
    }

    /** Both ends: the encoder sends `value` and gives it back; the decoder reads one and gives that back. */
    interface Coder {
        int bit(short[] models, int index, int value);

        /** Bits sent as they are (each a fair coin), for things no model would guess, like signs. */
        int direct(int value, int bits);

        /** A number of `bits` bits down a tree of models at models[base + 1 ..], top bit first. */
        default int tree(short[] models, int base, int bits, int value) {
            int node = 1;
            for (int i = bits - 1; i >= 0; i--) node = (node << 1) | bit(models, base + node, (value >>> i) & 1);
            return node - (1 << bits);
        }
    }

    static final class Encoder implements Coder {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long low;
        private int range = 0xFFFFFFFF;
        private int cache;
        private long cacheSize = 1;

        @Override
        public int bit(short[] models, int index, int bit) {
            int p = models[index];
            int bound = (range >>> 11) * p;
            if (bit == 0) {
                range = bound;
                models[index] = (short) (p + ((TOTAL - p) >>> ADAPT));
            } else {
                low += bound & 0xFFFFFFFFL;
                range -= bound;
                models[index] = (short) (p - (p >>> ADAPT));
            }
            while (Integer.compareUnsigned(range, TOP) < 0) {
                range <<= 8;
                shiftLow();
            }
            return bit;
        }

        @Override
        public int direct(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                range >>>= 1;
                if (((value >>> i) & 1) != 0) low += range & 0xFFFFFFFFL;
                while (Integer.compareUnsigned(range, TOP) < 0) {
                    range <<= 8;
                    shiftLow();
                }
            }
            return value;
        }

        private void shiftLow() {
            if (low < 0xFF000000L || low > 0xFFFFFFFFL) {
                int carry = (int) (low >>> 32);
                int temp = cache;
                do {
                    out.write(temp + carry);
                    temp = 0xFF;
                } while (--cacheSize != 0);
                cache = (int) (low >>> 24) & 0xFF;
            }
            cacheSize++;
            low = (low & 0x00FFFFFFL) << 8;
        }

        byte[] finish() {
            for (int i = 0; i < 5; i++) shiftLow();
            byte[] all = out.toByteArray();
            return java.util.Arrays.copyOfRange(all, 1, all.length); // the first byte is always 0
        }
    }

    static final class Decoder implements Coder {
        private final byte[] data;
        private int at;
        private int range = 0xFFFFFFFF;
        private int code;

        Decoder(byte[] data) {
            this.data = data;
            for (int i = 0; i < 4; i++) code = (code << 8) | next();
        }

        private int next() {
            return at < data.length ? data[at++] & 0xFF : 0;
        }

        @Override
        public int bit(short[] models, int index, int ignored) {
            int p = models[index];
            int bound = (range >>> 11) * p;
            int bit;
            if (Integer.compareUnsigned(code, bound) < 0) {
                range = bound;
                models[index] = (short) (p + ((TOTAL - p) >>> ADAPT));
                bit = 0;
            } else {
                code -= bound;
                range -= bound;
                models[index] = (short) (p - (p >>> ADAPT));
                bit = 1;
            }
            while (Integer.compareUnsigned(range, TOP) < 0) {
                range <<= 8;
                code = (code << 8) | next();
            }
            return bit;
        }

        @Override
        public int direct(int ignored, int bits) {
            int value = 0;
            for (int i = 0; i < bits; i++) {
                range >>>= 1;
                int t = (code - range) >>> 31; // 1 if code is below range
                code -= range & (t - 1);
                value = (value << 1) | (1 - t);
                while (Integer.compareUnsigned(range, TOP) < 0) {
                    range <<= 8;
                    code = (code << 8) | next();
                }
            }
            return value;
        }
    }
}
