package squidclips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a sound Minecraft played into something {@link AudioMix} can mix: it picks the sound's file the way Minecraft
 * does (a sound event like "block.stone.break" has several files to choose from), reads it from the game's own
 * resources, and decodes it with Squid's own decoders (squid.audio). Each file is decoded once and kept.
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

    /** Decodes a sound file's bytes (Ogg Vorbis, or WAV, MP3 or FLAC from a resource pack) with Squid's own decoders. */
    static Decoded decodeOgg(byte[] bytes) {
        squid.audio.Pcm pcm = squid.audio.Audio.decode(bytes);
        return new Decoded(pcm.samples(), pcm.channels(), pcm.rate());
    }
}
