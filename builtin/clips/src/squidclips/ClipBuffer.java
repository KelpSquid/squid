package squidclips;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The last few seconds of the game, kept as small JPEG pictures (so 30 seconds take tens of megabytes, not gigabytes).
 * Old pictures drop off the back as new ones come in. Nothing here needs Minecraft, so it can be tested on its own.
 */
public final class ClipBuffer {
    /** One picture, when it was taken, and where the camera was (x, y, z and yaw, or null). */
    public record Frame(byte[] jpeg, long time, double[] camera) {
    }

    /** A sound that played, when (in milliseconds), and the Minecraft details it needs to be mixed in later. */
    public record Noise(long time, Object id, float volume, float pitch, double x, double y, double z, boolean relative, Object attenuation) {
    }

    /** Everything kept at one moment, to save as a video. */
    public record Snapshot(List<Frame> frames, List<Noise> noises) {
    }

    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Deque<Noise> noises = new ArrayDeque<>();

    /** Adds a picture, and lets go of the ones older than seconds before it. */
    public synchronized void add(byte[] jpeg, long time, int seconds) {
        add(jpeg, time, seconds, null);
    }

    /** Adds a picture with where the camera was, and lets go of the ones (and sounds) older than seconds before it. */
    public synchronized void add(byte[] jpeg, long time, int seconds, double[] camera) {
        frames.addLast(new Frame(jpeg, time, camera));
        while (!frames.isEmpty() && frames.peekFirst().time() < time - seconds * 1000L) frames.removeFirst();
        long oldest = frames.peekFirst().time();
        while (!noises.isEmpty() && noises.peekFirst().time() < oldest) noises.removeFirst();
    }

    public synchronized void addSound(Noise noise) {
        noises.addLast(noise);
    }

    /** A copy of the pictures and sounds kept right now, oldest first. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(new ArrayList<>(frames), new ArrayList<>(noises));
    }

    /**
     * The sound track for a snapshot shown as a video at fps: each sound placed at the right moment of the video, heard
     * from where the camera was. hit turns one recorded sound into something to mix (or null to leave it out).
     */
    public static short[] soundTrack(Snapshot snapshot, int fps, java.util.function.BiFunction<Noise, Double, AudioMix.Hit> hit) {
        List<Frame> kept = snapshot.frames();
        if (kept.size() < 2) return new short[0];
        long first = kept.get(0).time();
        long last = kept.get(kept.size() - 1).time();
        double videoSeconds = kept.size() / (double) fps;
        double stretch = videoSeconds / Math.max(0.001, (last - first) / 1000.0); // the video's seconds per real second
        List<AudioMix.Hit> hits = new ArrayList<>();
        for (Noise noise : snapshot.noises()) {
            if (noise.time() < first || noise.time() > last) continue;
            AudioMix.Hit h = hit.apply(noise, (noise.time() - first) / 1000.0 * stretch);
            if (h != null) hits.add(h);
        }
        return AudioMix.mix(hits, seconds -> {
            int i = (int) Math.max(0, Math.min(kept.size() - 1, Math.round(seconds * fps)));
            double[] camera = kept.get(i).camera();
            return camera != null ? camera : new double[] {0, 0, 0, 0};
        }, videoSeconds);
    }

    /** A copy of the pictures kept right now, oldest first. */
    public synchronized List<byte[]> pictures() {
        List<byte[]> copy = new ArrayList<>(frames.size());
        for (Frame frame : frames) copy.add(frame.jpeg());
        return copy;
    }

    /** How many frames a second the kept pictures were taken at (at least 1). */
    public synchronized int fps() {
        if (frames.size() < 2) return 20;
        double seconds = (frames.peekLast().time() - frames.peekFirst().time()) / 1000.0;
        return (int) Math.max(1, Math.min(60, Math.round((frames.size() - 1) / Math.max(seconds, 0.001))));
    }

    public synchronized void clear() {
        frames.clear();
        noises.clear();
    }

    /** Squeezes a picture to exactly width x height (the video's size), smoothly. */
    public static BufferedImage fit(BufferedImage image, int width, int height) {
        if (image.getWidth() == width && image.getHeight() == height && image.getType() == BufferedImage.TYPE_INT_RGB) return image;
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    /** A picture as JPEG bytes, at quality 0 to 1. */
    public static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
