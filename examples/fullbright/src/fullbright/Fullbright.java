package fullbright;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.network.chat.Component;
import squid.api.KeyBinding;
import squid.api.Squid;
import squid.api.SquidMod;

import java.lang.reflect.Field;

/**
 * Press B to see in the dark: caves and nights look like daytime. Press B again to switch it off.
 * It uses Minecraft's own night vision lighting, without the potion effect (no icon, no flashing when it runs out).
 */
public class Fullbright implements SquidMod {
    private KeyBinding key;
    private volatile boolean on;
    private volatile boolean changed; // the light needs working out again right away
    private Field needsUpdate;

    @Override
    public void init(Squid squid) {
        key = squid.addKeyBinding("Fullbright", InputConstants.KEY_B);
        squid.onTick(() -> {
            while (key.pressed()) {
                on = !on;
                changed = true;
                Minecraft.getInstance().gui.chatListener().handleOverlay(Component.literal("Fullbright: " + (on ? "ON" : "OFF")));
            }
        });

        // Minecraft works out how bright each light level looks, then hands it to the graphics card.
        // Asking for that again right after a switch makes the change show at once.
        squid.atStart("net.minecraft.client.renderer.LightmapRenderStateExtractor", "extract", call -> {
            if (changed) {
                changed = false;
                updateNow(call.self());
            }
        });
        squid.atEnd("net.minecraft.client.renderer.LightmapRenderStateExtractor", "extract", call -> {
            if (on) brighten((LightmapRenderState) call.args()[0]);
        });
    }

    /** Full night vision, no darkness effect, and the brightness slider at its highest. */
    static void brighten(LightmapRenderState light) {
        light.nightVisionEffectIntensity = 1;
        light.nightVisionColor = LightmapRenderStateExtractor.WHITE;
        light.darknessEffectScale = 0;
        light.brightness = 1;
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
