package squidpaint;

import squid.audio.Pcm;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays a sound once, straight to the speakers, so the Sound Swapper can let you hear a sound before and after you
 * swap it. Only one plays at a time: a new one stops the last.
 */
final class Preview {
    private static volatile Thread playing;

    private Preview() {
    }

    /** Plays the sound at this volume (0 to 1, Minecraft's own volume setting). */
    static void play(Pcm pcm, float volume) {
        Thread t = new Thread(() -> run(pcm, volume), "Squid Sound Swapper preview");
        t.setDaemon(true);
        playing = t;
        t.start();
    }

    /** Stops the sound that's playing, if there is one. */
    static void stop() {
        playing = null;
    }

    private static void run(Pcm pcm, float volume) {
        AudioFormat format = new AudioFormat(pcm.rate(), 16, pcm.channels(), true, false);
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, pcm.rate() * pcm.channels() * 2 / 10);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            return; // no speakers: nothing to hear
        }
        try {
            line.start();
            short[] samples = pcm.samples();
            byte[] out = new byte[4096];
            int at = 0;
            while (at < samples.length && playing == Thread.currentThread()) {
                int n = Math.min(out.length / 2, samples.length - at);
                for (int i = 0; i < n; i++) {
                    int v = Math.round(samples[at + i] * volume);
                    out[2 * i] = (byte) v;
                    out[2 * i + 1] = (byte) (v >> 8);
                }
                line.write(out, 0, n * 2);
                at += n;
            }
            if (playing == Thread.currentThread()) line.drain();
        } finally {
            line.stop();
            line.close();
        }
    }
}
