package squidspeed;

import net.minecraft.client.Minecraft;
import squid.api.ModSettings;
import squid.api.Squid;
import squid.api.SquidMod;

/**
 * Squid Speed: makes the game run faster and cooler. Each part has a switch in its settings (Squid > Mods), and
 * switching one changes it the next time the game starts, since they change Minecraft's code as it loads.
 */
public class Speed implements SquidMod {
    private ModSettings settings;

    @Override
    public void init(Squid squid) {
        settings = squid.settings();
        if (settings.toggle("Faster chunk drawing", true)) ChunkDrawing.install(squid);
        if (settings.toggle("Faster chunk building", true)) BiomeCache.install(squid);
        // Minecraft only slows down when the window is minimized; in the background behind other windows it kept
        // drawing as fast as it could, heating the computer for nobody
        squid.atEnd("com.mojang.blaze3d.platform.FramerateLimitTracker", "getFramerateLimit", call -> {
            int cap = backgroundFps();
            if (cap <= 0 || Bench.on()) return;
            if (!Minecraft.getInstance().getWindow().isFocused()) {
                call.setReturnValue(Math.min((Integer) call.returnValue(), cap));
            }
        });
        if (Bench.on()) Bench.install(squid);
    }

    /** Frames a second while another window is in front, or 0 for no limit. */
    private int backgroundFps() {
        return switch (settings.choice("Frames in the background", "30", "No limit", "10", "30", "60")) {
            case "10" -> 10;
            case "30" -> 30;
            case "60" -> 60;
            default -> 0;
        };
    }
}
