package squidmods;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;
import squid.Main;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Squid > Mod Maker: make and change easy mods without leaving the game. It lists the easy mods in the mods folder
 * (the .java ones), and makes a new one from a name. Picking one opens it in {@link CodeScreen}.
 */
final class ModMakerScreen extends Screen {
    private final Screen parent;
    private EditBox name;
    private String typed = "";
    private int page;
    private String problem;

    ModMakerScreen(Screen parent) {
        super(Component.literal(Lang.t("Mod Maker")));
        this.parent = parent;
    }

    static Path modsFolder() {
        return Main.gameFolder().resolve("mods");
    }

    @Override
    protected void init() {
        int x = width / 2 - 150;
        name = new EditBox(font, x, 30, 228, 20, Component.literal(Lang.t("Name")));
        name.setHint(Component.literal(Lang.t("A new mod's name, like Rocket Boots")));
        name.setMaxLength(40);
        name.setValue(typed);
        name.setResponder(text -> typed = text);
        addRenderableWidget(name);
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Make it")), b -> make()).bounds(x + 232, 30, 68, 20).build());

        List<Path> mods = ModMaker.easyMods(modsFolder());
        int columns = 2;
        int rows = Math.max(1, (height - 120) / 22);
        int perPage = columns * rows;
        int pages = Math.max(1, (mods.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        List<Path> onPage = mods.subList(Math.min(mods.size(), page * perPage), Math.min(mods.size(), (page + 1) * perPage));
        for (int i = 0; i < onPage.size(); i++) {
            Path file = onPage.get(i);
            String shown = file.getFileName().toString();
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(shown, 140)),
                    b -> minecraft.setScreenAndShow(new CodeScreen(this, file))).bounds(x + (i % columns) * 152, 62 + (i / columns) * 22, 148, 20).build());
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = (page + pages - 1) % pages;
                rebuildWidgets();
            }).bounds(x, height - 52, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = (page + 1) % pages;
                rebuildWidgets();
            }).bounds(x + 280, height - 52, 20, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void make() {
        if (typed.isBlank()) {
            problem = Lang.t("Type a name for it first.");
            return;
        }
        try {
            Path file = ModMaker.create(modsFolder(), typed);
            typed = "";
            minecraft.setScreenAndShow(new CodeScreen(this, file));
        } catch (IOException e) {
            problem = Lang.t("Couldn't make it: {0}", e.getMessage());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("Mod Maker"), width / 2, 12, 0xFFFFFFFF);
        if (ModMaker.easyMods(modsFolder()).isEmpty()) {
            g.centeredText(font, Lang.t("No easy mods yet. Type a name and press Make it!"), width / 2, height / 2, 0xFFA0A0A0);
        }
        String note = problem != null ? problem : Lang.t("Pick a mod to change it. Saving runs it right away.");
        g.centeredText(font, note, width / 2, height - 40, problem != null ? 0xFFFF5555 : 0xFF808080);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
