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
 * The Mods screen, built into Squid: a Mods button in the top-left corner of the title screen and the pause menu.
 * It lists every mod, turns them on and off, and opens each mod's settings.
 */
public class ModsMenu implements SquidMod {
    private static Method addWidget;

    @Override
    public void init(Squid squid) {
        squid.atEnd("net.minecraft.client.gui.screens.TitleScreen", "init", "()V", call -> addButton((Screen) call.self()));
        squid.atEnd("net.minecraft.client.gui.screens.PauseScreen", "init", "()V", call -> addButton((Screen) call.self()));
    }

    private static void addButton(Screen screen) {
        Button mods = Button.builder(Component.literal(Lang.t("Mods")),
                button -> Minecraft.getInstance().setScreenAndShow(new ModListScreen(screen))).bounds(4, 4, 60, 20).build();
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
