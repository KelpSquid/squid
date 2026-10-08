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
    private boolean showCommands;
    private int commandPage;
    private final java.util.List<Button> snippetButtons = new java.util.ArrayList<>();
    private final java.util.List<Button> pageButtons = new java.util.ArrayList<>();

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
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Save")), b -> save()).bounds(width / 2 - 150, height - 26, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Commands")), b -> {
            showCommands = !showCommands;
            layOutCommands();
        }).bounds(width / 2 - 49, height - 26, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Back")), b -> onClose()).bounds(width / 2 + 52, height - 26, 98, 20).build());

        // The Commands panel: click one and its line of code is typed in where the cursor is
        snippetButtons.clear();
        for (String[] snippet : ModMaker.SNIPPETS) {
            Button button = Button.builder(Component.literal(snippet[0]), b -> type(snippet[1])).build();
            button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(snippet[1])));
            snippetButtons.add(addRenderableWidget(button));
        }
        pageButtons.clear();
        pageButtons.add(addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            commandPage--;
            layOutCommands();
        }).size(20, 20).build()));
        pageButtons.add(addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            commandPage++;
            layOutCommands();
        }).size(20, 20).build()));
        layOutCommands();
    }

    private static final int PANEL = 110; // how wide the Commands panel is

    /** Shows or hides the Commands panel (the code gets narrower to make room), and puts its buttons in place. */
    private void layOutCommands() {
        editor.setWidth(showCommands ? width - 16 - PANEL - 4 : width - 16);
        int x = width - 8 - PANEL;
        int top = 34;
        int perPage = Math.max(1, (height - 56 - top - 24) / 22);
        int pages = (snippetButtons.size() + perPage - 1) / perPage;
        commandPage = Math.floorMod(commandPage, pages);
        for (int i = 0; i < snippetButtons.size(); i++) {
            Button button = snippetButtons.get(i);
            int onPage = i - commandPage * perPage;
            button.visible = showCommands && onPage >= 0 && onPage < perPage;
            button.setRectangle(PANEL, 20, x, top + onPage * 22);
        }
        int pagerY = top + perPage * 22;
        pageButtons.get(0).setPosition(x, pagerY);
        pageButtons.get(1).setPosition(x + PANEL - 20, pagerY);
        for (Button pager : pageButtons) pager.visible = showCommands && pages > 1;
    }

    /** Types a line of code in where the cursor is, as if it was typed on the keyboard. */
    private void type(String line) {
        setFocused(editor);
        line.codePoints().forEach(c -> editor.charTyped(new CharacterEvent(c)));
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
        if (showCommands) {
            g.text(font, font.plainSubstrByWidth(Lang.t("Click in start(), then pick:"), PANEL), width - 8 - PANEL, 22, 0xFFA0A0A0);
        }
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
