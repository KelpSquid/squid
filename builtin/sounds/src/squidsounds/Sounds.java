package squidsounds;

import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Util;
import squid.api.Squid;
import squid.api.SquidMod;
import squid.audio.Audio;
import squid.audio.Pcm;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource packs can use .wav, .mp3 and .flac sounds and music, not just .ogg. Minecraft always asks for
 * "sounds/name.ogg"; when that file isn't there but name.wav, name.mp3 or name.flac is (or the .ogg is really one of
 * those inside), Squid decodes it with its own decoders and hands Minecraft the sound. Real .ogg files are left to
 * Minecraft, as always.
 */
public class Sounds implements SquidMod {
    private static final String[] OTHER_KINDS = {".wav", ".mp3", ".flac"};
    private final Set<Identifier> plainOgg = ConcurrentHashMap.newKeySet(); // checked already: Minecraft's own
    private final java.util.Map<Identifier, Pcm> decoded = new ConcurrentHashMap<>();

    @Override
    public void init(Squid squid) {
        // Short sounds: decoded whole
        squid.atStart("net.minecraft.client.sounds.SoundBufferLibrary", "getCompleteBuffer", call -> {
            Identifier path = (Identifier) call.args()[0];
            byte[] data = ours(path);
            if (data == null) return;
            call.cancel(CompletableFuture.supplyAsync(() -> {
                Pcm pcm = decode(path, data);
                return new SoundBuffer(bytes(pcm), format(pcm));
            }, Util.nonCriticalIoPool()));
        });
        // Music and long sounds: streamed (here, decoded whole and handed out a piece at a time)
        squid.atStart("net.minecraft.client.sounds.SoundBufferLibrary", "getStream", call -> {
            Identifier path = (Identifier) call.args()[0];
            boolean looping = (boolean) call.args()[1];
            byte[] data = ours(path);
            if (data == null) return;
            call.cancel(CompletableFuture.supplyAsync(() -> new Stream(decode(path, data), looping), Util.nonCriticalIoPool()));
        });
    }

    /** The file to decode ourselves for a sound Minecraft wants, or null to let Minecraft load its .ogg as usual. */
    private byte[] ours(Identifier path) {
        if (plainOgg.contains(path)) return null;
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

    private Pcm decode(Identifier path, byte[] data) {
        return decoded.computeIfAbsent(path, p -> Audio.decode(data));
    }

    private static AudioFormat format(Pcm pcm) {
        return new AudioFormat(pcm.rate(), 16, pcm.channels(), true, false);
    }

    private static ByteBuffer bytes(Pcm pcm) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(pcm.samples().length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm.samples()) buffer.putShort(s);
        return buffer.flip();
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
}
