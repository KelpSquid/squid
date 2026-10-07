package xray;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import squid.api.KeyBinding;
import squid.api.Squid;
import squid.api.SquidMod;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Press X to see through the ground: everything disappears except ores (and spawners and ancient debris),
 * and they're lit up so they're easy to spot. Press X again to switch it off.
 */
public class Xray implements SquidMod {
    /** Blocks that stay visible besides every "..._ore". */
    private static final Set<String> ALSO_SHOWN = Set.of("ancient_debris", "spawner", "trial_spawner", "vault", "budding_amethyst");

    private KeyBinding key;
    private volatile boolean on;
    private volatile boolean changed;
    private Field needsUpdate;
    private final Map<Block, Boolean> shown = new ConcurrentHashMap<>(); // chunks are built on several threads at once

    @Override
    public void init(Squid squid) {
        key = squid.addKeyBinding("X-Ray", InputConstants.KEY_X);
        squid.onTick(() -> {
            while (key.pressed()) toggle();
        });

        // Minecraft builds each chunk's look block by block. With X-Ray on, it skips every block that isn't shown...
        squid.atStart("net.minecraft.client.renderer.block.ModelBlockRenderer", "tesselateBlock", call -> {
            if (on && !shows((BlockState) call.args()[6])) call.cancel();
        });
        // ...and water and lava, which would hide ores under them
        squid.atStart("net.minecraft.client.renderer.block.FluidRenderer", "tesselate", call -> {
            if (on) call.cancel();
        });
        // A block's sides that touch stone are normally left out, since nobody can see them. Ores need every side now.
        squid.atEnd("net.minecraft.client.renderer.block.ModelBlockRenderer", "shouldRenderFace", call -> {
            if (on && shows((BlockState) call.args()[1])) call.setReturnValue(true);
        });

        // Ores deep in the ground get no light, so light everything up while X-Ray is on
        squid.atStart("net.minecraft.client.renderer.LightmapRenderStateExtractor", "extract", call -> {
            if (changed) {
                changed = false;
                updateNow(call.self());
            }
        });
        squid.atEnd("net.minecraft.client.renderer.LightmapRenderStateExtractor", "extract", call -> {
            if (on) {
                LightmapRenderState light = (LightmapRenderState) call.args()[0];
                light.nightVisionEffectIntensity = 1;
                light.nightVisionColor = LightmapRenderStateExtractor.WHITE;
                light.darknessEffectScale = 0;
                light.brightness = 1;
            }
        });
    }

    private void toggle() {
        Minecraft minecraft = Minecraft.getInstance();
        on = !on;
        changed = true;
        // Minecraft skips drawing chunks hidden behind solid ground. With the ground gone, it has to draw them all.
        minecraft.smartCull = !on;
        // Build every chunk's look again, with or without the hidden blocks. This is what F3+A does.
        minecraft.levelExtractor.allChanged();
        minecraft.gui.chatListener().handleOverlay(Component.literal("X-Ray: " + (on ? "ON" : "OFF")));
    }

    private boolean shows(BlockState state) {
        return shown.computeIfAbsent(state.getBlock(), block -> {
            String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
            return name.endsWith("_ore") || ALSO_SHOWN.contains(name);
        });
    }

    private void updateNow(Object extractor) {
        try {
            if (needsUpdate == null) {
                needsUpdate = extractor.getClass().getDeclaredField("needsUpdate");
                needsUpdate.setAccessible(true);
            }
            needsUpdate.setBoolean(extractor, true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Couldn't update the light", e);
        }
    }
}
