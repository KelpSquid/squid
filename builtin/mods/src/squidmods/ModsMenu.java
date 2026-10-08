package squidmods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;
import squid.api.Squid;
import squid.api.SquidMod;

import java.lang.reflect.Method;

/**
 * The Squid menu and the Mods screen, built into Squid: one Squid button in the top-left corner of the title screen
 * and the pause menu opens the Squid menu (Mods, plus every button mods add with Squid.addMenuButton). The Mods screen
 * lists every mod, turns them on and off, and opens each mod's settings.
 */
public class ModsMenu implements SquidMod {
    private static Method addWidget;

    @Override
    public void init(Squid squid) {
        squid.atEnd("net.minecraft.client.gui.screens.TitleScreen", "init", "()V", call -> addButton((Screen) call.self()));
        squid.atEnd("net.minecraft.client.gui.screens.PauseScreen", "init", "()V", call -> addButton((Screen) call.self()));
        squid.onTick(ModsMenu::tick);
    }

    private static boolean newsChecked;

    /** The first time the title screen shows after an update: what's new, once. */
    private static void tick() {
        if (newsChecked) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !(minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen title)) return;
        newsChecked = true;
        if (WhatsNew.seen(WhatsNew.file())) return;
        WhatsNew.markSeen(WhatsNew.file());
        minecraft.setScreenAndShow(new WhatsNewScreen(title));
    }

    private static void addButton(Screen screen) {
        Button mods = Button.builder(Component.literal("Squid"),
                button -> Minecraft.getInstance().setScreenAndShow(new SquidMenuScreen(screen))).bounds(4, 4, 60, 20).build();
        try {
            if (addWidget == null) {
                // A screen's own method for adding buttons, which other code can't normally call
                addWidget = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
                addWidget.setAccessible(true);
            }
            addWidget.invoke(screen, mods);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Couldn't add the Mods button"), e);
        }
    }
}
