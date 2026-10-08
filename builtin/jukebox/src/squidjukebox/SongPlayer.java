package squidjukebox;

import squid.audio.Audio;
import squid.audio.Pcm;
import squid.audio.Sqda;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One song playing, on a thread of its own: Squid's decoders read it, and it goes straight to the speakers, a quarter
 * of a second ahead. A .sqda streams as it plays (and loops at its own loop points when repeating); other files are
 * decoded first. When it ends by itself, `ended` runs.
 */
final class SongPlayer {
    private final Path file;
    private final boolean repeat;
    private final Runnable ended;
    private volatile boolean stopped;
    volatile boolean paused;
    /** 0 to 1: the Jukebox's volume times Minecraft's master and music sliders. */
    volatile float volume = 1;
    /** How loud it is right now, 0 to 1, for mods that move with the music. */
    volatile float level;
    volatile long position;
    /** Where the speakers are (a little behind position, by what's waiting in the line), in samples. */
    volatile long heard;
    volatile long length;
    volatile int rate = 1;
    /** What went wrong, in words, or null. */
    volatile String problem;
    /** Whether any sound reached the speakers: a song that did isn't a failure, even if it ends early. */
    volatile boolean started;
    /** The music bars: how loud each of 8 bands is right now, bass to treble, 0 to 1. */
    volatile float[] bars = new float[8];
    /** Whether the bars are shown: they're only measured then. */
    volatile boolean wantBars;
    /** The song's beats: how many seconds apart, and when the first one is (seconds), or 0 if it has no steady beat. */
    volatile double beatEvery;
    volatile double firstBeat;

    SongPlayer(Path file, boolean repeat, Runnable ended) {
        this.file = file;
        this.repeat = repeat;
        this.ended = ended;
    }

    void start() {
        Thread thread = new Thread(this::run, "Squid Jukebox");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        stopped = true;
    }

    /** Seconds played and the song's length, for the progress bar. */
    double seconds() {
        return position / (double) Math.max(1, rate);
    }

    /** Seconds heard: what's coming out of the speakers right now. */
    double heardSeconds() {
        return heard / (double) Math.max(1, rate);
    }

    double lengthSeconds() {
        return length / (double) Math.max(1, rate);
    }

    /** Where the sound comes from: a .sqda as it decodes, or a decoded file. */
    private interface Source {
        int rate();

        int channels();

        long length();

        long position();

        /** The next moments (channel after channel), or null at the end. */
        short[] read(int moments);
    }

    private static final class SqdaSource implements Source {
        private final Sqda.Player player;
        private final long length;

        SqdaSource(Sqda file, boolean repeat) {
            player = file.play(0, repeat);
            length = file.variants.getFirst().samples();
        }

        public int rate() {
            return player.rate();
        }

        public int channels() {
            return player.channels();
        }

        public long length() {
            return length;
        }

        public long position() {
            return player.position();
        }

        public short[] read(int moments) {
            return player.read(moments);
        }
    }

    private static final class PcmSource implements Source {
        private final Pcm pcm;
        private final boolean repeat;
        private int at; // in moments

        PcmSource(Pcm pcm, boolean repeat) {
            this.pcm = pcm;
            this.repeat = repeat;
        }

        public int rate() {
            return pcm.rate();
        }

        public int channels() {
            return pcm.channels();
        }

        public long length() {
            return pcm.samples().length / pcm.channels();
        }

        public long position() {
            return at;
        }

        public short[] read(int moments) {
            int ch = pcm.channels();
            int total = pcm.samples().length / ch;
            if (at >= total) {
                if (!repeat || total == 0) return null;
                at = 0;
            }
            int n = Math.min(moments, total - at);
            short[] out = new short[n * ch];
            System.arraycopy(pcm.samples(), at * ch, out, 0, n * ch);
            at += n;
            return out;
        }
    }

    private void run() {
        SourceDataLine line = null;
        try {
            byte[] data = Files.readAllBytes(file);
            Source source;
            Pcm pcm = null;
            if (Sqda.is(data)) {
                source = new SqdaSource(Sqda.read(data), repeat);
            } else {
                pcm = Audio.decode(data);
                source = new PcmSource(pcm, repeat);
                // Its beat, so mods' onBeat can follow any song (a .sqda brings its own beat cues). Found alongside,
                // so the song starts right away, and a problem finding it never stops the song.
                Pcm song = pcm;
                Thread.ofVirtual().start(() -> {
                    try {
                        squid.audio.Analysis.Tempo tempo = squid.audio.Analysis.tempo(song);
                        if (tempo != null && tempo.confidence() >= 0.2) {
                            firstBeat = tempo.offset();
                            beatEvery = 60 / tempo.bpm();
                        }
                    } catch (Throwable e) {
                        // no beat for this song
                    }
                });
            }
            data = null; // the file's bytes aren't needed any more
            length = source.length();
            rate = source.rate();
            AudioFormat format = new AudioFormat(source.rate(), 16, source.channels(), true, false);
            try {
                line = AudioSystem.getSourceDataLine(format);
                line.open(format, source.rate() * source.channels() * 2 / 4); // a quarter of a second
            } catch (IllegalArgumentException | LineUnavailableException unusual) {
                // A sample rate the speakers won't take (like 96 kHz): played at 48 kHz instead
                if (pcm == null) throw unusual;
                pcm = resample(pcm, 48000);
                source = new PcmSource(pcm, repeat);
                length = source.length();
                rate = source.rate();
                format = new AudioFormat(48000, 16, pcm.channels(), true, false);
                line = AudioSystem.getSourceDataLine(format);
                line.open(format, 48000 * pcm.channels() * 2 / 4);
            }
            line.start();
            byte[] out = new byte[0];
            while (!stopped) {
                if (paused) {
                    line.stop();
                    while (paused && !stopped) Thread.sleep(50);
                    line.start();
                    continue;
                }
                short[] chunk = source.read(2048);
                if (chunk == null) break;
                if (out.length < chunk.length * 2) out = new byte[chunk.length * 2];
                float gain = volume;
                long sum = 0;
                for (int i = 0; i < chunk.length; i++) {
                    int v = Math.round(chunk[i] * gain);
                    v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
                    out[i * 2] = (byte) v;
                    out[i * 2 + 1] = (byte) (v >> 8);
                    sum += (long) chunk[i] * chunk[i];
                }
                // Loudness for mods: how loud the song itself is (not the volume slider), smoothed a little
                float rms = (float) Math.sqrt(sum / (double) Math.max(1, chunk.length)) / 32768f;
                level = level * 0.6f + Math.min(1, rms * 3) * 0.4f;
                if (wantBars) bars = squid.audio.Analysis.bands(chunk, source.channels(), source.rate(), 8);
                line.write(out, 0, chunk.length * 2);
                started = true;
                position = source.position();
                // What the speakers are playing now: what was sent, less what's still waiting in the line
                long waiting = (line.getBufferSize() - line.available()) / (2L * source.channels());
                heard = Math.max(0, position - waiting);
            }
            if (!stopped) line.drain();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception | OutOfMemoryError e) {
            problem = e.getMessage() != null ? e.getMessage() : e.toString();
            System.out.println("[Squid Jukebox] Couldn't play " + file.getFileName() + ": " + problem);
        } finally {
            if (line != null) {
                line.stop();
                line.flush();
                line.close();
            }
            level = 0;
            bars = new float[8];
        }
        if (!stopped) ended.run();
    }

    /** The sound at another sample rate, by drawing straight lines between the samples. */
    static Pcm resample(Pcm pcm, int newRate) {
        int ch = pcm.channels();
        short[] in = pcm.samples();
        int frames = in.length / ch;
        double step = pcm.rate() / (double) newRate;
        int n = (int) (frames / step);
        short[] out = new short[n * ch];
        for (int i = 0; i < n; i++) {
            double at = i * step;
            int a = (int) at;
            int b = Math.min(frames - 1, a + 1);
            double f = at - a;
            for (int c = 0; c < ch; c++) out[i * ch + c] = (short) Math.round(in[a * ch + c] * (1 - f) + in[b * ch + c] * f);
        }
        return new Pcm(out, ch, newRate);
    }
}
