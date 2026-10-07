package squidreplay;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import squid.Main;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Saved replays: a .sqreplay file in the instance's replays folder holds a recording (or the trimmed part of one)
 * and which world and dimension it happened in, since a replay plays back in the place it was recorded. Everything
 * Minecraft-specific (entity looks, items, blocks, sounds) is written the way Minecraft itself sends it over the
 * network, then the whole thing is gzipped.
 */
final class ReplayFile {
    private static final int MAGIC = 0x53515250; // "SQRP"
    private static final int VERSION = 1;

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

    /** Saves the ticks from first to last (inclusive), and says where. */
    static Path save(Minecraft minecraft, Timeline.Recording recording, int first, int last) throws IOException {
        first = Math.max(0, first);
        last = Math.min(recording.length() - 1, last);
        long fromTick = recording.frames[first].tick();
        long toTick = recording.frames[last].tick();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), minecraft.level.registryAccess());
        // Each look (synced data list) and outfit (equipment array) is written once and then referred to by number
        Map<Object, Integer> looks = new IdentityHashMap<>();
        Map<Object, Integer> outfits = new IdentityHashMap<>();
        buf.writeVarInt(last - first + 1);
        for (int i = first; i <= last; i++) {
            Timeline.Frame frame = recording.frames[i];
            buf.writeVarLong(frame.tick() - fromTick);
            buf.writeVarInt(frame.things().length);
            for (Timeline.Thing t : frame.things()) writeThing(buf, t, looks, outfits);
        }
        List<Timeline.Change> changes = new ArrayList<>();
        for (Timeline.Change c : recording.changes) {
            if (c.tick() >= fromTick && c.tick() <= toTick) changes.add(c);
        }
        buf.writeVarInt(changes.size());
        for (Timeline.Change c : changes) {
            buf.writeVarLong(c.tick() - fromTick);
            buf.writeLong(((BlockPos) c.pos()).asLong());
            buf.writeVarInt(Block.getId((BlockState) c.before()));
            buf.writeVarInt(Block.getId((BlockState) c.after()));
        }
        List<Timeline.Noise> noises = recording.noisesBetween(fromTick - 1, toTick);
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

        Files.createDirectories(folder());
        String name = "replay-" + DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now()) + ".sqreplay";
        Path file = folder().resolve(name);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(world(minecraft));
            out.writeUTF(dimension(minecraft));
            out.writeInt(last - first + 1);
            out.writeUTF(LocalDateTime.now().withNano(0).toString());
            byte[] body = new byte[buf.readableBytes()];
            buf.readBytes(body);
            out.writeInt(body.length);
            out.write(body);
        } finally {
            buf.release();
        }
        return file;
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

    /** Just a file's details, for the list (without reading the whole recording). */
    static Info info(Path file) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() > VERSION) return null;
            return new Info(file, in.readUTF(), in.readUTF(), in.readInt(), in.readUTF());
        } catch (IOException e) {
            return null;
        }
    }

    /** The saved replays of the world and dimension you're in, newest first. */
    static List<Info> here(Minecraft minecraft) {
        List<Info> out = new ArrayList<>();
        if (!Files.isDirectory(folder()) || minecraft.level == null) return out;
        String world = world(minecraft);
        String dimension = dimension(minecraft);
        try (Stream<Path> files = Files.list(folder())) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sqreplay")).toList()) {
                Info info = info(file);
                if (info != null && info.world().equals(world) && info.dimension().equals(dimension)) out.add(info);
            }
        } catch (IOException e) {
            return out;
        }
        out.sort(Comparator.comparing(Info::saved).reversed());
        return out;
    }

    /** Reads a saved replay back into a recording to play. */
    static Timeline.Recording load(Minecraft minecraft, Path file) throws IOException {
        byte[] body;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("not a replay");
            if (in.readInt() > VERSION) throw new IOException("made by a newer Squid");
            in.readUTF();
            in.readUTF();
            in.readInt();
            in.readUTF();
            body = new byte[in.readInt()];
            in.readFully(body);
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(body), minecraft.level.registryAccess());
        try {
            List<Object> looks = new ArrayList<>();
            List<Object[]> outfits = new ArrayList<>();
            Timeline.Frame[] frames = new Timeline.Frame[buf.readVarInt()];
            for (int i = 0; i < frames.length; i++) {
                long tick = buf.readVarLong();
                Timeline.Thing[] things = new Timeline.Thing[buf.readVarInt()];
                for (int j = 0; j < things.length; j++) things[j] = readThing(buf, looks, outfits);
                frames[i] = new Timeline.Frame(tick, things);
            }
            Timeline.Change[] changes = new Timeline.Change[buf.readVarInt()];
            for (int i = 0; i < changes.length; i++) {
                changes[i] = new Timeline.Change(buf.readVarLong(), BlockPos.of(buf.readLong()), Block.stateById(buf.readVarInt()),
                        Block.stateById(buf.readVarInt()));
            }
            Timeline.Noise[] noises = new Timeline.Noise[buf.readVarInt()];
            for (int i = 0; i < noises.length; i++) {
                noises[i] = new Timeline.Noise(buf.readVarLong(), buf.readIdentifier(), SoundSource.valueOf(buf.readUtf()), buf.readFloat(),
                        buf.readFloat(), buf.readDouble(), buf.readDouble(), buf.readDouble(), SoundInstance.Attenuation.valueOf(buf.readUtf()),
                        buf.readBoolean());
            }
            return new Timeline.Recording(frames, changes, noises);
        } catch (RuntimeException e) {
            throw new IOException("the file is damaged (" + e.getMessage() + ")", e);
        } finally {
            buf.release();
        }
    }

    private static Timeline.Thing readThing(RegistryFriendlyByteBuf buf, List<Object> looks, List<Object[]> outfits) {
        int id = buf.readVarInt();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(buf.readIdentifier());
        java.util.UUID uuid = buf.readUUID();
        GameProfile profile = buf.readBoolean() ? new GameProfile(buf.readUUID(), buf.readUtf()) : null;
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        float[] f = new float[7];
        for (int i = 0; i < f.length; i++) f[i] = buf.readFloat();
        int hurt = buf.readVarInt();
        int death = buf.readVarInt();
        int look = buf.readVarInt();
        Object data;
        if (look >= 0) {
            data = looks.get(look);
        } else {
            List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
            for (int key = buf.readUnsignedByte(); key != 255; key = buf.readUnsignedByte()) values.add(SynchedEntityData.DataValue.read(buf, key));
            data = values;
            looks.add(values);
        }
        int outfit = buf.readVarInt();
        Object[] equipment;
        if (outfit == -2) {
            equipment = null;
        } else if (outfit >= 0) {
            equipment = outfits.get(outfit);
        } else {
            equipment = new Object[buf.readVarInt()];
            for (int i = 0; i < equipment.length; i++) equipment[i] = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            outfits.add(equipment);
        }
        return new Timeline.Thing(id, type, uuid, profile, x, y, z, f[0], f[1], f[2], f[3], f[4], f[5], f[6], hurt, death, data, equipment);
    }
}
