package squid.audio;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A .sqda file ("Squid audio"): sound made to be extra nice with Squid. Besides the sound (squeezed with
 * {@link MusicCodec}), it can carry:
 *
 * - Variants: several takes of the sound, one picked at random each time it plays (by weight), like sounds.json.
 * - Loop points: where a looping sound jumps back to (so a song can have an intro, then loop its middle forever).
 * - A volume track: how loud it is every 1024 samples, for visualizers and lights that pulse to the music.
 * - Cues: beats, bars, sections and named markers, at exact moments.
 * - Light cues: when lights should turn on, flash, fade or strobe, in what color, for how long, and which lights.
 * - Sound settings: subtitle, volume, pitch, how far it carries, and whether it streams.
 * - Triggers: sounds to play when an entity (like a creeper) comes into view, leaves it, or comes near, while
 *   this sound plays.
 * - Info: title, artist, album, year, license, and anything else as text.
 *
 * The file is "SQDA", a version byte, then chunks like a PNG: a 4-letter name, a length, and the chunk. Chunks a
 * reader doesn't know are skipped, so new kinds can be added without breaking older Squids. Numbers are big-endian,
 * text is a 2-byte length then UTF-8. The sound (VARI chunks) comes last, so the rest can be read quickly.
 */
public final class Sqda {
    public static final int VERSION = 1;

    /** One take of the sound. Frames are Squid Music frames. A weight of 0 means it only plays from a trigger. */
    public record Variant(String name, int weight, int rate, int channels, long samples, List<byte[]> frames) {
        public double seconds() {
            return samples / (double) rate;
        }
    }

    /** Where a looping variant jumps back to: from `end` back to `start` (in samples). */
    public record Loop(int variant, long start, long end) {
    }

    /** How loud a variant is: one value per `step` samples per channel, 0 (silent, -96 dB) to 255 (full). */
    public record Levels(int variant, int step, int channels, byte[] values) {
        /** The loudness at a moment, 0 to 1, louder of the channels. */
        public float at(long sample) {
            int i = (int) (sample / step);
            if (i < 0 || (long) (i + 1) * channels > values.length) return 0;
            int best = 0;
            for (int c = 0; c < channels; c++) best = Math.max(best, values[i * channels + c] & 0xFF);
            return best / 255f;
        }
    }

    public static final int CUE = 0;
    public static final int BEAT = 1;
    public static final int BAR = 2;
    public static final int SECTION = 3;

    /** A marker at a moment: a beat, a bar, a section, or a named cue. */
    public record Cue(int variant, long at, int kind, String name) {
    }

    public static final int LIGHT_ON = 0;
    public static final int LIGHT_FLASH = 1;
    public static final int LIGHT_FADE = 2;
    public static final int LIGHT_STROBE = 3;

    /** A light cue: at a moment, for a length (in samples), a color (0xRRGGBB), a brightness (0-255), an effect, and which lights. */
    public record Light(int variant, long at, long length, int color, int brightness, int effect, String group) {
    }

    /** Sound settings, like the ones in sounds.json. Stream: 0 lets Squid decide, 1 always, 2 never. */
    public record Settings(String subtitle, float volume, float pitch, int distance, int stream) {
        public static final Settings DEFAULT = new Settings("", 1, 1, 16, 0);
    }

    public static final int ENTERS_VIEW = 0;
    public static final int LEAVES_VIEW = 1;
    public static final int COMES_NEAR = 2;

    /**
     * While this plays: when an entity of a type (like "minecraft:creeper") does something (comes into view, leaves
     * it, or comes within `distance` blocks), play a sound: a Minecraft sound like "minecraft:entity.creeper.primed",
     * or "variant:name" for one of this file's own variants. At most once every `cooldown` ticks.
     */
    public record Trigger(String entity, int event, float distance, String sound, float volume, float pitch, int cooldown) {
    }

    public final List<Variant> variants = new ArrayList<>();
    public final List<Loop> loops = new ArrayList<>();
    public final List<Levels> levels = new ArrayList<>();
    public final List<Cue> cues = new ArrayList<>();
    public final List<Light> lights = new ArrayList<>();
    public final List<Trigger> triggers = new ArrayList<>();
    public final Map<String, String> info = new LinkedHashMap<>();
    public Settings settings = Settings.DEFAULT;

    /** Whether the bytes are a .sqda file. */
    public static boolean is(byte[] d) {
        return d.length >= 5 && d[0] == 'S' && d[1] == 'Q' && d[2] == 'D' && d[3] == 'A';
    }

    /** A .sqda with one variant, from a sound, with its volume track worked out. */
    public static Sqda fromSound(Pcm pcm, int quality) {
        Sqda s = new Sqda();
        s.addVariant("main", 1, pcm, quality);
        return s;
    }

    /** Adds a variant (and its volume track). Returns its number. */
    public int addVariant(String name, int weight, Pcm pcm, int quality) {
        MusicCodec.Encoded e = MusicCodec.encode(pcm, quality);
        variants.add(new Variant(name, Math.max(0, weight), e.rate(), e.channels(), e.samples(), e.frames()));
        int index = variants.size() - 1;
        levels.add(measure(index, pcm));
        return index;
    }

    /** The volume track: the loudness of every 1024 samples, in 96 dB from silent to full. */
    static Levels measure(int variant, Pcm pcm) {
        int step = MusicCodec.FRAME;
        int channels = pcm.channels();
        int moments = pcm.samples().length / channels;
        int count = (moments + step - 1) / step;
        byte[] values = new byte[count * channels];
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < channels; c++) {
                double sum = 0;
                int n = 0;
                for (int j = i * step; j < Math.min(moments, (i + 1) * step); j++, n++) {
                    double v = pcm.samples()[j * channels + c] / 32768.0;
                    sum += v * v;
                }
                double db = n == 0 || sum == 0 ? -96 : 10 * Math.log10(sum / n) + 3; // +3: a full-scale sine reads 0 dB
                values[i * channels + c] = (byte) Math.round(Math.clamp((db + 96) / 96, 0, 1) * 255);
            }
        }
        return new Levels(variant, step, channels, values);
    }

    /** Picks a variant at random, by weight. Weight 0 is never picked (it's only for triggers). */
    public int pickVariant(Random random) {
        int total = 0;
        for (Variant v : variants) total += v.weight();
        if (total <= 0) return 0;
        int roll = random.nextInt(total);
        for (int i = 0; i < variants.size(); i++) {
            roll -= variants.get(i).weight();
            if (roll < 0) return i;
        }
        return 0;
    }

    public Loop loopOf(int variant) {
        for (Loop l : loops) if (l.variant() == variant) return l;
        return null;
    }

    public Levels levelsOf(int variant) {
        for (Levels l : levels) if (l.variant() == variant) return l;
        return null;
    }

    /** A whole variant as sound. */
    public Pcm decode(int variant) {
        Variant v = variants.get(variant);
        return MusicCodec.decode(new MusicCodec.Encoded(v.rate(), v.channels(), v.samples(), v.frames()));
    }

    /** Plays a variant a piece at a time, looping at its loop points (or start to end) if asked. */
    public Player play(int variant, boolean looping) {
        return new Player(variants.get(variant), looping ? loopOf(variant) : null, looping);
    }

    // ---- Writing ----

    public byte[] write() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeBytes("SQDA");
            out.writeByte(VERSION);
            if (!info.isEmpty()) chunk(out, "INFO", d -> {
                d.writeShort(info.size());
                for (Map.Entry<String, String> e : info.entrySet()) {
                    d.writeUTF(e.getKey());
                    d.writeUTF(e.getValue());
                }
            });
            if (!settings.equals(Settings.DEFAULT)) chunk(out, "SNDS", d -> {
                d.writeUTF(settings.subtitle());
                d.writeFloat(settings.volume());
                d.writeFloat(settings.pitch());
                d.writeInt(settings.distance());
                d.writeByte(settings.stream());
            });
            for (Loop l : loops) chunk(out, "LOOP", d -> {
                d.writeShort(l.variant());
                d.writeLong(l.start());
                d.writeLong(l.end());
            });
            if (!cues.isEmpty()) chunk(out, "CUES", d -> {
                d.writeInt(cues.size());
                for (Cue c : cues) {
                    d.writeShort(c.variant());
                    d.writeLong(c.at());
                    d.writeByte(c.kind());
                    d.writeUTF(c.name());
                }
            });
            if (!lights.isEmpty()) chunk(out, "LITE", d -> {
                d.writeInt(lights.size());
                for (Light l : lights) {
                    d.writeShort(l.variant());
                    d.writeLong(l.at());
                    d.writeLong(l.length());
                    d.writeInt(l.color());
                    d.writeByte(l.brightness());
                    d.writeByte(l.effect());
                    d.writeUTF(l.group());
                }
            });
            if (!triggers.isEmpty()) chunk(out, "TRIG", d -> {
                d.writeShort(triggers.size());
                for (Trigger t : triggers) {
                    d.writeUTF(t.entity());
                    d.writeByte(t.event());
                    d.writeFloat(t.distance());
                    d.writeUTF(t.sound());
                    d.writeFloat(t.volume());
                    d.writeFloat(t.pitch());
                    d.writeInt(t.cooldown());
                }
            });
            for (Levels l : levels) chunk(out, "LEVL", d -> {
                d.writeShort(l.variant());
                d.writeShort(l.step());
                d.writeByte(l.channels());
                d.writeInt(l.values().length);
                d.write(l.values());
            });
            for (Variant v : variants) chunk(out, "VARI", d -> {
                d.writeUTF(v.name());
                d.writeShort(v.weight());
                d.writeInt(v.rate());
                d.writeByte(v.channels());
                d.writeLong(v.samples());
                d.writeInt(v.frames().size());
                for (byte[] f : v.frames()) {
                    d.writeShort(f.length);
                    d.write(f);
                }
            });
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e); // can't happen writing to memory
        }
    }

    private interface Body {
        void write(DataOutputStream d) throws IOException;
    }

    private static void chunk(DataOutputStream out, String name, Body body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        body.write(new DataOutputStream(bytes));
        out.writeBytes(name);
        out.writeInt(bytes.size());
        bytes.writeTo(out);
    }

    // ---- Reading ----

    public static Sqda read(byte[] data) {
        try {
            return read(new java.io.ByteArrayInputStream(data), true);
        } catch (IOException e) {
            throw new IllegalArgumentException("not a .sqda file Squid can read: " + e.getMessage());
        }
    }

    /**
     * Reads a .sqda. Without the sound (`withSound` false) it stops at the first variant, which is quick even for a
     * long song: everything else comes before the sound.
     */
    public static Sqda read(InputStream stream, boolean withSound) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        byte[] magic = new byte[5];
        in.readFully(magic);
        if (!is(magic)) throw new IOException("it isn't a .sqda file");
        if (magic[4] > VERSION) throw new IOException("it's from a newer Squid (version " + magic[4] + ")");
        Sqda s = new Sqda();
        while (true) {
            byte[] name = new byte[4];
            int got = in.readNBytes(name, 0, 4);
            if (got == 0) break;
            if (got < 4) throw new EOFException("a chunk is cut off");
            String type = new String(name, StandardCharsets.US_ASCII);
            int length = in.readInt();
            if (length < 0) throw new IOException("a chunk is too big");
            if (type.equals("VARI") && !withSound) break;
            byte[] body = in.readNBytes(length);
            if (body.length < length) throw new EOFException("the " + type + " chunk is cut off");
            DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(body));
            switch (type) {
                case "INFO" -> {
                    int n = d.readUnsignedShort();
                    for (int i = 0; i < n; i++) s.info.put(d.readUTF(), d.readUTF());
                }
                case "SNDS" -> s.settings = new Settings(d.readUTF(), d.readFloat(), d.readFloat(), d.readInt(), d.readUnsignedByte());
                case "LOOP" -> s.loops.add(new Loop(d.readUnsignedShort(), d.readLong(), d.readLong()));
                case "CUES" -> {
                    int n = d.readInt();
                    for (int i = 0; i < n; i++) s.cues.add(new Cue(d.readUnsignedShort(), d.readLong(), d.readUnsignedByte(), d.readUTF()));
                }
                case "LITE" -> {
                    int n = d.readInt();
                    for (int i = 0; i < n; i++) {
                        s.lights.add(new Light(d.readUnsignedShort(), d.readLong(), d.readLong(), d.readInt(), d.readUnsignedByte(), d.readUnsignedByte(), d.readUTF()));
                    }
                }
                case "TRIG" -> {
                    int n = d.readUnsignedShort();
                    for (int i = 0; i < n; i++) {
                        s.triggers.add(new Trigger(d.readUTF(), d.readUnsignedByte(), d.readFloat(), d.readUTF(), d.readFloat(), d.readFloat(), d.readInt()));
                    }
                }
                case "LEVL" -> {
                    int variant = d.readUnsignedShort();
                    int step = d.readUnsignedShort();
                    int channels = d.readUnsignedByte();
                    byte[] values = d.readNBytes(d.readInt());
                    s.levels.add(new Levels(variant, Math.max(1, step), Math.max(1, channels), values));
                }
                case "VARI" -> {
                    String vname = d.readUTF();
                    int weight = d.readUnsignedShort();
                    int rate = d.readInt();
                    int channels = d.readUnsignedByte();
                    long samples = d.readLong();
                    int count = d.readInt();
                    if (rate <= 0 || channels < 1 || channels > 2 || samples < 0 || count < 0 || count > body.length / 2) {
                        throw new IOException("a variant's format is broken");
                    }
                    List<byte[]> frames = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) frames.add(d.readNBytes(d.readUnsignedShort()));
                    s.variants.add(new Variant(vname, weight, rate, channels, samples, frames));
                }
                default -> {
                    // A kind of chunk from a newer Squid: skipped
                }
            }
        }
        if (withSound && s.variants.isEmpty()) throw new IOException("it has no sound in it");
        return s;
    }

    // ---- Playing ----

    /**
     * Plays a variant a piece at a time, decoding as it goes (a long song never needs to be decoded all at once).
     * When it loops, it jumps from the loop's end back to its start with a 6 ms crossfade, so the seam doesn't click.
     */
    public static final class Player {
        private static final int FADE = 256;
        private final Variant variant;
        private final boolean looping;
        private final long loopStart;
        private final long loopEnd;
        private final MusicCodec.Decoder decoder;
        private int nextFrame;
        private short[] chunk;
        private long chunkStart;
        private long position;
        private int loops;

        Player(Variant variant, Loop loop, boolean looping) {
            this.variant = variant;
            this.looping = looping;
            long start = loop == null ? 0 : Math.clamp(loop.start(), 0, variant.samples());
            long end = loop == null ? variant.samples() : Math.clamp(loop.end(), 0, variant.samples());
            if (end - start < FADE * 2) {
                start = 0;
                end = variant.samples();
            }
            loopStart = start;
            loopEnd = end;
            decoder = new MusicCodec.Decoder(variant.channels());
            seek(0);
        }

        public int rate() {
            return variant.rate();
        }

        public int channels() {
            return variant.channels();
        }

        /** Where playback is, in samples from the start of the file. */
        public long position() {
            return position;
        }

        /** Where it jumps back to and from when looping (start and end of the whole sound without loop points). */
        public long loopStart() {
            return loopStart;
        }

        public long loopEnd() {
            return loopEnd;
        }

        public boolean looping() {
            return looping;
        }

        /** How many times it's looped so far. */
        public int loops() {
            return loops;
        }

        /** Jumps to a moment (in samples). */
        public void seek(long sample) {
            sample = Math.clamp(sample, 0, variant.samples());
            long frame = sample / MusicCodec.FRAME + 1; // the frame whose sound holds this moment
            int group = (int) ((frame - 1) / MusicCodec.GROUP * MusicCodec.GROUP);
            decoder.startAt(group);
            nextFrame = group;
            while (nextFrame < frame && nextFrame < variant.frames().size()) decoder.decode(variant.frames().get(nextFrame++));
            nextChunk();
            position = sample;
        }

        private void nextChunk() {
            if (nextFrame >= variant.frames().size()) {
                chunk = null;
                return;
            }
            chunk = decoder.decode(variant.frames().get(nextFrame));
            chunkStart = (long) (nextFrame - 1) * MusicCodec.FRAME;
            nextFrame++;
        }

        /** Up to `count` moments of sound (channel after channel), or null when it has ended. */
        public short[] read(int count) {
            long end = looping ? loopEnd : variant.samples();
            if (position >= end) {
                if (!looping) return null;
                return seam(count);
            }
            int n = (int) Math.min(count, end - position);
            short[] out = new short[n * variant.channels()];
            int filled = take(out, 0, n);
            if (filled < n) return filled == 0 ? null : java.util.Arrays.copyOf(out, filled * variant.channels());
            return out;
        }

        /** Copies the next moments into out, decoding frames as needed. Returns how many it got. */
        private int take(short[] out, int at, int n) {
            int ch = variant.channels();
            int done = 0;
            while (done < n) {
                if (chunk == null) break;
                long inChunk = position - chunkStart;
                if (inChunk >= MusicCodec.FRAME) {
                    nextChunk();
                    continue;
                }
                int count = (int) Math.min(n - done, MusicCodec.FRAME - inChunk);
                System.arraycopy(chunk, (int) inChunk * ch, out, (at + done) * ch, count * ch);
                done += count;
                position += count;
            }
            return done;
        }

        /** The jump from the loop's end back to its start: the sound after the end fades out as the start fades in. */
        private short[] seam(int count) {
            int ch = variant.channels();
            short[] tail = new short[FADE * ch];
            int got = take(tail, 0, FADE);
            seek(loopStart);
            loops++;
            short[] out = new short[Math.max(FADE, count) * ch];
            int head = take(out, 0, Math.max(FADE, count));
            for (int i = 0; i < Math.min(got, FADE); i++) {
                float in = (i + 0.5f) / FADE;
                for (int c = 0; c < ch; c++) {
                    int v = Math.round(out[i * ch + c] * in + tail[i * ch + c] * (1 - in));
                    out[i * ch + c] = (short) Math.clamp(v, Short.MIN_VALUE, Short.MAX_VALUE);
                }
            }
            return head == out.length / ch ? out : java.util.Arrays.copyOf(out, head * ch);
        }
    }
}
