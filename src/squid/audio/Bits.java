package squid.audio;

/**
 * Reads a file bit by bit, most significant bit first, the way FLAC and MP3 pack their numbers. Running past the end
 * throws, so a cut-off file stops cleanly instead of reading garbage.
 */
final class Bits {
    private final byte[] data;
    private final int end;
    private long position; // in bits

    Bits(byte[] data, int offset, int end) {
        this.data = data;
        this.end = end;
        this.position = (long) offset * 8;
    }

    /** n bits (0 to 32) as a whole number, unsigned. */
    int read(int n) {
        if (n == 0) return 0;
        long value = 0;
        for (int i = 0; i < n; i++) value = (value << 1) | bit();
        return (int) value;
    }

    /** n bits (up to 64) as a whole number. */
    long readLong(int n) {
        long value = 0;
        for (int i = 0; i < n; i++) value = (value << 1) | bit();
        return value;
    }

    /** n bits as a signed number (two's complement). */
    int readSigned(int n) {
        if (n == 0) return 0;
        long value = readLong(n);
        long sign = 1L << (n - 1);
        return (int) ((value ^ sign) - sign);
    }

    int bit() {
        long byteIndex = position >>> 3;
        if (byteIndex >= end) throw new IllegalStateException("the file ends too early");
        int bit = (data[(int) byteIndex] >>> (7 - (int) (position & 7))) & 1;
        position++;
        return bit;
    }

    /** How many 0 bits come before the next 1 (which is read too). */
    int unary() {
        int count = 0;
        while (bit() == 0) count++;
        return count;
    }

    /** A Rice-coded signed number with parameter k (FLAC's residuals): unary high part, k low bits, zigzag sign. */
    int rice(int k) {
        long high = unary();
        long value = (high << k) | readLong(k);
        return (int) ((value >>> 1) ^ -(value & 1));
    }

    void alignToByte() {
        position = (position + 7) & ~7L;
    }

    int bytePosition() {
        return (int) (position >>> 3);
    }

    void skipBytes(int n) {
        position += (long) n * 8;
    }

    boolean atEnd() {
        return (position >>> 3) >= end;
    }

    long bitPosition() {
        return position;
    }

    void seekBit(long bit) {
        position = bit;
    }
}
