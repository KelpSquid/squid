package squidmods;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;
import squid.Main;
import squid.api.ModInfo;
import squid.api.ModSettings;

import java.io.IOException;
import java.util.List;

/**
 * Every mod in this instance, with a switch for each and a Settings button for mods that have settings. Mods Squid
 * builds from code turn on and off right away; jars need the game to restart.
 */
final class ModListScreen extends Screen {
    private static final int ROW = 30;

    private final Screen parent;
    private List<ModFiles.ModFile> mods;
    private int page;
    private String message;
    private int messageColor = 0xFFA0A0A0;
    private int ticks;

    ModListScreen(Screen parent) {
        super(Component.literal(Lang.t("Mods")));
        this.parent = parent;
    }

    private int perPage() {
        return Math.max(1, (height - 100) / ROW);
    }

    @Override
    protected void init() {
        mods = ModFiles.list(Main.gameFolder().resolve("mods"));
        int pages = Math.max(1, (mods.size() + perPage() - 1) / perPage());
        page = Math.min(page, pages - 1);
        int x = width / 2 - 160;
        int y = 36;
        for (ModFiles.ModFile mod : mods.subList(page * perPage(), Math.min(mods.size(), (page + 1) * perPage()))) {
            if (mod.squid()) {
                Button toggle = Button.builder(Component.literal(mod.enabled() ? "§a" + Lang.t("ON") : "§c" + Lang.t("OFF")), b -> toggle(mod))
                        .bounds(x + 256, y, 64, 20).build();
                addRenderableWidget(toggle);
                if (ModSettings.has(mod.id()) && running(mod)) {
                    addRenderableWidget(Button.builder(Component.literal(Lang.t("Settings")),
                            b -> minecraft.setScreenAndShow(new SettingsScreen(this, mod.name(), ModSettings.of(mod.id())))).bounds(x + 188, y, 64, 20).build());
                }
            }
            y += ROW;
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> turn(-1, pages)).bounds(width / 2 - 100, height - 52, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> turn(1, pages)).bounds(width / 2 + 80, height - 52, 20, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void turn(int by, int pages) {
        page = Math.floorMod(page + by, pages);
        rebuildWidgets();
    }

    private static boolean running(ModFiles.ModFile mod) {
        for (ModInfo loaded : Main.mods()) {
            if (loaded.id().equals(mod.id())) return true;
        }
        return false;
    }

    private void toggle(ModFiles.ModFile mod) {
        try {
            ModFiles.toggle(mod);
            if (mod.fromCode()) {
                message = mod.enabled() ? Lang.t("Turned off {0}.", mod.name()) : Lang.t("Turned on {0}! It starts in a second.", mod.name());
                messageColor = 0xFF55FF55;
            } else {
                message = mod.enabled() ? Lang.t("Restart the game to finish turning {0} off.", mod.name())
                        : Lang.t("Restart the game to finish turning {0} on.", mod.name());
                messageColor = 0xFFFFFF55;
            }
        } catch (IOException e) {
            message = Lang.t("Couldn't switch it: {0}", e.getMessage());
            messageColor = 0xFFFF5555;
        }
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("Mods"), width / 2, 12, 0xFFFFFFFF);
        int x = width / 2 - 160;
        int y = 36;
        if (mods.isEmpty()) g.centeredText(font, Lang.t("No mods yet. Add some in Kelp, or from the Store!"), width / 2, height / 2 - 10, 0xFFA0A0A0);
        for (ModFiles.ModFile mod : mods.subList(page * perPage(), Math.min(mods.size(), (page + 1) * perPage()))) {
            String title = mod.version().isEmpty() ? mod.name() : mod.name() + " " + mod.version();
            g.text(font, font.plainSubstrByWidth(title, 180), x, y + 1, mod.enabled() ? 0xFFFFFFFF : 0xFF808080);
            String note = !mod.squid() ? Lang.t("Not a Squid mod") : !mod.enabled() ? Lang.t("Off")
                    : running(mod) ? mod.description() : mod.fromCode() ? Lang.t("Starting...") : Lang.t("Starts when the game restarts");
            g.text(font, font.plainSubstrByWidth(note, 180), x, y + 12, 0xFF808080);
            y += ROW;
        }
        if (message != null) g.centeredText(font, font.plainSubstrByWidth(message, width - 20), width / 2, height - 64, messageColor);
    }

    @Override
    public void tick() {
        super.tick();
        // Mods built from code start and stop within a second, so look again every second
        if (++ticks % 20 == 0) rebuildWidgets();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
