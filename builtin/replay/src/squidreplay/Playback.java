package squidreplay;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plays a recording back in the world you're in, Skate 3 style: the real players and mobs are hidden, and look-alike
 * copies ("ghosts") act out what was recorded, while the blocks are put back the way they were at that moment. A free
 * camera floats wherever you fly it. Leaving puts every block back and gives the camera back to you.
 */
final class Playback {
    private static volatile Playback current;

    final Timeline.Recording recording;
    private final Minecraft minecraft;
    private final ClientLevel level;
    private final Map<Integer, Entity> ghosts = new HashMap<>();
    private final Set<Entity> ghostSet = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Entity, Float> swings = new IdentityHashMap<>();
    private final Map<Entity, Object> shownData = new IdentityHashMap<>();
    private final Map<Entity, Object[]> shownEquipment = new IdentityHashMap<>();
    private final Entity camera;
    private final boolean hudWasHidden;
    private int appliedChanges;
    // How every block the replay touches looks right now, to put back exactly when it ends
    private final Map<BlockPos, BlockState> before = new HashMap<>();
    private int nextId = -1_000_000; // ghosts get ids no real entity uses

    /** The camera's place and direction, which the replay screen moves around. */
    double cameraX, cameraY, cameraZ;
    float cameraYaw, cameraPitch;
    /** The lens: the field of view in degrees, or 0 for the player's own. */
    volatile float fov;

    private Playback(Minecraft minecraft, Timeline.Recording recording) {
        this.minecraft = minecraft;
        this.recording = recording;
        this.level = minecraft.level;
        this.appliedChanges = recording.changes.length; // the world starts out as it is now: every change happened
        for (Timeline.Change change : recording.changes) before.putIfAbsent((BlockPos) change.pos(), level.getBlockState((BlockPos) change.pos()));
        camera = EntityTypes.MARKER.create(level, EntitySpawnReason.LOAD);
        Entity player = minecraft.player;
        cameraX = player.getX();
        cameraY = player.getEyeY();
        cameraZ = player.getZ();
        cameraYaw = player.getYRot();
        cameraPitch = player.getXRot();
        hudWasHidden = minecraft.gui.hud.isHidden();
    }

    /** Starts playing a recording back. */
    static Playback start(Minecraft minecraft, Timeline.Recording recording) {
        stopCurrent();
        Playback playback = new Playback(minecraft, recording);
        current = playback;
        playback.moveCamera();
        minecraft.setCameraEntity(playback.camera);
        if (!playback.hudWasHidden) minecraft.gui.hud.toggle();
        return playback;
    }

    static Playback current() {
        return current;
    }

    /** Whether something is one of the replay's look-alikes (so it isn't recorded, and is drawn while replaying). */
    static boolean isGhost(Object entity) {
        Playback playback = current;
        return playback != null && playback.ghostSet.contains(entity);
    }

    /** Whether Minecraft should skip drawing something: everything real is hidden while a replay plays. */
    static boolean hides(Object entity) {
        Playback playback = current;
        return playback != null && !playback.ghostSet.contains(entity);
    }

    /** The replay camera's field of view, or null when there's no replay or it uses the player's own. */
    static Float fovOverride() {
        Playback playback = current;
        return playback == null || playback.fov <= 0 ? null : playback.fov;
    }

    /** Everything recorded at a time, to pick a camera target from. */
    List<Timeline.Thing> things(double time) {
        return recording.at(time);
    }

    /** How high a thing's eyes are, for cameras that look at it. */
    double eyeHeight(Timeline.Thing thing) {
        Entity ghost = ghosts.get(thing.id);
        if (ghost != null) return ghost.getEyeHeight();
        return thing.profile != null ? 1.62 : 0.5;
    }

    /** A thing's name: a player's name, or what it is ("Pig"). */
    static String name(Timeline.Thing thing) {
        if (thing.profile instanceof GameProfile profile) return profile.name();
        return ((EntityType<?>) thing.type).getDescription().getString();
    }

    /** The recorded swing of a ghost's arm, or null for anything else. */
    static Float swingOf(Object entity) {
        Playback playback = current;
        return playback == null ? null : playback.swings.get(entity);
    }

    /** Shows the moment at a time (in ticks from the start of the recording; between ticks too). */
    void show(double time) {
        if (minecraft.level != level) {
            stop();
            return;
        }
        showBlocks(recording.changesBy(recording.tickAt(time)));
        List<Timeline.Thing> things = recording.at(time);
        Set<Integer> seen = new HashSet<>();
        for (Timeline.Thing thing : things) {
            Entity ghost = ghosts.get(thing.id);
            if (ghost == null) {
                ghost = makeGhost(thing);
                if (ghost == null) continue;
                ghosts.put(thing.id, ghost);
            }
            seen.add(thing.id);
            pose(ghost, thing);
        }
        ghosts.entrySet().removeIf(entry -> {
            if (seen.contains(entry.getKey())) return false;
            remove(entry.getValue());
            return true;
        });
        moveCamera();
    }

    /**
     * Plays the sounds from between two moments, as the replay moves forward through them. In slow motion they play
     * lower, and fast, higher, like the tape speeding up and slowing down.
     */
    void playSounds(double from, double to, float speed) {
        long after = recording.tickAt(from);
        long upTo = recording.tickAt(to);
        if (upTo <= after) return;
        for (Timeline.Noise noise : recording.noisesBetween(after, upTo)) {
            try {
                minecraft.getSoundManager().play(new net.minecraft.client.resources.sounds.SimpleSoundInstance(
                        (net.minecraft.resources.Identifier) noise.id(), (net.minecraft.sounds.SoundSource) noise.source(), noise.volume(),
                        Math.max(0.5f, Math.min(2f, noise.pitch() * speed)), net.minecraft.client.resources.sounds.SoundInstance.createUnseededRandom(),
                        false, 0, (net.minecraft.client.resources.sounds.SoundInstance.Attenuation) noise.attenuation(), noise.x(), noise.y(),
                        noise.z(), noise.relative()));
            } catch (RuntimeException e) {
                // a sound that can't play again: skip it
            }
        }
    }

    /**
     * Brings back the particles from between two moments, and (while singleplayer is paused, when Minecraft doesn't
     * move them) moves every particle along one step for each tick that passed, so smoke rises and sparks fly.
     */
    void playParticles(double from, double to) {
        long after = recording.tickAt(from);
        long upTo = recording.tickAt(to);
        if (upTo <= after) return;
        for (Timeline.Spark spark : recording.sparksBetween(after, upTo)) {
            try {
                minecraft.particleEngine.createParticle((net.minecraft.core.particles.ParticleOptions) spark.options(), spark.x(), spark.y(), spark.z(),
                        spark.dx(), spark.dy(), spark.dz());
            } catch (RuntimeException e) {
                // one that can't be made again: skip it
            }
        }
        if (minecraft.isPaused()) {
            for (long t = after; t < upTo && t < after + 5; t++) minecraft.particleEngine.tick();
        }
    }

    /** Puts blocks back (or forward) until exactly the first count changes have happened. */
    private void showBlocks(int count) {
        while (appliedChanges > count) {
            Timeline.Change change = recording.changes[--appliedChanges];
            setBlock(change.pos(), change.before());
        }
        while (appliedChanges < count) {
            Timeline.Change change = recording.changes[appliedChanges++];
            setBlock(change.pos(), change.after());
        }
    }

    private void setBlock(Object pos, Object state) {
        level.setBlock((BlockPos) pos, (BlockState) state, 2 | 16, 512); // tell the renderer, but don't update neighbors
    }

    private Entity makeGhost(Timeline.Thing thing) {
        Entity ghost;
        if (thing.profile instanceof GameProfile profile) {
            RemotePlayer player = new RemotePlayer(level, profile);
            // The real player's skin and cape: Minecraft finds them through the player list
            PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(profile.id());
            if (info != null) setField(AbstractClientPlayer.class, "playerInfo", player, info);
            ghost = player;
        } else {
            ghost = ((EntityType<?>) thing.type).create(level, EntitySpawnReason.LOAD);
            if (ghost == null) return null;
        }
        ghost.setId(nextId--);
        ghost.setUUID(UUID.randomUUID());
        ghost.setNoGravity(true);
        ghost.noPhysics = true;
        ghostSet.add(ghost);
        level.addEntity(ghost);
        return ghost;
    }

    @SuppressWarnings("unchecked")
    private void pose(Entity ghost, Timeline.Thing thing) {
        if (thing.data != shownData.get(ghost)) {
            try {
                ghost.getEntityData().assignValues((List<SynchedEntityData.DataValue<?>>) thing.data);
            } catch (RuntimeException e) {
                // a value this copy doesn't have: it just keeps its own look
            }
            shownData.put(ghost, thing.data);
        }
        ghost.setPos(thing.x, thing.y, thing.z);
        ghost.setYRot(thing.yRot);
        ghost.setXRot(thing.xRot);
        ghost.setYHeadRot(thing.headRot);
        ghost.setOldPosAndRot();
        if (ghost instanceof LivingEntity living) {
            living.yBodyRot = thing.bodyRot;
            living.yBodyRotO = thing.bodyRot;
            living.yHeadRot = thing.headRot;
            living.yHeadRotO = thing.headRot;
            living.hurtTime = thing.hurtTime;
            living.deathTime = thing.deathTime;
            walk(living.walkAnimation, thing.walkPosition, thing.walkSpeed);
            swings.put(ghost, thing.swing);
            if (thing.equipment != null && thing.equipment != shownEquipment.get(ghost)) {
                for (int i = 0; i < thing.equipment.length && i < EquipmentSlot.VALUES.size(); i++) {
                    living.setItemSlot(EquipmentSlot.VALUES.get(i), ((ItemStack) thing.equipment[i]).copy());
                }
                shownEquipment.put(ghost, thing.equipment);
            }
        }
    }

    private void moveCamera() {
        camera.setPos(cameraX, cameraY, cameraZ);
        camera.setYRot(cameraYaw);
        camera.setXRot(cameraPitch);
        camera.setYHeadRot(cameraYaw);
        camera.setOldPosAndRot();
    }

    private void remove(Entity ghost) {
        level.removeEntity(ghost.getId(), Entity.RemovalReason.DISCARDED);
        ghostSet.remove(ghost);
        swings.remove(ghost);
        shownData.remove(ghost);
        shownEquipment.remove(ghost);
    }

    /** Ends the replay: every block back to now, the copies gone, the camera and HUD back to normal. */
    void stop() {
        if (current != this) return;
        if (minecraft.level == level) {
            for (Map.Entry<BlockPos, BlockState> block : before.entrySet()) setBlock(block.getKey(), block.getValue());
            for (Entity ghost : ghosts.values()) remove(ghost);
        }
        ghosts.clear();
        current = null;
        if (minecraft.player != null) minecraft.setCameraEntity(minecraft.player);
        if (!hudWasHidden && minecraft.gui.hud.isHidden()) minecraft.gui.hud.toggle();
    }

    static void stopCurrent() {
        Playback playback = current;
        if (playback != null) playback.stop();
    }

    // ---- Walking: the legs swing by how far and how fast it was walking ----

    private static Field walkPosition;
    private static Field walkSpeed;
    private static Field walkSpeedOld;

    private static void walk(WalkAnimationState walk, float position, float speed) {
        try {
            if (walkPosition == null) {
                walkPosition = field(WalkAnimationState.class, "position");
                walkSpeed = field(WalkAnimationState.class, "speed");
                walkSpeedOld = field(WalkAnimationState.class, "speedOld");
            }
            walkPosition.setFloat(walk, position);
            walkSpeed.setFloat(walk, speed);
            walkSpeedOld.setFloat(walk, speed);
        } catch (ReflectiveOperationException e) {
            // legs stay still
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setField(Class<?> owner, String name, Object target, Object value) {
        try {
            field(owner, name).set(target, value);
        } catch (ReflectiveOperationException e) {
            // it keeps the default look
        }
    }
}
