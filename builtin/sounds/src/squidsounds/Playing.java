package squidsounds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import squid.Events;
import squid.api.Hud;
import squid.api.ModSettings;
import squid.api.SoundCue;
import squid.api.SquidAudio;
import squid.audio.Sqda;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The .sqda sounds playing now. Every tick it works out where each one is (by the clock since it started, which is
 * what's being heard, not how far Minecraft has read ahead), hands the cues that just passed to mods, keeps
 * {@link SquidAudio#level()} up to date from the volume track, and runs the entity triggers.
 *
 * With "Light cues glow on screen" on, light cues also glow around the edge of the screen (strobes are kept slow).
 */
final class Playing {
    private final ModSettings settings;
    private final List<Track> tracks = new CopyOnWriteArrayList<>();
    private int tick;
    // The glow from the latest light cue
    private volatile Sqda.Light glow;
    private volatile long glowStart;
    private volatile int glowRate = 48000;

    Playing(ModSettings settings) {
        this.settings = settings;
        settings.toggle("Light cues glow on screen", false);
    }

    /** One .sqda that's playing. */
    private static final class Track {
        final Sounds.SqdaStream stream;
        double heard;          // samples heard so far (counting every loop)
        long lastPosition = -1;
        long lastClock;
        final Map<Integer, Boolean>[] inView;
        final Map<Integer, Boolean>[] near;
        final int[] lastFired;

        @SuppressWarnings("unchecked")
        Track(Sounds.SqdaStream stream) {
            this.stream = stream;
            int n = stream.file.triggers.size();
            inView = new Map[n];
            near = new Map[n];
            lastFired = new int[n];
            for (int i = 0; i < n; i++) {
                inView[i] = new HashMap<>();
                near[i] = new HashMap<>();
                lastFired[i] = Integer.MIN_VALUE / 2;
            }
        }

        /** Where in the file is being heard now, following the loop. */
        long position() {
            Sqda.Player p = stream.player;
            long h = (long) heard;
            if (!p.looping() || h < p.loopEnd()) return h;
            long span = Math.max(1, p.loopEnd() - p.loopStart());
            return p.loopStart() + (h - p.loopEnd()) % span;
        }
    }

    void started(Sounds.SqdaStream stream) {
        tracks.add(new Track(stream));
    }

    void tick() {
        tick++;
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        float loudest = 0;
        String loudestName = "";
        for (Track t : tracks) {
            Sounds.SqdaStream s = t.stream;
            if (s.closed) {
                tracks.remove(t);
                continue;
            }
            if (s.firstRead == 0) continue; // not started yet
            if (t.lastClock == 0) t.lastClock = s.firstRead;
            // A higher pitch plays faster, so its cues come sooner (the file's own pitch; sounds.json's adds on top)
            if (!minecraft.isPaused()) t.heard += (now - t.lastClock) / 1e9 * s.player.rate() * Math.clamp(s.file.settings.pitch(), 0.5f, 2f);
            t.lastClock = now;
            long position = t.position();
            if (t.lastPosition < 0) fire(t, -1, position); // the first tick: a cue right at the start counts too
            else if (position != t.lastPosition) cuesBetween(t, t.lastPosition, position);
            t.lastPosition = position;
            Sqda.Levels levels = s.file.levelsOf(s.variant);
            float level = levels == null ? 0 : levels.at(position);
            if (level >= loudest) {
                loudest = level;
                loudestName = s.sound;
            }
            if (!s.file.triggers.isEmpty()) triggers(t, minecraft);
        }
        SquidAudio.update(tracks.isEmpty() ? "" : loudestName, tracks.isEmpty() ? 0 : loudest);
    }

    /** Hands mods the cues heard since last tick (around the loop's seam, both pieces). */
    private void cuesBetween(Track t, long from, long to) {
        Sqda.Player p = t.stream.player;
        if (to > from) {
            fire(t, from, to);
        } else if (p.looping()) {
            fire(t, from, p.loopEnd());
            fire(t, p.loopStart() - 1, to);
        }
    }

    private void fire(Track t, long after, long upTo) {
        Sounds.SqdaStream s = t.stream;
        double rate = s.player.rate();
        for (Sqda.Cue c : s.file.cues) {
            if (c.variant() != s.variant || c.at() <= after || c.at() > upTo) continue;
            String kind = switch (c.kind()) {
                case Sqda.BEAT -> "beat";
                case Sqda.BAR -> "bar";
                case Sqda.SECTION -> "section";
                default -> "cue";
            };
            Events.soundCue(new SoundCue(s.sound, kind, c.name(), c.at() / rate, 0, 0, 0, ""));
        }
        for (Sqda.Light l : s.file.lights) {
            if (l.variant() != s.variant || l.at() <= after || l.at() > upTo) continue;
            String effect = switch (l.effect()) {
                case Sqda.LIGHT_FLASH -> "flash";
                case Sqda.LIGHT_FADE -> "fade";
                case Sqda.LIGHT_STROBE -> "strobe";
                default -> "on";
            };
            Events.soundCue(new SoundCue(s.sound, "light", l.group(), l.at() / rate, l.length() / rate, l.color(), l.brightness(), effect));
            glow = l;
            glowStart = System.nanoTime();
            glowRate = s.player.rate();
        }
    }

    // ---- Triggers ----

    private void triggers(Track t, Minecraft minecraft) {
        var player = minecraft.player;
        var level = minecraft.level;
        if (player == null || level == null) return;
        List<Sqda.Trigger> list = t.stream.file.triggers;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1);
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 up = right.cross(look).normalize();
        double vertical = Math.tan(Math.toRadians(minecraft.options.fov().get()) / 2);
        double aspect = minecraft.getWindow().getHeight() == 0 ? 1.78 : minecraft.getWindow().getWidth() / (double) minecraft.getWindow().getHeight();
        double horizontal = vertical * aspect;
        for (Entity e : level.entitiesForRendering()) {
            if (e == player) continue;
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            for (int i = 0; i < list.size(); i++) {
                Sqda.Trigger trigger = list.get(i);
                if (!trigger.entity().equals(type) && !trigger.entity().equals("*")) continue;
                double range = trigger.distance() > 0 ? trigger.distance() : 32;
                Vec3 to = e.position().add(0, e.getBbHeight() / 2, 0).subtract(eye);
                double distance = to.length();
                boolean close = distance <= range;
                boolean seen = false;
                if (close) {
                    double z = to.dot(look);
                    seen = z > 0 && Math.abs(to.dot(right)) / z <= horizontal && Math.abs(to.dot(up)) / z <= vertical && player.hasLineOfSight(e);
                }
                boolean wasSeen = t.inView[i].getOrDefault(e.getId(), false);
                boolean wasNear = t.near[i].getOrDefault(e.getId(), false);
                t.inView[i].put(e.getId(), seen);
                t.near[i].put(e.getId(), close);
                boolean happened = switch (trigger.event()) {
                    case Sqda.ENTERS_VIEW -> seen && !wasSeen;
                    case Sqda.LEAVES_VIEW -> !seen && wasSeen;
                    default -> close && !wasNear;
                };
                if (happened && tick - t.lastFired[i] >= trigger.cooldown()) {
                    t.lastFired[i] = tick;
                    play(t, trigger, e, minecraft);
                }
            }
        }
        // Forget entities that are gone
        for (int i = 0; i < list.size(); i++) {
            t.inView[i].keySet().removeIf(id -> level.getEntity(id) == null);
            t.near[i].keySet().removeIf(id -> level.getEntity(id) == null);
        }
    }

    private void play(Track t, Sqda.Trigger trigger, Entity e, Minecraft minecraft) {
        String sound = trigger.sound();
        if (sound.startsWith("variant:")) {
            String name = sound.substring(8);
            List<Sqda.Variant> variants = t.stream.file.variants;
            for (int v = 0; v < variants.size(); v++) {
                if (!variants.get(v).name().equals(name)) continue;
                Identifier file = Identifier.tryParse(t.stream.sound + ".squidvariant" + v);
                if (file == null) return;
                minecraft.getSoundManager().play(new VariantSound(file, trigger.volume(), trigger.pitch(), e.getX(), e.getY(), e.getZ()));
                return;
            }
            return;
        }
        Identifier id = Identifier.tryParse(sound);
        if (id == null) return;
        minecraft.getSoundManager().play(new SimpleSoundInstance(SoundEvent.createVariableRangeEvent(id), SoundSource.MUSIC,
                trigger.volume(), trigger.pitch(), RandomSource.create(), e.getX(), e.getY(), e.getZ()));
    }

    /** One of a .sqda's own variants, played where an entity is. */
    static final class VariantSound extends AbstractSoundInstance {
        private final Identifier file;

        VariantSound(Identifier file, float volume, float pitch, double x, double y, double z) {
            super(file, SoundSource.MUSIC, RandomSource.create());
            this.file = file;
            this.volume = volume;
            this.pitch = pitch;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public WeighedSoundEvents getOrResolve(SoundManager manager) {
            // Not in sounds.json: it's straight from the file
            WeighedSoundEvents events = new WeighedSoundEvents(file, null);
            Sound sound = new Sound(file, r -> 1f, r -> 1f, 1, Sound.Type.FILE, false, false, 16);
            events.addSound(sound);
            this.soundEvent = events;
            this.sound = sound;
            return events;
        }

        @Override
        public SoundInstance.Attenuation getAttenuation() {
            return SoundInstance.Attenuation.LINEAR;
        }
    }

    // ---- The screen glow ----

    void hud(Hud hud) {
        Sqda.Light l = glow;
        if (l == null || !settings.toggle("Light cues glow on screen", false)) return;
        double seconds = (System.nanoTime() - glowStart) / 1e9;
        double length = Math.max(0.05, l.length() / (double) glowRate);
        if (seconds > length) {
            glow = null;
            return;
        }
        double strength = switch (l.effect()) {
            case Sqda.LIGHT_FLASH -> Math.max(0, 1 - seconds / Math.min(length, 0.25));
            case Sqda.LIGHT_FADE -> 1 - seconds / length;
            case Sqda.LIGHT_STROBE -> ((int) (seconds * 6)) % 2 == 0 ? 1 : 0; // 3 flashes a second at most, to be kind to eyes
            default -> 1;
        };
        int alpha = (int) Math.round(strength * l.brightness() * 0.45);
        if (alpha <= 0) return;
        int color = (alpha << 24) | (l.color() & 0xFFFFFF);
        int w = hud.width();
        int h = hud.height();
        int edge = Math.max(4, Math.min(w, h) / 30);
        hud.box(0, 0, w, edge, color);
        hud.box(0, h - edge, w, edge, color);
        hud.box(0, edge, edge, h - 2 * edge, color);
        hud.box(w - edge, edge, edge, h - 2 * edge, color);
    }
}
