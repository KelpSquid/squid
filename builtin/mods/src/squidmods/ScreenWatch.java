package squidmods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runs every tick for {@link ModsMenu}: makes sure the Squid button is on the title screen and the pause menu (and
 * puts it back if it isn't), and shows what's new once after an update.
 *
 * This is its own class on purpose: it names TitleScreen and PauseScreen, and Java loads those when it checks a class
 * that uses them like this. ModsMenu is checked while Squid starts, before every hook into the title screen is in, so
 * if this code were in ModsMenu, the title screen would load too early and miss those hooks (the Squid and Store
 * buttons both went missing that way). This class is only loaded on the first tick, after Minecraft has started.
 */
final class ScreenWatch {
    /** The Squid button on each title or pause screen, to check it's still there. */
    private static final Map<Screen, Button> BUTTONS = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean toldMissing;
    private static boolean newsChecked;

    private ScreenWatch() {
    }

    static void added(Screen screen, Button button) {
        BUTTONS.put(screen, button);
    }

    static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        Screen screen = minecraft.gui.screen();
        if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
            Button button = BUTTONS.get(screen);
            if (button == null || !screen.children().contains(button)) {
                if (!toldMissing) {
                    toldMissing = true;
                    System.out.println("[Squid] The Squid button wasn't on " + screen.getClass().getSimpleName()
                            + (button == null ? " (it was never added)" : " (it was taken off)") + ", so it was put back.");
                }
                ModsMenu.addButton(screen);
            }
        }
        // The first time the title screen shows after an update: what's new, once
        if (!newsChecked && screen instanceof TitleScreen title) {
            newsChecked = true;
            if (!WhatsNew.seen(WhatsNew.file())) {
                WhatsNew.markSeen(WhatsNew.file());
                minecraft.setScreenAndShow(new WhatsNewScreen(title));
            }
        }
    }
}
