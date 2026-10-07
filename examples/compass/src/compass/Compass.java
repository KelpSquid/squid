package compass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import squid.api.Hud;
import squid.api.Squid;
import squid.api.SquidMod;

/** A compass bar across the top of the screen. The direction you're facing is always in the middle. */
public class Compass implements SquidMod {
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"}; // every 45 degrees
    private static final int WIDTH = 180;  // GUI pixels
    private static final int SHOWN = 90;   // how many degrees fit on each side of the middle

    @Override
    public void init(Squid squid) {
        squid.onHud(this::draw);
    }

    private void draw(Hud hud) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        // Minecraft counts 0 degrees as south. Compasses count 0 as north, so turn it halfway around.
        double heading = ((player.getYRot() % 360) + 360 + 180) % 360;

        int left = (hud.width() - WIDTH) / 2;
        int middle = hud.width() / 2;
        int top = 4;
        hud.box(left, top, WIDTH, 14, 0x90000000);

        for (int angle = 0; angle < 360; angle += 15) {
            double away = ((angle - heading + 540) % 360) - 180; // -180 to 180 degrees from where you're facing
            if (Math.abs(away) > SHOWN) continue;
            int x = middle + (int) Math.round(away * (WIDTH / 2.0) / SHOWN);
            if (angle % 45 == 0) {
                String name = NAMES[angle / 45];
                int color = name.equals("N") ? 0xFFFF5555 : name.length() == 1 ? 0xFFFFFFFF : 0xFFB0B0B0;
                hud.centeredText(name, x, top + 3, color);
            } else {
                hud.box(x, top + 5, 1, 4, 0xFF808080); // a small tick between the names
            }
        }
        hud.box(middle, top - 1, 1, 16, 0xFFFFFF55); // the middle marker
        hud.centeredText(Math.round(heading) % 360 + "°", middle, top + 17, 0xFFFFFFFF);
    }
}
