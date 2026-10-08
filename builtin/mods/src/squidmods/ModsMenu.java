package squidmods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Events;
import squid.Lang;
import squid.ModPacks;
import squid.Screens;
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
        // In its own class, loaded on the first tick: it names TitleScreen and PauseScreen, and loading those before
        // their hooks are in (Squid's Store hooks the title screen after this) would keep the hooks out
        squid.onTick(() -> ScreenWatch.tick());
        // Mods drawing in the world (Squid.onWorldDraw): Minecraft gathers this frame's gizmos (its boxes, lines and
        // floating text) right here, so mods' shapes join them
        squid.atStart("net.minecraft.client.renderer.extract.LevelExtractor", "extractGizmos", "()V", call -> {
            if (Events.drawingWorld()) Events.drawWorld(WorldGizmos.PAINTER);
        });
        // A mod saved with new pictures or sounds (or added, or turned off): Minecraft loads its resources again, so
        // they show without a restart (like pressing F3+T)
        squid.onTick(() -> {
            if (ModPacks.assetsChanged()) Minecraft.getInstance().reloadResourcePacks();
        });
        // Mods' own screens (Squid.screen). A lambda, so the screen class only loads when one opens
        Screens.opener = screen -> BuiltScreen.open(screen);
    }

    static void addButton(Screen screen) {
        Button mods = Button.builder(Component.literal("Squid"),
                button -> Minecraft.getInstance().setScreenAndShow(new SquidMenuScreen(screen))).bounds(4, 4, 60, 20).build();
        try {
            if (addWidget == null) {
                // A screen's own method for adding buttons, which other code can't normally call
                addWidget = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
                addWidget.setAccessible(true);
            }
            addWidget.invoke(screen, mods);
            ScreenWatch.added(screen, mods);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Couldn't add the Mods button"), e);
        }
    }
}
