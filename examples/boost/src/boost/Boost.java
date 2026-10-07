package boost;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import squid.api.Squid;
import squid.api.SquidMod;

/**
 * Makes busy places run smoother by skipping what's too far away to see well:
 * dropped items and XP orbs past 32 blocks, and chests, signs and other block entities past 48.
 * It only skips drawing them. Nothing changes in the world, and the way the world is drawn stays the same,
 * so it works with resource packs and their shaders.
 */
public class Boost implements SquidMod {
    private static final double ITEM_DISTANCE = 32;         // Minecraft draws items up to about 64 blocks away
    private static final int BLOCK_ENTITY_DISTANCE = 48;    // and chests and signs up to 64

    @Override
    public void init(Squid squid) {
        // Item farms and mob grinders can have hundreds of items and orbs lying around. Far away they're specks.
        squid.atEnd("net.minecraft.client.renderer.entity.EntityRenderDispatcher", "shouldRender", call -> {
            if (!(Boolean) call.returnValue()) return;
            Entity entity = (Entity) call.args()[0];
            if (!(entity instanceof ItemEntity) && !(entity instanceof ExperienceOrb)) return;
            double dx = entity.getX() - (Double) call.args()[2];
            double dy = entity.getY() - (Double) call.args()[3];
            double dz = entity.getZ() - (Double) call.args()[4];
            if (dx * dx + dy * dy + dz * dz > ITEM_DISTANCE * ITEM_DISTANCE) call.setReturnValue(false);
        });

        // Chests, signs, banners and other block entities each take extra work to draw. Beacons and others that
        // choose their own distance keep it: this only changes the usual 64.
        squid.atEnd("net.minecraft.client.renderer.blockentity.BlockEntityRenderer", "getViewDistance", call ->
                call.setReturnValue(Math.min((Integer) call.returnValue(), BLOCK_ENTITY_DISTANCE)));
    }
}
