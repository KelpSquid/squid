package squidskins;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pick your skin and cape, with a turning 3D preview of you. Skins and capes can be dropped onto the window,
 * looked up by a player's name, or painted.
 */
final class WardrobeScreen extends Screen {
    private final Screen parent;
    private String message;
    private int messageColor = 0xFFA0A0A0;

    WardrobeScreen(Screen parent) {
        super(Component.literal("Skin & Cape"));
        this.parent = parent;
    }

    private Wardrobe.Choice choice() {
        return Skins.choice(Skins.myId());
    }

    private void pick(Wardrobe.Choice choice) {
        try {
            Skins.choose(Skins.myId(), choice);
        } catch (IOException e) {
            say("Couldn't save that: " + e.getMessage(), 0xFFFF5555);
        }
        rebuildWidgets();
    }

    void say(String text, int color) {
        message = text;
        messageColor = color;
    }

    @Override
    protected void init() {
        int previewWidth = 110;
        int previewHeight = Math.min(180, height - 90);
        PlayerSkinWidget preview = new PlayerSkinWidget(previewWidth, previewHeight, minecraft.getEntityModels(), Skins::preview);
        preview.setX(width / 2 - 170);
        preview.setY((height - previewHeight) / 2);
        addRenderableWidget(preview);

        Wardrobe.Choice now = choice();
        int x = width / 2 - 40;
        int y = Math.max(28, height / 2 - 92);

        List<String> skins = new ArrayList<>(List.of(""));
        skins.addAll(Wardrobe.pictures(Skins.wardrobe.skins()));
        addRenderableWidget(Button.builder(Component.literal("Skin: " + (now.skin().isEmpty() ? "Yours" : shortName(now.skin()))),
                b -> pick(new Wardrobe.Choice(next(skins, now.skin()), now.slim(), now.cape()))).bounds(x, y, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Arms: " + (now.slim() ? "Slim" : "Wide")),
                b -> pick(new Wardrobe.Choice(now.skin(), !now.slim(), now.cape()))).bounds(x, y + 24, 200, 20).build());

        List<String> capes = new ArrayList<>(List.of(""));
        capes.addAll(Wardrobe.BUILT_IN_CAPES);
        for (String file : Wardrobe.pictures(Skins.wardrobe.capes())) capes.add("file:" + file);
        addRenderableWidget(Button.builder(Component.literal("Cape: " + capeName(now.cape())),
                b -> pick(new Wardrobe.Choice(now.skin(), now.slim(), next(capes, now.cape())))).bounds(x, y + 48, 200, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Get Skin by Name..."),
                b -> minecraft.setScreenAndShow(new NameScreen(this))).bounds(x, y + 80, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Paint Skin"),
                b -> minecraft.setScreenAndShow(PaintScreen.skin(this, now))).bounds(x, y + 104, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Paint Cape"),
                b -> minecraft.setScreenAndShow(PaintScreen.cape(this, now))).bounds(x + 102, y + 104, 98, 20).build());
        // Pictures can be picked with the computer's own file window, or dropped onto the game. Either way
        // they're copied into Kelp's skins and capes folders, so the original can be deleted from Downloads.
        addRenderableWidget(Button.builder(Component.literal("Browse..."),
                b -> FilePicker.pickPictures(this::onFilesDrop)).bounds(x, y + 128, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open Folder"),
                b -> open(Skins.wardrobe.skins())).bounds(x + 102, y + 128, 98, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    /** The one after current in the list, going back to the start after the last. */
    private static String next(List<String> list, String current) {
        int at = list.indexOf(current);
        return list.get((at + 1) % list.size());
    }

    private static String shortName(String file) {
        String name = file.replaceAll("(?i)\\.png$", "");
        return name.length() > 18 ? name.substring(0, 17) + "..." : name;
    }

    private static String capeName(String cape) {
        if (cape.isEmpty()) return "None";
        if (cape.startsWith("file:")) return shortName(cape.substring(5));
        return Character.toUpperCase(cape.charAt(0)) + cape.substring(1);
    }

    /** Pictures dropped onto the window: square ones become skins, wide ones capes, and get picked right away. */
    @Override
    public void onFilesDrop(List<Path> files) {
        for (Path file : files) {
            try {
                Wardrobe.Kind[] kind = new Wardrobe.Kind[1];
                String name = Skins.wardrobe.bringIn(file, kind);
                Wardrobe.Choice now = choice();
                if (kind[0] == Wardrobe.Kind.SKIN) {
                    pick(new Wardrobe.Choice(name, now.slim(), now.cape()));
                    say("Added the skin " + shortName(name) + "!", 0xFF55FF55);
                } else {
                    pick(new Wardrobe.Choice(now.skin(), now.slim(), "file:" + name));
                    say("Added the cape " + shortName(name) + "!", 0xFF55FF55);
                }
            } catch (IOException e) {
                say(e.getMessage(), 0xFFFF5555);
            }
        }
    }

    private void open(Path folder) {
        try {
            Files.createDirectories(folder);
            String os = System.getProperty("os.name").toLowerCase();
            String[] command = os.contains("win") ? new String[] {"explorer", folder.toString()}
                    : os.contains("mac") ? new String[] {"open", folder.toString()} : new String[] {"xdg-open", folder.toString()};
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            say("Couldn't open the folder: " + e.getMessage(), 0xFFFF5555);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, "Skin & Cape", width / 2, 10, 0xFFFFFFFF);
        int x = width / 2 - 40;
        int y = Math.max(28, height / 2 - 92) + 156;
        g.text(font, "Browse, or drop pictures here.", x, y, 0xFFA0A0A0);
        g.text(font, "Only you see them for now.", x, y + 11, 0xFF808080);
        if (message != null) g.centeredText(font, font.plainSubstrByWidth(message, width - 20), width / 2, height - 42, messageColor);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
