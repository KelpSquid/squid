package squidclips;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a video as an .avi of JPEG pictures (Motion JPEG), from scratch: no outside programs needed, and it plays in
 * Windows Media Player, VLC and most video players. The file is made of RIFF chunks: a header saying the size and
 * speed, the pictures one after another, and an index saying where each one starts.
 */
public final class AviWriter {
    private AviWriter() {
    }

    /** Writes the JPEG frames (all width x height) as a video playing at fps frames a second. */
    public static void write(List<byte[]> frames, int width, int height, int fps, Path file) throws IOException {
        ByteArrayOutputStream movi = new ByteArrayOutputStream();
        ByteArrayOutputStream index = new ByteArrayOutputStream();
        int offset = 4; // index offsets count from the "movi" word
        int biggest = 0;
        for (byte[] frame : frames) {
            int padded = frame.length + (frame.length & 1); // chunks are padded to an even size
            movi.write(ascii("00dc"));
            movi.write(int32(frame.length));
            movi.write(frame);
            if ((frame.length & 1) == 1) movi.write(0);
            index.write(ascii("00dc"));
            index.write(int32(0x10)); // a keyframe: every JPEG picture stands on its own
            index.write(int32(offset));
            index.write(int32(frame.length));
            offset += 8 + padded;
            biggest = Math.max(biggest, frame.length);
        }

        ByteArrayOutputStream avih = new ByteArrayOutputStream();
        avih.write(int32(1_000_000 / fps));          // microseconds per frame
        avih.write(int32(biggest * fps));            // most bytes a second
        avih.write(int32(0));                        // padding granularity
        avih.write(int32(0x10));                     // has an index
        avih.write(int32(frames.size()));            // total frames
        avih.write(int32(0));                        // initial frames
        avih.write(int32(1));                        // streams
        avih.write(int32(biggest));                  // suggested buffer size
        avih.write(int32(width));
        avih.write(int32(height));
        avih.write(new byte[16]);                    // reserved

        ByteArrayOutputStream strh = new ByteArrayOutputStream();
        strh.write(ascii("vids"));
        strh.write(ascii("MJPG"));
        strh.write(int32(0));                        // flags
        strh.write(int16(0));                        // priority
        strh.write(int16(0));                        // language
        strh.write(int32(0));                        // initial frames
        strh.write(int32(1));                        // scale
        strh.write(int32(fps));                      // rate: rate / scale = frames a second
        strh.write(int32(0));                        // start
        strh.write(int32(frames.size()));            // length, in frames
        strh.write(int32(biggest));                  // suggested buffer size
        strh.write(int32(-1));                       // quality
        strh.write(int32(0));                        // sample size
        strh.write(int16(0));                        // frame rectangle: left, top, right, bottom
        strh.write(int16(0));
        strh.write(int16(width));
        strh.write(int16(height));

        ByteArrayOutputStream strf = new ByteArrayOutputStream(); // BITMAPINFOHEADER
        strf.write(int32(40));
        strf.write(int32(width));
        strf.write(int32(height));
        strf.write(int16(1));                        // planes
        strf.write(int16(24));                       // bits per pixel
        strf.write(ascii("MJPG"));                   // compression
        strf.write(int32(width * height * 3));       // image size
        strf.write(int32(0));
        strf.write(int32(0));
        strf.write(int32(0));
        strf.write(int32(0));

        byte[] strl = list("strl", chunk("strh", strh.toByteArray()), chunk("strf", strf.toByteArray()));
        byte[] hdrl = list("hdrl", chunk("avih", avih.toByteArray()), strl);
        byte[] moviList = list("movi", movi.toByteArray());
        byte[] idx1 = chunk("idx1", index.toByteArray());

        Files.createDirectories(file.toAbsolutePath().getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try (OutputStream out = Files.newOutputStream(part)) {
            out.write(ascii("RIFF"));
            out.write(int32(4 + hdrl.length + moviList.length + idx1.length));
            out.write(ascii("AVI "));
            out.write(hdrl);
            out.write(moviList);
            out.write(idx1);
        }
        Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] chunk(String id, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii(id));
        out.write(int32(data.length));
        out.write(data);
        if ((data.length & 1) == 1) out.write(0);
        return out.toByteArray();
    }

    private static byte[] list(String type, byte[]... parts) throws IOException {
        ByteArrayOutputStream inside = new ByteArrayOutputStream();
        inside.write(ascii(type));
        for (byte[] part : parts) inside.write(part);
        return chunk("LIST", inside.toByteArray());
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] int32(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] int16(int value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array();
    }
}
