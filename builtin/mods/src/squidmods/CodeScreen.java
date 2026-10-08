package squidmods;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import squid.Lang;
import squid.LiveReload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Mod Maker's code editor: one easy mod's code, changed in the game. Save (or Ctrl+S) writes it, and Squid's live
 * reload builds it and swaps it in a second later; what happened ("Reloaded!", or the mistake and its line) shows
 * under the code. Leaving with changes saves them first, so nothing typed is lost.
 */
final class CodeScreen extends Screen {
    private static final int TAB = 258; // GLFW's Tab key
    private static final int S = 83;

    private final Screen parent;
    private final Path file;
    private MultiLineEditBox editor;
    private String code;
    private String saved; // the code as it is in the file
    private long savedAt; // when Save was last pressed, to know which live reload result is about it
    private String problem;

    CodeScreen(Screen parent, Path file) {
        super(Component.literal(file.getFileName().toString()));
        this.parent = parent;
        this.file = file;
        try {
            code = ModMaker.read(file);
        } catch (IOException e) {
            code = "";
            problem = Lang.t("Couldn't open it: {0}", e.getMessage());
        }
        saved = code;
    }

    @Override
    protected void init() {
        editor = MultiLineEditBox.builder().setX(8).setY(22).setPlaceholder(Component.literal(Lang.t("Your mod's code")))
                .build(font, width - 16, height - 22 - 56, Component.literal(Lang.t("Code")));
        editor.setCharacterLimit(200_000);
        editor.setValue(code);
        editor.setValueListener(text -> code = text);
        addRenderableWidget(editor);
        setInitialFocus(editor);
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Save")), b -> save()).bounds(width / 2 - 100, height - 26, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Back")), b -> onClose()).bounds(width / 2 + 2, height - 26, 98, 20).build());
    }

    private void save() {
        if (problem != null && saved.isEmpty() && code.isEmpty()) return; // it couldn't be opened: don't wipe it
        try {
            Files.writeString(file, code, StandardCharsets.UTF_8);
            saved = code;
            savedAt = System.currentTimeMillis();
            problem = null;
        } catch (IOException e) {
            problem = Lang.t("Couldn't save: {0}", e.getMessage());
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == S && event.hasControlDown()) {
            save();
            return true;
        }
        if (event.key() == TAB && getFocused() == editor) { // Tab types spaces, like in a code editor
            for (int i = 0; i < 4; i++) editor.charTyped(new CharacterEvent(' '));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.text(font, file.getFileName().toString() + (code.equals(saved) ? "" : " *"), 8, 8, 0xFFFFFFFF);
        String hint = Lang.t("Ctrl+S saves. Saving runs it right away.");
        g.text(font, hint, width - 8 - font.width(hint), 8, 0xFF808080);
        String status;
        int color;
        LiveReload.Result result = LiveReload.last(file);
        if (problem != null) {
            status = problem;
            color = 0xFFFF5555;
        } else if (savedAt == 0) {
            status = "";
            color = 0;
        } else if (result != null && result.when() >= savedAt) {
            status = result.message();
            color = result.worked() ? 0xFF55FF55 : 0xFFFF5555;
        } else if (System.currentTimeMillis() - savedAt < 15_000) {
            status = Lang.t("Saved. Squid is building it...");
            color = 0xFFA0A0A0;
        } else {
            status = Lang.t("Saved.");
            color = 0xFFA0A0A0;
        }
        // Up to two lines, so a mistake's line number and what's wrong both fit
        String first = font.plainSubstrByWidth(status, width - 16);
        String second = font.plainSubstrByWidth(status.substring(first.length()).strip(), width - 16);
        g.text(font, first, 8, height - 52, color);
        g.text(font, second, 8, height - 41, color);
    }

    @Override
    public void onClose() {
        if (!code.equals(saved)) save();
        minecraft.setScreenAndShow(parent);
    }
}
