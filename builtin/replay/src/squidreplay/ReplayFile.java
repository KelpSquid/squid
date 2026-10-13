package squidreplay;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import squid.Lang;
import squid.Main;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * Saved replays: a .sqreplay file in the instance's replays folder holds a recording (or the trimmed part of one)
 * and which world and dimension it happened in, since a replay plays back in the place it was recorded. Everything
 * Minecraft-specific (entity looks, items, blocks, sounds) is written the way Minecraft itself sends it over the
 * network, then the whole thing is gzipped.
 *
 * A replay file can come from anywhere (a friend can send one), so reading one trusts nothing in it: the file and
 * everything it says it holds have ceilings that are checked before anything is made, a broken or cut-off file is
 * explained instead of crashing the game, and file names never come from inside a file.
 */
final class ReplayFile {
    private static final int MAGIC = 0x53515250; // "SQRP"
    private static final int VERSION = 1;

    // The ceilings. Each is well above what Squid itself ever writes, so only a broken or hand-made file hits one.
    /** The biggest file opened, as it is on disk (gzipped). */
    static final long MOST_FILE_BYTES = 64L << 20;
    /** The biggest recording inside a file, once it's unzipped. */
    static final int MOST_BODY_BYTES = 128 << 20;
    /** The longest replay: the most minutes Squid keeps. */
    static final int MOST_FRAMES = Replay.MOST_MINUTES * 60 * 20;
    /** Things in one tick. More than this near you are left out when saving. */
    static final int MOST_THINGS_PER_FRAME = 32_768;
    static final int MOST_CHANGES = 1_000_000;
    static final int MOST_NOISES = 500_000;
    static final int MOST_SPARKS = Timeline.SPARKS_PER_TICK * MOST_FRAMES;
    /** Synced data values in one look (Minecraft numbers them 0 to 254). */
    static final int MOST_VALUES = 255;
    /** Equipment slots in one outfit (Minecraft has fewer than 10). */
    static final int MOST_SLOTS = 64;
    /** The longest name, world or date written in a file's header. */
    static final int MOST_HEADER_CHARS = 1024;
    /** How far out a thing, sound or particle can be: past the world border, but nowhere near the end of a number. */
    static final double WORLD_EDGE = 33_554_432;

    // The fewest bytes each kind of entry takes, so a count bigger than what's left in the file is caught before
    // making room for it
    private static final int FRAME_BYTES = 2;
    private static final int THING_BYTES = 76;
    private static final int CHANGE_BYTES = 11;
    private static final int NOISE_BYTES = 40;
    private static final int SPARK_BYTES = 50;

    /** A saved replay's details, for the list. */
    record Info(Path file, String world, String dimension, int ticks, String saved) {
    }

    static Path folder() {
        return Main.gameFolder().resolve("replays");
    }

    /** Which world you're in: a server's address, or a singleplayer world's name. */
    static String world(Minecraft minecraft) {
        if (minecraft.getCurrentServer() != null) return "server:" + minecraft.getCurrentServer().ip;
        if (minecraft.getSingleplayerServer() != null) return "world:" + minecraft.getSingleplayerServer().getWorldData().getLevelName();
        return "unknown";
    }

    static String dimension(Minecraft minecraft) {
        return minecraft.level.dimension().identifier().toString();
    }

    // ---- What goes wrong, explained (shown after "Couldn't open it:" or "Couldn't save it:") ----

    static IOException notAReplay() {
        return new IOException(Lang.t("it isn't a replay"));
    }

    static IOException newer() {
        return new IOException(Lang.t("it was saved by a newer Squid"));
    }

    static IOException cutOff() {
        return new IOException(Lang.t("it's cut off: part of the file is missing"));
    }

    static IOException damaged() {
        return new IOException(Lang.t("it's damaged or was changed by hand"));
    }

    static IOException tooBig() {
        return new IOException(Lang.t("it's too big to open safely"));
    }

    static IOException tooLong() {
        return new IOException(Lang.t("it's too long to save. Trim it with In and Out first."));
    }

    // ---- Saving ----

    /** Saves the ticks from first to last (inclusive) in the replays folder, and says where. */
    static Path save(Minecraft minecraft, Timeline.Recording recording, int first, int last) throws IOException {
        return saveIn(folder(), minecraft.level.registryAccess(), world(minecraft), dimension(minecraft), recording, first, last, LocalDateTime.now());
    }

    /**
     * Saves into a folder under a new name made from the time (a second save in the same second gets "-2"), so a
     * save never writes over another file. It's written to a .part file first and renamed when it's whole, so a
     * crash halfway leaves no broken replay in the list.
     */
    static Path saveIn(Path folder, RegistryAccess registries, String world, String dimension, Timeline.Recording recording,
                       int first, int last, LocalDateTime now) throws IOException {
        Files.createDirectories(folder);
        Path file = freeName(folder, now);
        Path part = folder.resolve(file.getFileName() + ".part");
        boolean done = false;
        try {
            try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                write(out, registries, world, dimension, recording, first, last, now);
            }
            if (Files.size(part) > MOST_FILE_BYTES) throw tooLong(); // it couldn't be opened again
            Files.move(part, file); // not over anything: fails if the name was taken meanwhile
            done = true;
            return file;
        } finally {
            if (!done) Files.deleteIfExists(part);
        }
    }

    /** "replay-2026-10-13_18-30-05.sqreplay", or with -2, -3... when that's taken. */
    static Path freeName(Path folder, LocalDateTime now) throws IOException {
        String stamp = "replay-" + DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(now);
        for (int n = 1; n <= 1000; n++) {
            Path file = folder.resolve(stamp + (n == 1 ? "" : "-" + n) + ".sqreplay");
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.exists(folder.resolve(file.getFileName() + ".part"), LinkOption.NOFOLLOW_LINKS)) {
                return file;
            }
        }
        throw new java.nio.file.FileAlreadyExistsException(folder.resolve(stamp + ".sqreplay").toString());
    }

    /** Writes the ticks from first to last (inclusive) as a replay file. */
    static void write(OutputStream to, RegistryAccess registries, String world, String dimension, Timeline.Recording recording,
                      int first, int last, LocalDateTime now) throws IOException {
        first = Math.max(0, first);
        last = Math.min(recording.length() - 1, last);
        if (last < first) throw new IOException(Lang.t("it's too short"));
        if (last - first + 1 > MOST_FRAMES) throw tooLong();
        long fromTick = recording.frames[first].tick();
        long toTick = recording.frames[last].tick();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            // Each look (synced data list) and outfit (equipment array) is written once and then referred to by number
            Map<Object, Integer> looks = new IdentityHashMap<>();
            Map<Object, Integer> outfits = new IdentityHashMap<>();
            buf.writeVarInt(last - first + 1);
            for (int i = first; i <= last; i++) {
                Timeline.Frame frame = recording.frames[i];
                buf.writeVarLong(frame.tick() - fromTick);
                int things = Math.min(frame.things().length, MOST_THINGS_PER_FRAME);
                buf.writeVarInt(things);
                for (int j = 0; j < things; j++) writeThing(buf, frame.things()[j], looks, outfits);
                if (buf.writerIndex() > MOST_BODY_BYTES) throw tooLong();
            }
            List<Timeline.Change> changes = new ArrayList<>();
            for (Timeline.Change c : recording.changes) {
                if (c.tick() >= fromTick && c.tick() <= toTick) changes.add(c);
            }
            if (changes.size() > MOST_CHANGES) throw tooLong();
            buf.writeVarInt(changes.size());
            for (Timeline.Change c : changes) {
                buf.writeVarLong(c.tick() - fromTick);
                buf.writeLong(((BlockPos) c.pos()).asLong());
                buf.writeVarInt(Block.getId((BlockState) c.before()));
                buf.writeVarInt(Block.getId((BlockState) c.after()));
            }
            List<Timeline.Noise> noises = recording.noisesBetween(fromTick - 1, toTick);
            if (noises.size() > MOST_NOISES) throw tooLong();
            buf.writeVarInt(noises.size());
            for (Timeline.Noise n : noises) {
                buf.writeVarLong(n.tick() - fromTick);
                buf.writeIdentifier((Identifier) n.id());
                buf.writeUtf(((SoundSource) n.source()).name());
                buf.writeFloat(n.volume());
                buf.writeFloat(n.pitch());
                buf.writeDouble(n.x());
                buf.writeDouble(n.y());
                buf.writeDouble(n.z());
                buf.writeUtf(((SoundInstance.Attenuation) n.attenuation()).name());
                buf.writeBoolean(n.relative());
            }

            List<Timeline.Spark> sparks = recording.sparksBetween(fromTick - 1, toTick);
            if (sparks.size() > MOST_SPARKS) throw tooLong();
            buf.writeVarInt(sparks.size());
            for (Timeline.Spark s : sparks) {
                buf.writeVarLong(s.tick() - fromTick);
                net.minecraft.core.particles.ParticleTypes.STREAM_CODEC.encode(buf, (net.minecraft.core.particles.ParticleOptions) s.options());
                for (double d : new double[] {s.x(), s.y(), s.z(), s.dx(), s.dy(), s.dz()}) buf.writeDouble(d);
            }
            if (buf.readableBytes() > MOST_BODY_BYTES) throw tooLong(); // it couldn't be opened again

            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(to, 1 << 16))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeUTF(clip(world));
                out.writeUTF(clip(dimension));
                out.writeInt(last - first + 1);
                out.writeUTF(now.withNano(0).toString());
                out.writeInt(buf.readableBytes());
                buf.readBytes(out, buf.readableBytes());
            }
        } finally {
            buf.release();
        }
    }

    private static String clip(String text) {
        return text.length() > MOST_HEADER_CHARS ? text.substring(0, MOST_HEADER_CHARS) : text;
    }

    private static void writeThing(RegistryFriendlyByteBuf buf, Timeline.Thing t, Map<Object, Integer> looks, Map<Object, Integer> outfits) {
        buf.writeVarInt(t.id);
        buf.writeIdentifier(BuiltInRegistries.ENTITY_TYPE.getKey((EntityType<?>) t.type));
        buf.writeUUID(t.uuid);
        buf.writeBoolean(t.profile != null);
        if (t.profile instanceof GameProfile profile) {
            buf.writeUUID(profile.id());
            buf.writeUtf(profile.name());
        }
        buf.writeDouble(t.x);
        buf.writeDouble(t.y);
        buf.writeDouble(t.z);
        for (float f : new float[] {t.yRot, t.xRot, t.headRot, t.bodyRot, t.walkPosition, t.walkSpeed, t.swing}) buf.writeFloat(f);
        buf.writeVarInt(t.hurtTime);
        buf.writeVarInt(t.deathTime);
        // A look: its number if written before, or a new one written out in full
        Integer look = looks.get(t.data);
        buf.writeVarInt(look == null ? -1 : look);
        if (look == null) {
            looks.put(t.data, looks.size());
            @SuppressWarnings("unchecked")
            List<SynchedEntityData.DataValue<?>> values = (List<SynchedEntityData.DataValue<?>>) t.data;
            for (SynchedEntityData.DataValue<?> value : values) value.write(buf);
            buf.writeByte(255);
        }
        Integer outfit = t.equipment == null ? Integer.valueOf(-2) : outfits.get(t.equipment);
        buf.writeVarInt(outfit == null ? -1 : outfit);
        if (outfit == null) {
            outfits.put(t.equipment, outfits.size());
            buf.writeVarInt(t.equipment.length);
            for (Object stack : t.equipment) ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, (ItemStack) stack);
        }
    }

    // ---- The list ----

    /** Just a file's details, for the list (without reading the whole recording), or null if it isn't a replay. */
    static Info info(Path file) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) return null;
            int version = in.readInt();
            if (version < 1 || version > VERSION) return null;
            String world = in.readUTF();
            String dimension = in.readUTF();
            int ticks = in.readInt();
            String saved = in.readUTF();
            if (ticks < 0 || saved.length() > 64) return null;
            return new Info(file, world, dimension, ticks, saved);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Whether a file is a replay right in the folder: not somewhere else, not a folder, and not a link to elsewhere. */
    static boolean inFolder(Path folder, Path file) {
        Path where = file.toAbsolutePath().normalize();
        return where.getParent() != null && where.getParent().equals(folder.toAbsolutePath().normalize())
                && where.getFileName().toString().endsWith(".sqreplay") && Files.isRegularFile(where, LinkOption.NOFOLLOW_LINKS);
    }

    /** The saved replays of the world and dimension you're in, newest first. */
    static List<Info> here(Minecraft minecraft) {
        List<Info> out = new ArrayList<>();
        if (!Files.isDirectory(folder()) || minecraft.level == null) return out;
        String world = clip(world(minecraft));
        String dimension = clip(dimension(minecraft));
        try (Stream<Path> files = Files.list(folder())) {
            for (Path file : files.filter(f -> inFolder(folder(), f)).toList()) {
                Info info = info(file);
                if (info != null && info.world().equals(world) && info.dimension().equals(dimension)) out.add(info);
            }
        } catch (IOException | RuntimeException e) {
            return out;
        }
        out.sort(Comparator.comparing(Info::saved).reversed());
        return out;
    }

    /** Deletes a saved replay from the list (only ever a replay right in the replays folder). */
    static void delete(Path file) throws IOException {
        if (!inFolder(folder(), file)) throw notAReplay();
        Files.deleteIfExists(file);
    }

    // ---- Opening ----

    /** Reads a saved replay back into a recording to play. */
    static Timeline.Recording load(Minecraft minecraft, Path file) throws IOException {
        if (!inFolder(folder(), file)) throw notAReplay();
        return read(file, minecraft.level.registryAccess());
    }

    /**
     * Reads a replay file. Whatever is in it, this either gives back a recording that's safe to play or throws an
     * IOException saying what's wrong, in words for the player.
     */
    static Timeline.Recording read(Path file, RegistryAccess registries) throws IOException {
        if (Files.size(file) > MOST_FILE_BYTES) throw tooBig();
        try (InputStream raw = Files.newInputStream(file)) {
            return decode(body(raw), registries);
        }
    }

    /** The recording's bytes, unzipped, after checking the header. */
    static byte[] body(InputStream raw) throws IOException {
        DataInputStream in;
        try {
            in = new DataInputStream(new GZIPInputStream(raw, 1 << 16));
        } catch (EOFException e) {
            throw cutOff();
        } catch (ZipException e) {
            throw notAReplay(); // not gzipped at all
        }
        try {
            if (in.readInt() != MAGIC) throw notAReplay();
            int version = in.readInt();
            if (version > VERSION) throw newer();
            if (version < 1) throw damaged();
            in.readUTF(); // world
            in.readUTF(); // dimension
            in.readInt(); // ticks
            in.readUTF(); // when it was saved
            int length = in.readInt();
            if (length < 0) throw damaged();
            if (length > MOST_BODY_BYTES) throw tooBig();
            // Read bit by bit rather than making room for the whole length first, so a file that only claims to be
            // big doesn't take the memory
            byte[] body = in.readNBytes(length);
            if (body.length < length) throw cutOff();
            // Reading on to the end checks the zip's own checksum, so a file with bytes changed or missing at the
            // end is caught too
            if (in.read() != -1) throw damaged(); // something more after the recording
            return body;
        } catch (EOFException e) {
            throw cutOff();
        } catch (ZipException | java.io.UTFDataFormatException e) {
            throw damaged();
        }
    }

    /** Turns a replay's bytes back into a recording, checking every count before making room for it. */
    static Timeline.Recording decode(byte[] body, RegistryAccess registries) throws IOException {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(body), registries);
        try {
            List<Object> looks = new ArrayList<>();
            List<Object[]> outfits = new ArrayList<>();
            Timeline.Frame[] frames = new Timeline.Frame[count(buf, MOST_FRAMES, FRAME_BYTES)];
            long lastTick = -1;
            for (int i = 0; i < frames.length; i++) {
                long tick = buf.readVarLong();
                if (tick <= lastTick) throw damaged(); // ticks only go forward
                lastTick = tick;
                Timeline.Thing[] things = new Timeline.Thing[count(buf, MOST_THINGS_PER_FRAME, THING_BYTES)];
                for (int j = 0; j < things.length; j++) things[j] = readThing(buf, looks, outfits);
                frames[i] = new Timeline.Frame(tick, things);
            }
            Timeline.Change[] changes = new Timeline.Change[count(buf, MOST_CHANGES, CHANGE_BYTES)];
            long changeTick = 0;
            for (int i = 0; i < changes.length; i++) {
                long tick = buf.readVarLong();
                if (tick < changeTick) throw damaged(); // in order, so the replay can find them
                changeTick = tick;
                changes[i] = new Timeline.Change(tick, BlockPos.of(buf.readLong()), Block.stateById(buf.readVarInt()), Block.stateById(buf.readVarInt()));
            }
            Timeline.Noise[] noises = new Timeline.Noise[count(buf, MOST_NOISES, NOISE_BYTES)];
            for (int i = 0; i < noises.length; i++) {
                long tick = buf.readVarLong();
                Identifier id = buf.readIdentifier();
                SoundSource source = SoundSource.valueOf(buf.readUtf(32));
                float volume = finite(buf.readFloat());
                float pitch = finite(buf.readFloat());
                double x = place(buf.readDouble());
                double y = place(buf.readDouble());
                double z = place(buf.readDouble());
                noises[i] = new Timeline.Noise(tick, id, source, volume, pitch, x, y, z, SoundInstance.Attenuation.valueOf(buf.readUtf(32)), buf.readBoolean());
            }
            Timeline.Spark[] sparks = new Timeline.Spark[count(buf, MOST_SPARKS, SPARK_BYTES)];
            long sparkTick = -1;
            int sparksThisTick = 0;
            for (int i = 0; i < sparks.length; i++) {
                long tick = buf.readVarLong();
                if (tick < sparkTick) throw damaged();
                sparksThisTick = tick == sparkTick ? sparksThisTick + 1 : 1;
                sparkTick = tick;
                if (sparksThisTick > Timeline.SPARKS_PER_TICK) throw tooBig(); // more at once than Squid ever keeps
                sparks[i] = new Timeline.Spark(tick, net.minecraft.core.particles.ParticleTypes.STREAM_CODEC.decode(buf), place(buf.readDouble()),
                        place(buf.readDouble()), place(buf.readDouble()), finite(buf.readDouble()), finite(buf.readDouble()), finite(buf.readDouble()));
            }
            if (buf.isReadable()) throw damaged(); // something more after the end
            return new Timeline.Recording(frames, changes, noises, sparks);
        } catch (RuntimeException | StackOverflowError e) {
            // Minecraft's own reading found something wrong (a made-up item, a string that runs past the end...)
            throw damaged();
        } finally {
            buf.release();
        }
    }

    /** How many of something come next: never below 0, over the ceiling, or more than the bytes left could hold. */
    private static int count(RegistryFriendlyByteBuf buf, int most, int bytesEach) throws IOException {
        int count = buf.readVarInt();
        if (count < 0) throw damaged();
        if (count > most) throw tooBig();
        if ((long) count * bytesEach > buf.readableBytes()) throw damaged();
        return count;
    }

    private static float finite(float f) throws IOException {
        if (!Float.isFinite(f)) throw damaged();
        return f;
    }

    private static double finite(double d) throws IOException {
        if (!Double.isFinite(d)) throw damaged();
        return d;
    }

    /** A coordinate: a real number, and in the world (or near it). */
    private static double place(double d) throws IOException {
        if (!Double.isFinite(d) || Math.abs(d) > WORLD_EDGE) throw damaged();
        return d;
    }

    private static Timeline.Thing readThing(RegistryFriendlyByteBuf buf, List<Object> looks, List<Object[]> outfits) throws IOException {
        int id = buf.readVarInt();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(buf.readIdentifier());
        java.util.UUID uuid = buf.readUUID();
        GameProfile profile = buf.readBoolean() ? new GameProfile(buf.readUUID(), buf.readUtf(64)) : null;
        double x = place(buf.readDouble());
        double y = place(buf.readDouble());
        double z = place(buf.readDouble());
        float[] f = new float[7];
        for (int i = 0; i < f.length; i++) f[i] = finite(buf.readFloat());
        int hurt = buf.readVarInt();
        int death = buf.readVarInt();
        if (hurt < 0 || hurt > 10_000 || death < 0 || death > 10_000) throw damaged();
        int look = buf.readVarInt();
        Object data;
        if (look >= 0) {
            if (look >= looks.size()) throw damaged(); // a look that wasn't written before
            data = looks.get(look);
        } else if (look == -1) {
            List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
            for (int key = buf.readUnsignedByte(); key != 255; key = buf.readUnsignedByte()) {
                if (values.size() >= MOST_VALUES) throw damaged();
                values.add(SynchedEntityData.DataValue.read(buf, key));
            }
            data = values;
            looks.add(values);
        } else {
            throw damaged();
        }
        int outfit = buf.readVarInt();
        Object[] equipment;
        if (outfit == -2) {
            equipment = null;
        } else if (outfit >= 0) {
            if (outfit >= outfits.size()) throw damaged();
            equipment = outfits.get(outfit);
        } else if (outfit == -1) {
            equipment = new Object[count(buf, MOST_SLOTS, 1)];
            for (int i = 0; i < equipment.length; i++) equipment[i] = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            outfits.add(equipment);
        } else {
            throw damaged();
        }
        return new Timeline.Thing(id, type, uuid, profile, x, y, z, f[0], f[1], f[2], f[3], f[4], f[5], f[6], hurt, death, data, equipment);
    }
}
