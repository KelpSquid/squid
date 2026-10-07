package squid.audio;

/**
 * Decoded sound: 16-bit samples, channel after channel for each moment (left, right, left, right... for stereo),
 * how many channels, and how many moments a second.
 */
public record Pcm(short[] samples, int channels, int rate) {
    /** How long it lasts, in seconds. */
    public double seconds() {
        return samples.length / (double) Math.max(1, channels) / Math.max(1, rate);
    }
}
