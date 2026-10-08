package squidpaint;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Your texture packs: make new ones, pick the one you're painting into, give it an icon, switch it on or off, and
 * save it as a .zip to give to a friend. On the left are your packs; on the right, what you can do with the one you're
 * painting into.
 */
final class PackScreen extends Screen {
    private final Screen back;
    private EditBox newName;
    private String typed = "";
    private int page;
    private String message;
    private int messageColor = 0xFFA0A0A0;

    PackScreen(Object back) {
        super(Component.literal(Lang.t("Texture Packs")));
        this.back = back instanceof Screen s ? s : null;
    }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        int right = width / 2 + 10;
        String current = Paint.packName();

        // Your packs, a page at a time
        List<String> packs = Paint.packs();
        int rows = Math.max(1, (height - 32 - 60) / 22);
        int pages = Math.max(1, (packs.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        List<String> onPage = packs.subList(page * rows, Math.min(packs.size(), (page + 1) * rows));
        for (int i = 0; i < onPage.size(); i++) {
            String name = onPage.get(i);
            Button button = Button.builder(Component.literal((name.equals(current) ? "> " : "") + name), b -> use(name))
                    .bounds(left, 32 + i * 22, 140, 20).build();
            button.active = !name.equals(current);
            addRenderableWidget(button);
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> turn(-1, pages)).bounds(width / 2 - 126, height - 28, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> turn(1, pages)).bounds(width / 2 + 106, height - 28, 20, 20).build());
        }

        // What to do with the pack you're painting into
        int y = 32;
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Paint Blocks")),
                b -> minecraft.setScreenAndShow(new BlockPickScreen(this))).bounds(right, y, 140, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Swap Sounds")),
                b -> minecraft.setScreenAndShow(new SoundSwapScreen(this))).bounds(right, y + 22, 140, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Icon from a Picture...")),
                b -> minecraft.setScreenAndShow(new BlockPickScreen(this, texture -> {
                    try {
                        Paint.makeIcon(current, texture);
                        say(Lang.t("New icon for {0}!", current), 0xFF55FF55);
                    } catch (IOException e) {
                        say(Lang.t("Couldn't make the icon: {0}", e.getMessage()), 0xFFFF5555);
                    }
                    minecraft.setScreenAndShow(this);
                }))).bounds(right, y + 44, 140, 20).build());
        boolean on = Paint.enabled(current);
        addRenderableWidget(Button.builder(Component.literal(on ? Lang.t("Switched On") : Lang.t("Switched Off")), b -> {
            if (!Files.isDirectory(Paint.folder(current))) {
                say(Lang.t("Paint something into it first."), 0xFFFFFF55);
                return;
            }
            Paint.setEnabled(current, !on);
            rebuildWidgets();
        }).bounds(right, y + 66, 140, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Save as .zip")), b -> {
            try {
                Path zip = Paint.saveZip(current);
                say(Lang.t("Saved {0}", zip.toString()), 0xFF55FF55);
            } catch (IOException e) {
                say(Lang.t("Couldn't save it: {0}", e.getMessage()), 0xFFFF5555);
            }
        }).bounds(right, y + 88, 140, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Open Folder")), b -> {
            try {
                Files.createDirectories(Paint.folder(current));
                com.mojang.blaze3d.Blaze3D.openPath(Paint.folder(current));
            } catch (Exception e) {
                say(Lang.t("Couldn't open the folder: {0}", e.getMessage()), 0xFFFF5555);
            }
        }).bounds(right, y + 110, 140, 20).build());

        // A new pack: type its name
        newName = new EditBox(font, left, height - 52, 236, 20, Component.literal(Lang.t("New pack name")));
        newName.setHint(Component.literal(Lang.t("New pack name")));
        newName.setMaxLength(32);
        newName.setValue(typed);
        newName.setResponder(text -> typed = text);
        addRenderableWidget(newName);
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Make")), b -> make()).bounds(left + 240, height - 52, 60, 20).build());

        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void turn(int by, int pages) {
        page = Math.floorMod(page + by, pages);
        rebuildWidgets();
    }

    private void use(String name) {
        try {
            Paint.usePack(name);
            say(Lang.t("Painting into {0} now.", name), 0xFF55FF55);
        } catch (IOException e) {
            say(Lang.t("Couldn't switch: {0}", e.getMessage()), 0xFFFF5555);
        }
        rebuildWidgets();
    }

    private void make() {
        String name = typed.strip();
        if (!Paint.goodName(name)) {
            say(Lang.t("A pack's name can have letters, numbers and spaces (up to 32)."), 0xFFFF5555);
            return;
        }
        try {
            Paint.newPack(name);
            Paint.usePack(name);
            typed = "";
            say(Lang.t("Made {0}! Paint blocks into it, then switch it on.", name), 0xFF55FF55);
        } catch (IOException e) {
            say(Lang.t("Couldn't make it: {0}", e.getMessage()), 0xFFFF5555);
        }
        rebuildWidgets();
    }

    private void say(String text, int color) {
        message = text;
        messageColor = color;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("Texture Packs"), width / 2, 8, 0xFFFFFFFF);
        String line = message != null ? message : Lang.t("Painting into {0}. Pick another on the left, or make a new one.", Paint.packName());
        g.centeredText(font, font.plainSubstrByWidth(line, width - 20), width / 2, 19, message != null ? messageColor : 0xFF808080);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(back);
    }
}
