package squidsounds;

import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Util;
import net.minecraft.util.valueproviders.SampledFloat;
import squid.api.Squid;
import squid.api.SquidMod;
import squid.audio.Audio;
import squid.audio.Pcm;
import squid.audio.Sqda;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resource packs can use .wav, .mp3, .flac and Squid's own .sqda sounds and music, not just .ogg. Minecraft always
 * asks for "sounds/name.ogg"; when that file isn't there but name.wav, .mp3, .flac or .sqda is (or the .ogg is really
 * one of those inside), Squid decodes it with its own decoders and hands Minecraft the sound. Real .ogg files are
 * left to Minecraft, as always.
 *
 * .sqda files bring more: a variant picked at random each play, loop points, their own volume, pitch, distance,
 * streaming and subtitle, and while they play, their cues and light cues go to mods and entity triggers fire
 * (see {@link Playing}).
 */
public class Sounds implements SquidMod {
    private static final String[] OTHER_KINDS = {".sqda", ".wav", ".mp3", ".flac"};
    /** A trigger plays one of a file's own variants as "name.squidvariant3": the same file, variant 3. */
    static final Pattern VARIANT = Pattern.compile("^(.*)\\.squidvariant(\\d+)(\\.ogg)?$");

    private final Set<Identifier> plainOgg = ConcurrentHashMap.newKeySet(); // checked already: Minecraft's own
    /** Each sound file's bytes, read from the resource packs once instead of on every play. Empty: Minecraft's own. */
    private final Map<Identifier, Optional<byte[]>> files = new ConcurrentHashMap<>();
    private final Map<Identifier, Sqda> sqdas = new ConcurrentHashMap<>();
    private final Map<Identifier, Optional<Sqda>> headers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<SoundBuffer>> buffers = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private static Field soundList;

    @Override
    public void init(Squid squid) {
        Playing playing = new Playing(squid.settings());
        // Short sounds: decoded whole, once per variant, and kept like Minecraft keeps its own
        squid.atStart("net.minecraft.client.sounds.SoundBufferLibrary", "getCompleteBuffer", call -> {
            Identifier asked = (Identifier) call.args()[0];
            Identifier path = basePath(asked);
            // A sound already made into a buffer (not a .sqda, which picks a variant each time) isn't read again
            CompletableFuture<SoundBuffer> made = sqdas.containsKey(path) ? null : buffers.get(path + "#0");
            if (made != null) {
                call.cancel(made);
                return;
            }
            byte[] data = ours(path);
            if (data == null) return;
            int variant = Sqda.is(data) ? variantFor(asked, path, data) : 0;
            call.cancel(buffers.computeIfAbsent(path + "#" + variant, key -> CompletableFuture.supplyAsync(() -> {
                Pcm pcm = Sqda.is(data) ? sqda(path, data).decode(variant) : Audio.decode(data); // kept as a buffer, below
                return new SoundBuffer(bytes(pcm), format(pcm));
            }, Util.nonCriticalIoPool())));
        });
        // Music and long sounds: streamed. A .sqda is decoded as it plays; the others are decoded whole first.
        squid.atStart("net.minecraft.client.sounds.SoundBufferLibrary", "getStream", call -> {
            Identifier asked = (Identifier) call.args()[0];
            Identifier path = basePath(asked);
            boolean looping = (boolean) call.args()[1];
            byte[] data = ours(path);
            if (data == null) return;
            if (Sqda.is(data)) {
                int variant = variantFor(asked, path, data);
                call.cancel(CompletableFuture.supplyAsync(() -> {
                    Sqda file = sqda(path, data);
                    SqdaStream stream = new SqdaStream(file.play(variant, looping), file, variant, soundName(path));
                    playing.started(stream);
                    return (AudioStream) stream;
                }, Util.nonCriticalIoPool()));
                return;
            }
            // Not kept after it plays: a pack replacing all the music would otherwise hold every song in memory
            call.cancel(CompletableFuture.supplyAsync(() -> (AudioStream) new Stream(Audio.decode(data), looping), Util.nonCriticalIoPool()));
        });
        // When resource packs change, everything is looked up again (and our sound buffers let go)
        squid.atStart("net.minecraft.client.sounds.SoundBufferLibrary", "clear", call -> {
            for (CompletableFuture<SoundBuffer> f : buffers.values()) f.thenAccept(SoundBuffer::discardAlBuffer);
            buffers.clear();
            plainOgg.clear();
            files.clear();
            sqdas.clear();
            headers.clear();
        });
        // A .sqda's own sound settings, on top of sounds.json's
        squid.atEnd("net.minecraft.client.resources.sounds.Sound", "getVolume", call -> {
            Sqda.Settings s = settingsOf((Sound) call.self());
            if (s != null && s.volume() != 1) {
                SampledFloat original = (SampledFloat) call.returnValue();
                float volume = s.volume();
                call.setReturnValue((SampledFloat) r -> original.sample(r) * volume);
            }
        });
        squid.atEnd("net.minecraft.client.resources.sounds.Sound", "getPitch", call -> {
            Sqda.Settings s = settingsOf((Sound) call.self());
            if (s != null && s.pitch() != 1) {
                SampledFloat original = (SampledFloat) call.returnValue();
                float pitch = s.pitch();
                call.setReturnValue((SampledFloat) r -> original.sample(r) * pitch);
            }
        });
        squid.atEnd("net.minecraft.client.resources.sounds.Sound", "getAttenuationDistance", "()I", call -> {
            Sqda.Settings s = settingsOf((Sound) call.self());
            if (s != null && s.distance() != Sqda.Settings.DEFAULT.distance()) call.setReturnValue(s.distance());
        });
        squid.atEnd("net.minecraft.client.resources.sounds.Sound", "shouldStream", call -> {
            Sqda.Settings s = settingsOf((Sound) call.self());
            if (s != null && s.stream() != 0) call.setReturnValue(s.stream() == 1);
        });
        // A subtitle from the .sqda, when sounds.json doesn't give one
        squid.atEnd("net.minecraft.client.sounds.WeighedSoundEvents", "getSubtitle", call -> {
            if (call.returnValue() != null) return;
            Sound first = firstSound((WeighedSoundEvents) call.self());
            Sqda.Settings s = first == null ? null : settingsOf(first);
            if (s != null && !s.subtitle().isEmpty()) call.setReturnValue(Component.translatableWithFallback(s.subtitle(), s.subtitle()));
        });
        squid.onTick(playing::tick);
        squid.onHud(playing::hud);
    }

    /** "sounds/x.squidvariant3.ogg" (a trigger asking for variant 3) is the file "sounds/x.ogg". */
    static Identifier basePath(Identifier path) {
        Matcher m = VARIANT.matcher(path.getPath());
        return m.matches() ? path.withPath(m.group(1) + ".ogg") : path;
    }

    private int variantFor(Identifier asked, Identifier path, byte[] data) {
        Matcher m = VARIANT.matcher(asked.getPath());
        Sqda file = sqda(path, data);
        if (m.matches()) return Math.min(file.variants.size() - 1, Integer.parseInt(m.group(2)));
        synchronized (random) {
            return file.pickVariant(random);
        }
    }

    /** "minecraft:sounds/music/x.ogg" is the sound "minecraft:music/x". */
    static String soundName(Identifier path) {
        String p = path.getPath();
        if (p.startsWith("sounds/")) p = p.substring(7);
        if (p.endsWith(".ogg")) p = p.substring(0, p.length() - 4);
        return path.getNamespace() + ":" + p;
    }

    /** The file to decode ourselves for a sound Minecraft wants, or null to let Minecraft load its .ogg as usual. */
    private byte[] ours(Identifier path) {
        if (plainOgg.contains(path)) return null;
        Optional<byte[]> known = files.get(path);
        if (known != null) return known.orElse(null);
        byte[] found = find(path);
        // Short sounds are kept (they play again and again); songs are read again when they play, not held in memory
        if (found == null || found.length < 1 << 20) files.put(path, Optional.ofNullable(found));
        return found;
    }

    private byte[] find(Identifier path) {
        var resources = Minecraft.getInstance().getResourceManager();
        try {
            Optional<Resource> ogg = resources.getResource(path);
            if (ogg.isPresent()) {
                byte[] start;
                try (InputStream in = ogg.get().open()) {
                    start = in.readNBytes(12);
                }
                if (start.length >= 4 && start[0] == 'O' && start[1] == 'g' && start[2] == 'g' && start[3] == 'S') {
                    plainOgg.add(path);
                    return null;
                }
                try (InputStream in = ogg.get().open()) { // named .ogg, but really something else
                    byte[] all = in.readAllBytes();
                    return Audio.canDecode(all) ? all : null;
                }
            }
            String base = path.getPath().endsWith(".ogg") ? path.getPath().substring(0, path.getPath().length() - 4) : path.getPath();
            for (String kind : OTHER_KINDS) {
                Optional<Resource> other = resources.getResource(path.withPath(base + kind));
                if (other.isPresent()) {
                    try (InputStream in = other.get().open()) {
                        return in.readAllBytes();
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[Squid Sounds] Couldn't read " + path + ": " + e.getMessage());
        }
        plainOgg.add(path);
        return null;
    }

    /** Just the settings part of a .sqda (quick: it's at the front), or empty if the sound isn't one. */
    private Optional<Sqda> header(Identifier path) {
        return headers.computeIfAbsent(path, p -> {
            if (plainOgg.contains(p)) return Optional.empty();
            var resources = Minecraft.getInstance().getResourceManager();
            String base = p.getPath().endsWith(".ogg") ? p.getPath().substring(0, p.getPath().length() - 4) : p.getPath();
            for (Identifier candidate : List.of(p, p.withPath(base + ".sqda"))) {
                Optional<Resource> found = resources.getResource(candidate);
                if (found.isEmpty()) continue;
                try (InputStream in = found.get().open()) {
                    byte[] start = in.readNBytes(5);
                    if (!Sqda.is(start)) return Optional.empty(); // a real .ogg (or another kind): no .sqda settings
                } catch (IOException e) {
                    return Optional.empty();
                }
                try (InputStream in = found.get().open()) {
                    return Optional.of(Sqda.read(in, false));
                } catch (IOException | RuntimeException e) {
                    System.out.println("[Squid Sounds] Couldn't read " + candidate + ": " + e.getMessage());
                    return Optional.empty();
                }
            }
            return Optional.empty();
        });
    }

    private Sqda.Settings settingsOf(Sound sound) {
        if (sound.getType() != Sound.Type.FILE) return null;
        return header(basePath(sound.getPath())).map(s -> s.settings).orElse(null);
    }

    private static Sound firstSound(WeighedSoundEvents events) {
        try {
            if (soundList == null) {
                Field f = WeighedSoundEvents.class.getDeclaredField("list");
                f.setAccessible(true);
                soundList = f;
            }
            List<?> list = (List<?>) soundList.get(events);
            return !list.isEmpty() && list.getFirst() instanceof Sound s ? s : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private Sqda sqda(Identifier path, byte[] data) {
        return sqdas.computeIfAbsent(path, p -> Sqda.read(data));
    }

    static AudioFormat format(Pcm pcm) {
        return new AudioFormat(pcm.rate(), 16, pcm.channels(), true, false);
    }

    static ByteBuffer bytes(short[] samples, int count) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) buffer.putShort(samples[i]);
        return buffer.flip();
    }

    private static ByteBuffer bytes(Pcm pcm) {
        return bytes(pcm.samples(), pcm.samples().length);
    }

    /** A sound handed to Minecraft a piece at a time, starting over at the end if it loops. */
    static final class Stream implements FiniteAudioStream {
        private final Pcm pcm;
        private final boolean looping;
        private int at; // in samples

        Stream(Pcm pcm, boolean looping) {
            this.pcm = pcm;
            this.looping = looping;
        }

        @Override
        public AudioFormat getFormat() {
            return format(pcm);
        }

        @Override
        public ByteBuffer read(int size) {
            short[] s = pcm.samples();
            int wanted = Math.max(pcm.channels(), size / 2 / pcm.channels() * pcm.channels());
            if (at >= s.length) {
                if (!looping || s.length == 0) return null;
                at = 0;
            }
            int count = Math.min(wanted, s.length - at);
            ByteBuffer buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) buffer.putShort(s[at + i]);
            at += count;
            return buffer.flip();
        }

        @Override
        public ByteBuffer readAll() {
            return bytes(pcm);
        }

        @Override
        public void close() {
        }
    }

    /** A .sqda handed to Minecraft as it's decoded, looping at its loop points. */
    static final class SqdaStream implements AudioStream {
        final Sqda.Player player;
        final Sqda file;
        final int variant;
        final String sound;
        volatile boolean closed;
        volatile long firstRead;

        SqdaStream(Sqda.Player player, Sqda file, int variant, String sound) {
            this.player = player;
            this.file = file;
            this.variant = variant;
            this.sound = sound;
        }

        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(player.rate(), 16, player.channels(), true, false);
        }

        @Override
        public ByteBuffer read(int size) {
            if (firstRead == 0) firstRead = System.nanoTime();
            short[] s;
            try {
                s = player.read(Math.max(1, size / 2 / player.channels()));
            } catch (RuntimeException damaged) {
                // A damaged file ends the sound quietly, instead of an error on Minecraft's sound thread every tick
                if (!closed) System.out.println("[Squid] Stopped " + sound + ": the .sqda file is damaged (" + damaged + ")");
                closed = true;
                return null;
            }
            if (s == null) return null;
            return bytes(s, s.length);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
