package squidreplay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Saved replays (.sqreplay files), checked without the game window. PipelineTest loads this class through Squid's
 * loader, after Minecraft's registries are started, so the replay code and real Minecraft objects work here. Minecraft
 * isn't on the classpath the tests are built with, so its objects are made by name.
 *
 * run() gives back {what, got, expected} for PipelineTest to check.
 */
public final class SavedReplayCheck {
    private static final int MAGIC = 0x53515250; // "SQRP", as ReplayFile writes it
    private static final String CUT_OFF = "IOException: it's cut off: part of the file is missing";
    private static final String DAMAGED = "IOException: it's damaged or was changed by hand";
    private static final String TOO_BIG = "IOException: it's too big to open safely";
    private static final String NOT_A_REPLAY = "IOException: it isn't a replay";

    private static final List<String[]> results = new ArrayList<>();
    private static Object registries;

    private SavedReplayCheck() {
    }

    public static List<String[]> run() throws Throwable {
        results.clear();
        Object registry = mc("net.minecraft.core.registries.BuiltInRegistries", "REGISTRY");
        registries = load("net.minecraft.core.RegistryAccess").getMethod("fromRegistryOfRegistries", load("net.minecraft.core.Registry")).invoke(null, registry);
        Path base = Files.createTempDirectory("squid-replay-test");
        try {
            checks(base);
        } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(base)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        return results;
    }

    private static void checks(Path base) throws Throwable {
        Path folder = base.resolve("replays");
        LocalDateTime when = LocalDateTime.of(2026, 10, 13, 12, 0, 5);

        // A small replay, saved and opened again
        Timeline.Recording original = sample(64);
        Path saved = (Path) call("saveIn", folder, registries, "world:Test", "minecraft:overworld", original, 0, 2, when);
        Timeline.Recording back = (Timeline.Recording) call("read", saved, registries);
        check("a saved replay opens again exactly the same (things, looks, outfits, blocks, sounds, particles)", describe(back), describe(original));
        check("looks and outfits that didn't change are shared again after opening, so it stays small",
                (back.frames[0].things()[0].data == back.frames[1].things()[0].data) + " " + (back.frames[1].things()[0].data != back.frames[2].things()[0].data)
                        + " " + (back.frames[0].things()[1].equipment == back.frames[2].things()[1].equipment), "true true true");
        ReplayFile.Info info = (ReplayFile.Info) call("info", saved);
        check("the list reads a replay's world, dimension, length and date", info.world() + " " + info.dimension() + " " + info.ticks() + " " + info.saved(),
                "world:Test minecraft:overworld 3 2026-10-13T12:00:05");
        Timeline.Recording trimmed = (Timeline.Recording) call("read", call("saveIn", folder, registries, "world:Test", "minecraft:overworld", original, 1, 2, when), registries);
        check("saving the trimmed part keeps just those ticks, starting from 0, with their sounds and blocks", trimmed.length() + " " + trimmed.frames[0].tick()
                + " " + trimmed.changes.length + " " + trimmed.noises.length + " " + trimmed.sparks.length, "2 0 1 1 2");

        // Names: made from the time, never from what's in a file, and never over another replay
        Path second = (Path) call("saveIn", folder, registries, "world:Test", "minecraft:overworld", original, 0, 2, when);
        try (java.util.stream.Stream<Path> files = Files.list(folder)) {
            check("saves in the same second get their own names, and nothing is left half-written",
                    saved.getFileName() + " " + second.getFileName() + " | " + files.map(f -> f.getFileName().toString()).sorted().toList(),
                    "replay-2026-10-13_12-00-05.sqreplay replay-2026-10-13_12-00-05-3.sqreplay | [replay-2026-10-13_12-00-05-2.sqreplay, "
                            + "replay-2026-10-13_12-00-05-3.sqreplay, replay-2026-10-13_12-00-05.sqreplay]");
        }
        Files.write(base.resolve("outside.sqreplay"), Files.readAllBytes(saved));
        Files.createDirectories(folder.resolve("folder.sqreplay"));
        Files.writeString(folder.resolve("notes.txt"), "hi");
        check("only replays right in the replays folder can be opened or deleted", call("inFolder", folder, saved) + " "
                + call("inFolder", folder, folder.resolve("..").resolve("outside.sqreplay")) + " " + call("inFolder", folder, base.resolve("outside.sqreplay"))
                + " " + call("inFolder", folder, folder.resolve("folder.sqreplay")) + " " + call("inFolder", folder, folder.resolve("notes.txt")),
                "true false false false false");

        // Cut off: at every length, it's refused with a message (never opened half, never crashing)
        byte[] whole = Files.readAllBytes(saved);
        Path test = base.resolve("test.sqreplay");
        List<String> cutProblems = new ArrayList<>();
        for (int length = 0; length < whole.length; length++) {
            Files.write(test, Arrays.copyOf(whole, length));
            String got = outcome(test);
            if (!got.startsWith("IOException: ")) cutProblems.add(length + " bytes: " + got);
        }
        check("a replay cut off anywhere is refused with a message", cutProblems, List.of());
        Files.write(test, Arrays.copyOf(whole, whole.length / 2));
        check("a replay cut off halfway says it's cut off", outcome(test), CUT_OFF);
        Files.write(test, Arrays.copyOf(whole, 6));
        check("a replay cut off in its first bytes says it's cut off", outcome(test), CUT_OFF);
        byte[] inside = gunzip(whole);
        Files.write(test, gzip(Arrays.copyOf(inside, inside.length - 20)));
        check("a replay whose recording ends early (zipped up again) says it's cut off", outcome(test), CUT_OFF);

        // Made-up counts: refused before any room is made for them
        check("a replay claiming 2 billion ticks is too big", outcomeOf(file(1, body(2_000_000_000))), TOO_BIG);
        check("a replay claiming 30,000 things in a tick it has no bytes for is damaged", outcomeOf(file(1, body(1, 0, 30_000))), DAMAGED);
        check("a negative count is damaged", outcomeOf(file(1, body(-1))), DAMAGED);
        check("a replay claiming 5 million block changes is too big", outcomeOf(file(1, body(0, 5_000_000))), TOO_BIG);
        check("a replay claiming 600,000 sounds is too big", outcomeOf(file(1, body(0, 0, 600_000))), TOO_BIG);
        check("a replay claiming 2 million particles is too big", outcomeOf(file(1, body(0, 0, 0, 2_000_000))), TOO_BIG);
        check("a recording claiming 2 GB unzipped is too big", outcomeOf(file(1, body(0, 0, 0, 0), Integer.MAX_VALUE)), TOO_BIG);
        check("a recording claiming a negative size is damaged", outcomeOf(file(1, body(0, 0, 0, 0), -5)), DAMAGED);
        check("a recording longer than its file says it's cut off", outcomeOf(file(1, body(0, 0, 0, 0), 1000)), CUT_OFF);
        check("ticks going backward are damaged", outcomeOf(file(1, body(2, 5, 0, 3, 0))), DAMAGED);
        check("bytes left over after the end are damaged", outcomeOf(file(1, body(0, 0, 0, 0, 7))), DAMAGED);
        check("an empty recording opens (the list then says it's too short)", outcomeOf(file(1, body(0, 0, 0, 0))), "opened 0 ticks");
        check("a replay from a newer Squid says so", outcomeOf(file(99, body(0, 0, 0, 0))), "IOException: it was saved by a newer Squid");
        check("a thing far past the end of the world is damaged", outcomeOf(save(folder, sample(1e300), when)), DAMAGED);
        check("a thing at a place that isn't a number is damaged", outcomeOf(save(folder, sample(Double.NaN), when)), DAMAGED);

        // Random bytes, zipped or not, and a real header with random bytes after it
        Random random = new Random(62);
        byte[] noise = new byte[4096];
        random.nextBytes(noise);
        check("random bytes aren't a replay", outcomeOf(noise), NOT_A_REPLAY);
        check("zipped random bytes aren't a replay", outcomeOf(gzip(noise)), NOT_A_REPLAY);
        Files.write(test, noise);
        check("random bytes aren't listed", String.valueOf(call("info", test)), "null");
        check("a real header with random bytes for the recording is refused with a message", outcomeOf(file(1, noise)).startsWith("IOException: "), true);

        // Changed bytes: a few bytes of a real replay changed at random, hundreds of times. Each one either opens or
        // is refused with a message; nothing else may happen (no crash, no running out of memory)
        List<String> fuzzProblems = new ArrayList<>();
        int opened = 0;
        for (int round = 0; round < 400; round++) {
            byte[] changed = inside.clone();
            for (int n = 1 + random.nextInt(4); n > 0; n--) changed[random.nextInt(changed.length)] ^= (byte) (1 + random.nextInt(255));
            String got = outcomeOf(round % 4 == 0 ? flip(whole, random) : gzip(changed));
            if (got.startsWith("opened")) opened++;
            else if (!got.startsWith("IOException: ")) fuzzProblems.add(got);
        }
        check("400 replays with changed bytes each open or are refused with a message", fuzzProblems, List.of());
        check("(some of them still open)", opened > 0, true);

        // Too big on disk: refused before reading any of it
        try (java.io.RandomAccessFile huge = new java.io.RandomAccessFile(test.toFile(), "rw")) {
            huge.setLength(ReplayFile.MOST_FILE_BYTES + 1);
        }
        check("a replay file over " + (ReplayFile.MOST_FILE_BYTES >> 20) + " MB is too big", outcome(test), TOO_BIG);
        Files.delete(test);
    }

    // ---- A small recording: a pig and a player over 3 ticks, a block, a sound and two puffs of smoke ----

    private static Timeline.Recording sample(double pigX) throws Throwable {
        Object pigType = mc("net.minecraft.world.entity.EntityTypes", "PIG");
        Object playerType = mc("net.minecraft.world.entity.EntityTypes", "PLAYER");
        Object profile = load("com.mojang.authlib.GameProfile").getConstructor(UUID.class, String.class)
                .newInstance(UUID.fromString("00000000-0000-0000-0000-000000000062"), "Samuel");
        Object pigLook = List.of(dataValue(0, "BYTE", (byte) 3), dataValue(2, "STRING", "Bacon"));
        Object pigLookLater = List.of(dataValue(0, "BYTE", (byte) 1));
        Object playerLook = List.of(dataValue(9, "FLOAT", 20f));
        // Empty hands: a real item needs its components, which Minecraft only fills in once a world's data is loaded
        Object empty = load("net.minecraft.world.item.ItemStack").getField("EMPTY").get(null);
        Object[] outfit = {empty, empty, empty, empty, empty, empty};
        UUID pigId = UUID.fromString("00000000-0000-0000-0000-000000000007");
        Timeline.Frame[] frames = new Timeline.Frame[3];
        for (int i = 0; i < 3; i++) {
            Timeline.Thing pig = new Timeline.Thing(7, pigType, pigId, null, pigX + i * 0.25, 64, -3.5, 10 * i, 0, 15 * i, 5 * i, 0.5f * i, 0.3f, 0,
                    i == 2 ? 9 : 0, 0, i == 2 ? pigLookLater : pigLook, null);
            Timeline.Thing player = new Timeline.Thing(1, playerType, UUID.fromString("00000000-0000-0000-0000-000000000062"), profile, 2, 65, 2,
                    180, -20, 175, 170, 1, 0.8f, i * 0.3f, 0, 0, playerLook, outfit);
            frames[i] = new Timeline.Frame(100 + i, new Timeline.Thing[] {pig, player});
        }
        Object pos = load("net.minecraft.core.BlockPos").getConstructor(int.class, int.class, int.class).newInstance(4, 63, -2);
        Object stone = state("STONE");
        Object air = state("AIR");
        Timeline.Change[] changes = {new Timeline.Change(100, pos, air, stone), new Timeline.Change(101, pos, stone, air)};
        Object sound = load("net.minecraft.resources.Identifier").getMethod("parse", String.class).invoke(null, "minecraft:entity.pig.ambient");
        Timeline.Noise[] noises = {new Timeline.Noise(101, sound, enumValue("net.minecraft.sounds.SoundSource", "NEUTRAL"), 0.8f, 1.1f, 1, 64, -3,
                enumValue("net.minecraft.client.resources.sounds.SoundInstance$Attenuation", "LINEAR"), false)};
        Object smoke = mc("net.minecraft.core.particles.ParticleTypes", "SMOKE");
        Timeline.Spark[] sparks = {new Timeline.Spark(102, smoke, 1, 65, 1, 0, 0.1, 0), new Timeline.Spark(102, smoke, 1.5, 65, 1, 0.05, 0.1, 0)};
        return new Timeline.Recording(frames, changes, noises, sparks);
    }

    /** Everything in a recording, as text, with its ticks counted from its start. */
    private static String describe(Timeline.Recording r) {
        long start = r.frames[0].tick();
        StringBuilder out = new StringBuilder();
        for (Timeline.Frame f : r.frames) {
            out.append("tick ").append(f.tick() - start).append(":");
            for (Timeline.Thing t : f.things()) {
                out.append(" [").append(t.id).append(' ').append(t.type).append(' ').append(t.uuid).append(' ').append(t.profile).append(' ')
                        .append(t.x).append(',').append(t.y).append(',').append(t.z).append(' ').append(t.yRot).append(',').append(t.xRot).append(',')
                        .append(t.headRot).append(',').append(t.bodyRot).append(' ').append(t.walkPosition).append(',').append(t.walkSpeed).append(',')
                        .append(t.swing).append(' ').append(t.hurtTime).append(',').append(t.deathTime).append(' ').append(t.data).append(' ')
                        .append(t.equipment == null ? "no outfit" : Arrays.toString(t.equipment)).append(']');
            }
            out.append('\n');
        }
        for (Timeline.Change c : r.changes) out.append("change ").append(c.tick() - start).append(' ').append(c.pos()).append(' ').append(c.before()).append(" -> ").append(c.after()).append('\n');
        for (Timeline.Noise n : r.noises) {
            out.append("sound ").append(n.tick() - start).append(' ').append(n.id()).append(' ').append(n.source()).append(' ').append(n.volume()).append(' ')
                    .append(n.pitch()).append(' ').append(n.x()).append(',').append(n.y()).append(',').append(n.z()).append(' ').append(n.attenuation())
                    .append(' ').append(n.relative()).append('\n');
        }
        for (Timeline.Spark s : r.sparks) {
            out.append("particle ").append(s.tick() - start).append(' ').append(s.options()).append(' ').append(s.x()).append(',').append(s.y()).append(',')
                    .append(s.z()).append(' ').append(s.dx()).append(',').append(s.dy()).append(',').append(s.dz()).append('\n');
        }
        return out.toString();
    }

    // ---- Opening, and what came of it ----

    private static Path save(Path folder, Timeline.Recording recording, LocalDateTime when) throws Throwable {
        return (Path) call("saveIn", folder, registries, "world:Test", "minecraft:overworld", recording, 0, recording.length() - 1, when);
    }

    /** "opened N ticks", or what was thrown: "IOException: it's cut off..." */
    private static String outcome(Path file) {
        try {
            return "opened " + ((Timeline.Recording) call("read", file, registries)).length() + " ticks";
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String outcomeOf(Object fileOrBytes) throws IOException {
        if (fileOrBytes instanceof Path path) return outcome(path);
        Path file = Files.createTempFile("squid-replay-test", ".sqreplay");
        try {
            Files.write(file, (byte[]) fileOrBytes);
            return outcome(file);
        } finally {
            Files.delete(file);
        }
    }

    // ---- Files made by hand ----

    /** A replay file: the header ReplayFile writes, then a recording, claiming its real length. */
    private static byte[] file(int version, byte[] body) throws IOException {
        return file(version, body, body.length);
    }

    private static byte[] file(int version, byte[] body, int claimedLength) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(version);
        out.writeUTF("world:Test");
        out.writeUTF("minecraft:overworld");
        out.writeInt(1);
        out.writeUTF("2026-10-13T12:00");
        out.writeInt(claimedLength);
        out.write(body);
        return gzip(bytes.toByteArray());
    }

    /** A recording made of just numbers (counts and ticks), written the way Minecraft writes them. */
    private static byte[] body(int... numbers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int n : numbers) {
            while ((n & ~0x7F) != 0) {
                out.write((n & 0x7F) | 0x80);
                n >>>= 7;
            }
            out.write(n);
        }
        return out.toByteArray();
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(bytes);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        }
    }

    /** The zipped bytes themselves with one changed. */
    private static byte[] flip(byte[] bytes, Random random) {
        byte[] changed = bytes.clone();
        changed[random.nextInt(changed.length)] ^= (byte) (1 + random.nextInt(255));
        return changed;
    }

    // ---- Minecraft's objects, by name ----

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, true, SavedReplayCheck.class.getClassLoader());
    }

    private static Object mc(String className, String field) throws ReflectiveOperationException {
        return load(className).getField(field).get(null);
    }

    private static Object state(String block) throws ReflectiveOperationException {
        Object b = mc("net.minecraft.world.level.block.Blocks", block);
        return load("net.minecraft.world.level.block.Block").getMethod("defaultBlockState").invoke(b);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(String className, String name) throws ClassNotFoundException {
        return Enum.valueOf((Class) load(className), name);
    }

    private static Object dataValue(int id, String serializer, Object value) throws ReflectiveOperationException {
        Object s = mc("net.minecraft.network.syncher.EntityDataSerializers", serializer);
        return load("net.minecraft.network.syncher.SynchedEntityData$DataValue").getConstructors()[0].newInstance(id, s, value);
    }

    /** Calls one of ReplayFile's own methods (by name, as Minecraft's types can't be named here). */
    private static Object call(String name, Object... args) throws Throwable {
        for (Method m : ReplayFile.class.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                try {
                    return m.invoke(null, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void check(String what, Object got, Object expected) {
        results.add(new String[] {what, String.valueOf(got), String.valueOf(expected)});
    }
}
