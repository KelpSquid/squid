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
    /** One picture and when it was taken. */
    record Frame(byte[] jpeg, long time) {
    }

    private final Deque<Frame> frames = new ArrayDeque<>();

    /** Adds a picture, and lets go of the ones older than seconds before it. */
    public synchronized void add(byte[] jpeg, long time, int seconds) {
        frames.addLast(new Frame(jpeg, time));
        while (!frames.isEmpty() && frames.peekFirst().time() < time - seconds * 1000L) frames.removeFirst();
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
