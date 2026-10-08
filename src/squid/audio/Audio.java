package squid.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Squid's own sound decoders, from scratch: give it a sound file's bytes and get 16-bit samples back. It tells the
 * kind of file from its first bytes, not its name: WAV, FLAC, Ogg Vorbis, MP3, and Squid's own .sqda.
 */
public final class Audio {
    private Audio() {
    }

    /** The most samples a decoded sound can have: about 23 minutes of 48 kHz stereo, far longer than any game sound. */
    public static final int MAX_SAMPLES = 1 << 27;

    /** Whether Squid can decode a file like this. */
    public static boolean canDecode(byte[] data) {
        return Wav.is(data) || Flac.is(data) || Vorbis.is(data) || Mp3.is(data) || Sqda.is(data);
    }

    /**
     * The file's sound. More than 2 channels (like a 5.1 movie track) come back as stereo, because Minecraft's sound
     * engine only plays mono and stereo.
     */
    public static Pcm decode(byte[] data) {
        Pcm pcm;
        if (Wav.is(data)) pcm = Wav.decode(data);
        else if (Flac.is(data)) pcm = Flac.decode(data);
        else if (Vorbis.is(data)) pcm = Vorbis.decode(data);
        else if (Mp3.is(data)) pcm = Mp3.decode(data);
        else if (Sqda.is(data)) pcm = Sqda.read(data).decode(0);
        else throw new IllegalArgumentException("not a sound file Squid can read");
        return pcm.channels() > 2 ? toStereo(pcm) : pcm;
    }

    /**
     * Mixes surround sound down to stereo, the way most players fold 5.1 and 7.1 down: front left and right as they
     * are, the center a bit quieter into both, the bass channel (LFE) left out, and the side and back channels a bit
     * quieter into their own side. Files list their channels in the standard WAV/FLAC/Vorbis order:
     * quad is FL FR BL BR, and 5.0 up to 7.1 are FL FR C (LFE) then the sides and backs, left before right.
     */
    static Pcm toStereo(Pcm pcm) {
        int channels = pcm.channels();
        boolean center = channels != 4;
        boolean lfe = channels >= 6;
        int surround = center ? (lfe ? 4 : 3) : 2; // the first side or back channel
        short[] in = pcm.samples();
        int frames = in.length / channels;
        short[] out = new short[frames * 2];
        for (int f = 0; f < frames; f++) {
            int at = f * channels;
            double left = in[at];
            double right = in[at + 1];
            if (center) {
                left += in[at + 2] * 0.7071;
                right += in[at + 2] * 0.7071;
            }
            for (int c = surround; c < channels; c++) {
                if ((c - surround) % 2 == 0) left += in[at + c] * 0.7071;
                else right += in[at + c] * 0.7071;
            }
            out[f * 2] = clip(left);
            out[f * 2 + 1] = clip(right);
        }
        return new Pcm(out, 2, pcm.rate());
    }

    private static short clip(double v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
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
                long size = b.getInt(at + 4) & 0xFFFFFFFFL; // sizes are unsigned, so a broken one can't point backwards
                int body = at + 8;
                if (id.equals("fmt ")) {
                    if (size < 16 || body + 16 > d.length) throw new IllegalArgumentException("the WAV file's format is cut short");
                    format = b.getShort(body) & 0xFFFF;
                    channels = b.getShort(body + 2) & 0xFFFF;
                    rate = b.getInt(body + 4);
                    bits = b.getShort(body + 14) & 0xFFFF;
                    // WAVE_FORMAT_EXTENSIBLE: the real format inside
                    if (format == 0xFFFE && size >= 26 && body + 26 <= d.length) format = b.getShort(body + 24) & 0xFFFF;
                } else if (id.equals("data")) {
                    int end = (int) Math.min(d.length, body + size); // a recording cut short still plays what's there
                    return samples(b, body, end, format, channels, rate, bits);
                }
                long next = body + size + (size & 1);
                if (next > d.length) break;
                at = (int) next;
            }
            throw new IllegalArgumentException("the WAV file has no sound data");
        }

        private static Pcm samples(ByteBuffer b, int from, int to, int format, int channels, int rate, int bits) {
            if (channels == 0) throw new IllegalArgumentException("the WAV file has no format");
            if (channels > 8) throw new IllegalArgumentException("the WAV file says it has " + channels + " channels");
            if (rate < 1000 || rate > 384000) throw new IllegalArgumentException("the WAV file's sample rate (" + rate + ") isn't real");
            // Only plain samples (1) and decimals (3). Others, like the phone formats mu-law and A-law, would play as noise.
            if (format != 1 && format != 3) throw new IllegalArgumentException("WAV format " + format + " isn't supported: save it as plain PCM");
            boolean ok = format == 1 ? bits == 8 || bits == 16 || bits == 24 || bits == 32 : bits == 32 || bits == 64;
            if (!ok) throw new IllegalArgumentException("WAV with " + bits + "-bit samples isn't supported");
            int width = bits / 8;
            int count = (to - from) / width / channels * channels; // whole moments only
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
