package squidreplay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the replay remembers: for every tick, where each nearby thing was and how it looked, plus every block that
 * changed. Old ticks drop off the back, so it always holds the last few minutes. Nothing in here needs Minecraft
 * running, so it can be tested on its own (the Minecraft objects ride along as plain Objects).
 */
public final class Timeline {
    /** One thing (a player, a mob, an item...) at one tick. */
    public static final class Thing {
        final int id;
        final Object type;      // its EntityType
        final UUID uuid;
        final Object profile;   // a player's GameProfile, or null
        public final double x, y, z;
        public final float yRot, xRot, headRot, bodyRot;
        final float walkPosition, walkSpeed, swing;
        final int hurtTime, deathTime;
        final Object data;      // its synced data (a List of DataValues): the same object while it doesn't change
        final Object[] equipment; // ItemStacks by slot, or null: the same array while nothing changes

        public Thing(int id, Object type, UUID uuid, Object profile, double x, double y, double z, float yRot, float xRot,
              float headRot, float bodyRot, float walkPosition, float walkSpeed, float swing, int hurtTime, int deathTime,
              Object data, Object[] equipment) {
            this.id = id;
            this.type = type;
            this.uuid = uuid;
            this.profile = profile;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yRot = yRot;
            this.xRot = xRot;
            this.headRot = headRot;
            this.bodyRot = bodyRot;
            this.walkPosition = walkPosition;
            this.walkSpeed = walkSpeed;
            this.swing = swing;
            this.hurtTime = hurtTime;
            this.deathTime = deathTime;
            this.data = data;
            this.equipment = equipment;
        }

        /** Partway (0 to 1) from this tick's thing to the next tick's, for smooth slow motion. */
        Thing toward(Thing next, float f) {
            if (next == null || f <= 0) return this;
            return new Thing(id, type, uuid, profile, lerp(x, next.x, f), lerp(y, next.y, f), lerp(z, next.z, f),
                    angle(yRot, next.yRot, f), lerp(xRot, next.xRot, f), angle(headRot, next.headRot, f), angle(bodyRot, next.bodyRot, f),
                    lerp(walkPosition, next.walkPosition, f), lerp(walkSpeed, next.walkSpeed, f), lerp(swing, next.swing, f),
                    f < 0.5f ? hurtTime : next.hurtTime, f < 0.5f ? deathTime : next.deathTime,
                    f < 0.5f ? data : next.data, f < 0.5f ? equipment : next.equipment);
        }
    }

    /** Every thing at one tick. */
    public record Frame(long tick, Thing[] things) {
        Thing find(int id) {
            for (Thing thing : things) {
                if (thing.id == id) return thing;
            }
            return null;
        }
    }

    /** A block that changed: where, and what it was before and after. */
    public record Change(long tick, Object pos, Object before, Object after) {
    }

    /** A particle that appeared (smoke, sparks, splashes...): which, where, and which way it was flying. */
    public record Spark(long tick, Object options, double x, double y, double z, double dx, double dy, double dz) {
    }

    /** At most this many particles are kept per tick, so a huge explosion can't fill the memory. */
    static final int SPARKS_PER_TICK = 300;

    /** A sound that played: which, how loud and high, and where (the Minecraft details ride along as Objects). */
    public record Noise(long tick, Object id, Object source, float volume, float pitch, double x, double y, double z,
                        Object attenuation, boolean relative) {
    }

    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final ArrayDeque<Change> changes = new ArrayDeque<>();
    private final ArrayDeque<Noise> noises = new ArrayDeque<>();
    private final ArrayDeque<Spark> sparks = new ArrayDeque<>();
    private long sparkTick;
    private int sparksThisTick;

    /** Adds a tick, and lets go of the ticks more than keep ticks old (and the block changes before them). */
    public synchronized void add(Frame frame, int keep) {
        frames.addLast(frame);
        while (!frames.isEmpty() && frames.peekFirst().tick() <= frame.tick() - keep) frames.removeFirst();
        long oldest = frames.peekFirst().tick();
        while (!changes.isEmpty() && changes.peekFirst().tick() < oldest) changes.removeFirst();
        while (!noises.isEmpty() && noises.peekFirst().tick() < oldest) noises.removeFirst();
        while (!sparks.isEmpty() && sparks.peekFirst().tick() < oldest) sparks.removeFirst();
    }

    public synchronized void add(Spark spark) {
        if (spark.tick() != sparkTick) {
            sparkTick = spark.tick();
            sparksThisTick = 0;
        }
        if (sparksThisTick++ < SPARKS_PER_TICK) sparks.addLast(spark);
    }

    public synchronized void add(Noise noise) {
        noises.addLast(noise);
    }

    public synchronized void add(Change change) {
        changes.addLast(change);
    }

    public synchronized void clear() {
        frames.clear();
        changes.clear();
        noises.clear();
        sparks.clear();
    }

    public synchronized int size() {
        return frames.size();
    }

    /** A still copy to play back, which keeps working while recording goes on. */
    public synchronized Recording freeze() {
        return new Recording(frames.toArray(new Frame[0]), changes.toArray(new Change[0]), noises.toArray(new Noise[0]), sparks.toArray(new Spark[0]));
    }

    /** A frozen stretch of time to play back. */
    public static final class Recording {
        final Frame[] frames;
        final Change[] changes;
        final Noise[] noises;
        final Spark[] sparks;

        Recording(Frame[] frames, Change[] changes, Noise[] noises, Spark[] sparks) {
            this.frames = frames;
            this.changes = changes;
            this.noises = noises;
            this.sparks = sparks;
        }

        /** The particles that appeared after one tick, up to and including another. */
        public List<Spark> sparksBetween(long after, long upTo) {
            List<Spark> out = new ArrayList<>();
            for (Spark spark : sparks) {
                if (spark.tick() > after && spark.tick() <= upTo) out.add(spark);
            }
            return out;
        }

        /** The sounds that played after one tick, up to and including another. */
        public List<Noise> noisesBetween(long after, long upTo) {
            List<Noise> out = new ArrayList<>();
            for (Noise noise : noises) {
                if (noise.tick() > after && noise.tick() <= upTo) out.add(noise);
            }
            return out;
        }

        public int length() {
            return frames.length;
        }

        /** Where everything was at a time (in ticks from the start, between ticks too). */
        public List<Thing> at(double time) {
            int i = (int) Math.floor(Math.max(0, Math.min(frames.length - 1, time)));
            float f = (float) Math.max(0, Math.min(1, time - i));
            Frame now = frames[i];
            Frame next = i + 1 < frames.length ? frames[i + 1] : null;
            List<Thing> out = new ArrayList<>(now.things().length);
            for (Thing thing : now.things()) out.add(next == null ? thing : thing.toward(next.find(thing.id), f));
            return out;
        }

        public long tickAt(double time) {
            return frames[(int) Math.max(0, Math.min(frames.length - 1, Math.floor(time)))].tick();
        }

        /** How many block changes had happened by a tick. */
        public int changesBy(long tick) {
            int low = 0;
            int high = changes.length;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (changes[mid].tick() <= tick) low = mid + 1;
                else high = mid;
            }
            return low;
        }
    }

    static double lerp(double a, double b, float f) {
        return a + (b - a) * f;
    }

    static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    /** Turns the short way round: from 350 to 10 degrees goes through 0, not back through 180. */
    public static float angle(float a, float b, float f) {
        float diff = ((b - a) % 360 + 540) % 360 - 180;
        return a + diff * f;
    }
}
