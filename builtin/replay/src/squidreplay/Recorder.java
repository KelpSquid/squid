package squidreplay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes down, every tick, where everything near you is and how it looks, and every block that changes. It only keeps
 * what a replay needs to look right, and reuses what didn't change, so a few minutes take a few megabytes.
 */
final class Recorder {
    /** How far from you things get recorded, in blocks. */
    static final double RANGE = 96;

    final Timeline timeline = new Timeline();
    private ClientLevel level;
    private long tick;
    private Map<Integer, Timeline.Thing> last = new HashMap<>();

    /** Records one tick. A new world (or dimension) starts a fresh recording. */
    void tick(Minecraft minecraft, int keepTicks) {
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }
        if (minecraft.level != level) {
            clear();
            level = minecraft.level;
        }
        if (minecraft.isPaused()) return; // paused time isn't part of what happened
        tick++;
        Map<Integer, Timeline.Thing> now = new HashMap<>();
        List<Timeline.Thing> things = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (Playback.isGhost(entity) || entity.distanceToSqr(minecraft.player) > RANGE * RANGE) continue;
            Timeline.Thing thing = capture(entity, last.get(entity.getId()));
            things.add(thing);
            now.put(entity.getId(), thing);
        }
        last = now;
        timeline.add(new Timeline.Frame(tick, things.toArray(new Timeline.Thing[0])), keepTicks);
    }

    /** A block changing in the recorded world (Squid calls this from Minecraft's setBlock). */
    void blockChanged(ClientLevel in, BlockPos pos, BlockState before, BlockState after) {
        if (in != level || before == after || timeline.size() == 0) return;
        timeline.add(new Timeline.Change(tick, pos.immutable(), before, after));
    }

    void clear() {
        timeline.clear();
        last = new HashMap<>();
        level = null;
    }

    private static Timeline.Thing capture(Entity entity, Timeline.Thing before) {
        Object profile = entity instanceof Player player ? player.getGameProfile() : null;
        float body = entity.getYRot();
        float walkPosition = 0;
        float walkSpeed = 0;
        float swing = 0;
        int hurtTime = 0;
        int deathTime = 0;
        Object[] equipment = null;
        if (entity instanceof LivingEntity living) {
            body = living.yBodyRot;
            walkPosition = living.walkAnimation.position();
            walkSpeed = living.walkAnimation.speed();
            swing = living.getSwingAnimation(1);
            hurtTime = living.hurtTime;
            deathTime = living.deathTime;
            equipment = equipment(living, before == null ? null : before.equipment);
        }
        return new Timeline.Thing(entity.getId(), entity.getType(), entity.getUUID(), profile, entity.getX(), entity.getY(), entity.getZ(),
                entity.getYRot(), entity.getXRot(), entity.getYHeadRot(), body, walkPosition, walkSpeed, swing, hurtTime, deathTime,
                data(entity, before == null ? null : before.data), equipment);
    }

    /** What it's wearing and holding: the same array as last tick if nothing changed. */
    private static Object[] equipment(LivingEntity living, Object[] before) {
        List<EquipmentSlot> slots = EquipmentSlot.VALUES;
        boolean same = before != null;
        for (int i = 0; same && i < slots.size(); i++) same = ItemStack.matches((ItemStack) before[i], living.getItemBySlot(slots.get(i)));
        if (same) return before;
        Object[] now = new Object[slots.size()];
        for (int i = 0; i < slots.size(); i++) now[i] = living.getItemBySlot(slots.get(i)).copy();
        return now;
    }

    /** Its synced data (pose, flags, colors, names...): the same list as last tick if nothing changed. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object data(Entity entity, Object before) {
        List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
        if (values == null) values = List.of();
        if (before instanceof List<?> old && sameData((List<SynchedEntityData.DataValue<?>>) old, values)) return before;
        List<SynchedEntityData.DataValue<?>> copy = new ArrayList<>(values.size());
        for (SynchedEntityData.DataValue<?> value : values) {
            if (value.value() instanceof ItemStack stack) {
                copy.add(new SynchedEntityData.DataValue(value.id(), (EntityDataSerializer) value.serializer(), stack.copy()));
            } else {
                copy.add(value);
            }
        }
        return copy;
    }

    private static boolean sameData(List<SynchedEntityData.DataValue<?>> a, List<SynchedEntityData.DataValue<?>> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            SynchedEntityData.DataValue<?> x = a.get(i);
            SynchedEntityData.DataValue<?> y = b.get(i);
            if (x.id() != y.id()) return false;
            if (x.value() instanceof ItemStack s && y.value() instanceof ItemStack t) {
                if (!ItemStack.matches(s, t)) return false;
            } else if (!Objects.equals(x.value(), y.value())) {
                return false;
            }
        }
        return true;
    }
}
