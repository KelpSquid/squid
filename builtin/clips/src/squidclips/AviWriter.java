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
 * speed, the pictures one after another (each followed by its slice of sound, if there is any), and an index saying
 * where each one starts. Sound is plain 16-bit stereo PCM, like a .wav.
 */
public final class AviWriter {
    private AviWriter() {
    }

    /** Writes the JPEG frames (all width x height) as a silent video playing at fps frames a second. */
    public static void write(List<byte[]> frames, int width, int height, int fps, Path file) throws IOException {
        write(frames, width, height, fps, null, 0, file);
    }

    /**
     * Writes the JPEG frames as a video with sound: audio is left/right pairs of 16-bit samples at sampleRate a second
     * (or null for none). Each picture is followed by the sound that plays while it's on screen.
     */
    public static void write(List<byte[]> frames, int width, int height, int fps, short[] audio, int sampleRate, Path file) throws IOException {
        boolean sound = audio != null && audio.length > 0;
        ByteArrayOutputStream movi = new ByteArrayOutputStream();
        ByteArrayOutputStream index = new ByteArrayOutputStream();
        int offset = 4; // index offsets count from the "movi" word
        int biggest = 0;
        int biggestSound = 0;
        long samplesWritten = 0;
        long totalSamples = sound ? audio.length / 2 : 0;
        for (int f = 0; f < frames.size(); f++) {
            byte[] frame = frames.get(f);
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
            if (sound) {
                // This picture's share of the sound (the last picture takes whatever is left)
                long until = f == frames.size() - 1 ? totalSamples : Math.min(totalSamples, (long) (f + 1) * sampleRate / fps);
                int count = (int) Math.max(0, until - samplesWritten);
                byte[] pcm = new byte[count * 4];
                ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
                for (long i = samplesWritten; i < samplesWritten + count; i++) {
                    buffer.putShort(audio[(int) (i * 2)]);
                    buffer.putShort(audio[(int) (i * 2 + 1)]);
                }
                samplesWritten += count;
                movi.write(ascii("01wb"));
                movi.write(int32(pcm.length));
                movi.write(pcm);
                index.write(ascii("01wb"));
                index.write(int32(0x10));
                index.write(int32(offset));
                index.write(int32(pcm.length));
                offset += 8 + pcm.length; // always even: 4 bytes a sample pair
                biggestSound = Math.max(biggestSound, pcm.length);
            }
        }

        ByteArrayOutputStream avih = new ByteArrayOutputStream();
        avih.write(int32(1_000_000 / fps));          // microseconds per frame
        avih.write(int32(biggest * fps + (sound ? sampleRate * 4 : 0))); // most bytes a second
        avih.write(int32(0));                        // padding granularity
        avih.write(int32(0x10));                     // has an index
        avih.write(int32(frames.size()));            // total frames
        avih.write(int32(0));                        // initial frames
        avih.write(int32(sound ? 2 : 1));            // streams: pictures, and sound if there is any
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
        byte[] hdrl;
        if (sound) {
            ByteArrayOutputStream ash = new ByteArrayOutputStream();
            ash.write(ascii("auds"));
            ash.write(int32(0));                     // no codec: plain PCM
            ash.write(int32(0));                     // flags
            ash.write(int16(0));                     // priority
            ash.write(int16(0));                     // language
            ash.write(int32(0));                     // initial frames
            ash.write(int32(4));                     // scale: one block is a left/right pair of 16-bit samples
            ash.write(int32(sampleRate * 4));        // rate: bytes a second
            ash.write(int32(0));                     // start
            ash.write(int32((int) totalSamples));    // length, in blocks
            ash.write(int32(biggestSound));          // suggested buffer size
            ash.write(int32(-1));                    // quality
            ash.write(int32(4));                     // sample size
            ash.write(new byte[8]);                  // frame rectangle (unused for sound)
            ByteArrayOutputStream wave = new ByteArrayOutputStream(); // WAVEFORMATEX
            wave.write(int16(1));                    // PCM
            wave.write(int16(2));                    // stereo
            wave.write(int32(sampleRate));
            wave.write(int32(sampleRate * 4));       // bytes a second
            wave.write(int16(4));                    // bytes per left/right pair
            wave.write(int16(16));                   // bits per sample
            wave.write(int16(0));                    // no extra format bytes
            byte[] audioStrl = list("strl", chunk("strh", ash.toByteArray()), chunk("strf", wave.toByteArray()));
            hdrl = list("hdrl", chunk("avih", avih.toByteArray()), strl, audioStrl);
        } else {
            hdrl = list("hdrl", chunk("avih", avih.toByteArray()), strl);
        }
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
