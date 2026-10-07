package squidstore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import squid.Lang;
import squid.api.Squid;
import squid.api.SquidMod;

import java.lang.reflect.Method;

/**
 * The Squid Store, built into Squid: a Store button on Minecraft's title screen, next to Realms
 * (Realms gets half the width, the way the two Options and Quit buttons share a row).
 */
public class Store implements SquidMod {
    private Method addWidget;

    @Override
    public void init(Squid squid) {
        squid.atEnd("net.minecraft.client.gui.screens.TitleScreen", "init", "()V", call -> addButton((TitleScreen) call.self()));
    }

    private void addButton(TitleScreen title) {
        AbstractWidget realms = null;
        for (GuiEventListener child : title.children()) {
            if (child instanceof AbstractWidget widget && widget.getMessage().getContents() instanceof TranslatableContents text
                    && text.getKey().equals("menu.online")) {
                realms = widget;
            }
        }
        int x;
        int y;
        int width;
        if (realms != null) {
            realms.setWidth(98);
            x = realms.getX() + 102;
            y = realms.getY();
            width = 98;
        } else {
            x = 4; // no Realms button (like in a demo): top-left corner instead
            y = 4;
            width = 80;
        }
        Button store = Button.builder(Component.literal(Lang.t("Store")),
                button -> Minecraft.getInstance().setScreenAndShow(new StoreScreen(title))).bounds(x, y, width, 20).build();
        try {
            if (addWidget == null) {
                // A screen's own method for adding buttons, which other code can't normally call
                addWidget = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
                addWidget.setAccessible(true);
            }
            addWidget.invoke(title, store);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Couldn't add the Store button"), e);
        }
    }
}
