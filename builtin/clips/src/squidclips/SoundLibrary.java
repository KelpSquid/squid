package squidclips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a sound Minecraft played into something {@link AudioMix} can mix: it picks the sound's file the way Minecraft
 * does (a sound event like "block.stone.break" has several files to choose from), reads it from the game's own
 * resources, and decodes the .ogg with the decoder Minecraft already ships (stb_vorbis, through LWJGL). Each file is
 * decoded once and kept.
 */
public final class SoundLibrary {
    /** A decoded sound file: its samples (mono, or left/right pairs), and how many channels and samples a second. */
    record Decoded(short[] samples, int channels, int rate) {
    }

    private static final Decoded NONE = new Decoded(new short[0], 1, AudioMix.RATE);
    private static final Map<Identifier, Decoded> decoded = new ConcurrentHashMap<>();
    private static final RandomSource random = RandomSource.create();

    private SoundLibrary() {
    }

    /**
     * A sound that played, ready to mix: when (seconds into the video), which sound event, how loud and high, where,
     * and how it fades. Null if Minecraft doesn't know the sound or its file can't be read.
     */
    public static AudioMix.Hit hit(double time, Object eventId, float volume, float pitch, double x, double y, double z, boolean relative,
                                   Object attenuation) {
        try {
            WeighedSoundEvents events = Minecraft.getInstance().getSoundManager().getSoundEvent((Identifier) eventId);
            if (events == null) return null;
            Sound sound = events.getSound(random);
            if (sound == null) return null;
            Decoded d = decode(sound.getPath());
            if (d.samples().length == 0) return null;
            float v = volume * sound.getVolume().sample(random);
            float p = pitch * sound.getPitch().sample(random);
            boolean linear = attenuation == SoundInstance.Attenuation.LINEAR;
            return new AudioMix.Hit(time, d.samples(), d.channels(), d.rate(), v, p, x, y, z, relative, linear, sound.getAttenuationDistance(v));
        } catch (RuntimeException e) {
            return null; // a sound that can't be mixed: the video just doesn't have it
        }
    }

    private static Decoded decode(Identifier path) {
        return decoded.computeIfAbsent(path, p -> {
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(p)) {
                byte[] bytes = in.readAllBytes();
                return decodeOgg(bytes);
            } catch (Exception e) {
                return NONE;
            }
        });
    }

    /** Decodes an .ogg file's bytes. Long ones (music) are cut at two minutes. */
    static Decoded decodeOgg(byte[] bytes) {
        ByteBuffer file = ByteBuffer.allocateDirect(bytes.length);
        file.put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channels = stack.mallocInt(1);
            IntBuffer rate = stack.mallocInt(1);
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(file, channels, rate);
            if (pcm == null) return NONE;
            try {
                int max = Math.min(pcm.remaining(), rate.get(0) * channels.get(0) * 120);
                short[] samples = new short[max];
                pcm.get(samples);
                return new Decoded(samples, channels.get(0), rate.get(0));
            } finally {
                MemoryUtil.memFree(pcm); // LWJGL's own free: the C library's free crashes here
            }
        }
    }
}
