package squid.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Squid's own sound decoders, from scratch: give it a sound file's bytes and get 16-bit samples back. It tells the
 * kind of file from its first bytes, not its name: WAV, FLAC, Ogg Vorbis and MP3.
 */
public final class Audio {
    private Audio() {
    }

    /** Whether Squid can decode a file like this. */
    public static boolean canDecode(byte[] data) {
        return Wav.is(data) || Flac.is(data) || Vorbis.is(data) || Mp3.is(data);
    }

    public static Pcm decode(byte[] data) {
        if (Wav.is(data)) return Wav.decode(data);
        if (Flac.is(data)) return Flac.decode(data);
        if (Vorbis.is(data)) return Vorbis.decode(data);
        if (Mp3.is(data)) return Mp3.decode(data);
        throw new IllegalArgumentException("not a sound file Squid can read");
    }

    /** WAV: a RIFF file with a "fmt " chunk (the format) and a "data" chunk (the samples, as they are). */
    static final class Wav {
        private Wav() {
        }

        static boolean is(byte[] d) {
            return d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'A' && d[10] == 'V' && d[11] == 'E';
        }

        static Pcm decode(byte[] d) {
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            int at = 12;
            int format = 0;
            int channels = 0;
            int rate = 0;
            int bits = 0;
            while (at + 8 <= d.length) {
                String id = new String(d, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
                int size = b.getInt(at + 4);
                int body = at + 8;
                if (id.equals("fmt ")) {
                    format = b.getShort(body) & 0xFFFF;
                    channels = b.getShort(body + 2) & 0xFFFF;
                    rate = b.getInt(body + 4);
                    bits = b.getShort(body + 14) & 0xFFFF;
                    if (format == 0xFFFE && size >= 26) format = b.getShort(body + 24) & 0xFFFF; // WAVE_FORMAT_EXTENSIBLE: the real format inside
                } else if (id.equals("data")) {
                    int end = Math.min(d.length, body + Math.max(0, size));
                    return samples(b, body, end, format, channels, rate, bits);
                }
                at = body + size + (size & 1);
            }
            throw new IllegalArgumentException("the WAV file has no sound data");
        }

        private static Pcm samples(ByteBuffer b, int from, int to, int format, int channels, int rate, int bits) {
            if (channels == 0) throw new IllegalArgumentException("the WAV file has no format");
            int width = bits / 8;
            int count = (to - from) / width;
            short[] out = new short[count];
            for (int i = 0; i < count; i++) {
                int at = from + i * width;
                out[i] = switch (format == 3 ? -bits : bits) {
                    case 8 -> (short) (((b.get(at) & 0xFF) - 128) << 8);
                    case 16 -> b.getShort(at);
                    case 24 -> (short) ((b.get(at + 2) << 8) | (b.get(at + 1) & 0xFF));
                    case 32 -> (short) (b.getInt(at) >> 16);
                    case -32 -> (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(b.getFloat(at) * 32767)));
                    case -64 -> (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(b.getDouble(at) * 32767)));
                    default -> throw new IllegalArgumentException("WAV with " + bits + "-bit samples isn't supported");
                };
            }
            return new Pcm(out, channels, rate);
        }
    }
}
