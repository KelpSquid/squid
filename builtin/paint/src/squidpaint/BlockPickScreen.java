package squidpaint;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import squid.Lang;

import java.util.List;
import java.util.Locale;

/**
 * Picks the block to paint: every block texture, searchable by name ("diamond", "oak"...), a page at a time.
 * Ones you've painted have a * in front.
 */
final class BlockPickScreen extends Screen {
    private final Screen back;
    private List<Identifier> all;
    private boolean items; // blocks, or items (swords, food, tools...)
    private EditBox search;
    private String filter = "";
    private int page;

    BlockPickScreen(Object back) {
        super(Component.literal(Lang.t("Block Painter")));
        this.back = back instanceof Screen s ? s : null;
    }

    @Override
    protected void init() {
        if (all == null) all = items ? Paint.itemTextures() : Paint.blockTextures();
        int x = width / 2 - 150;
        addRenderableWidget(Button.builder(Component.literal(items ? Lang.t("Items") : Lang.t("Blocks")), b -> {
            items = !items;
            all = null;
            page = 0;
            rebuildWidgets();
        }).bounds(x, 30, 60, 20).build());
        search = new EditBox(font, x + 64, 30, 236, 20, Component.literal(Lang.t("Search")));
        search.setHint(Component.literal(Lang.t("Search blocks, like \"diamond\" or \"oak\"")));
        search.setValue(filter);
        search.setResponder(text -> {
            filter = text;
            page = 0;
            rebuildWidgets();
            setFocused(search);
        });
        addRenderableWidget(search);
        setInitialFocus(search);

        List<Identifier> shown = shown();
        int columns = 3;
        int rows = Math.max(1, (height - 120) / 22);
        int perPage = columns * rows;
        int pages = Math.max(1, (shown.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        int y = 58;
        List<Identifier> onPage = shown.subList(Math.min(shown.size(), page * perPage), Math.min(shown.size(), (page + 1) * perPage));
        for (int i = 0; i < onPage.size(); i++) {
            Identifier texture = onPage.get(i);
            String name = (Paint.painted(texture) ? "* " : "") + nice(texture);
            int bx = x + (i % columns) * 102;
            int by = y + (i / columns) * 22;
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(name, 92)),
                    b -> minecraft.setScreenAndShow(new BlockPaintScreen(this, texture))).bounds(bx, by, 98, 20).build());
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

    /** "block/diamond_ore" is shown as "diamond ore", "item/apple" as "apple". */
    static String nice(Identifier texture) {
        String path = texture.getPath();
        String name = path.startsWith("block/") ? path.substring("block/".length()) : path.startsWith("item/") ? path.substring("item/".length()) : path;
        return name.replace('_', ' ');
    }

    private List<Identifier> shown() {
        String wanted = filter.toLowerCase(Locale.ROOT).strip().replace(' ', '_');
        if (wanted.isEmpty()) return all;
        return all.stream().filter(t -> t.getPath().contains(wanted)).toList();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("Block Painter"), width / 2, 12, 0xFFFFFFFF);
        if (shown().isEmpty()) g.centeredText(font, items ? Lang.t("No items with that name.") : Lang.t("No blocks with that name."), width / 2, height / 2, 0xFFA0A0A0);
        g.centeredText(font, Lang.t("Pick a block to paint. A * means you've painted it."), width / 2, height - 40, 0xFF808080);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(back);
    }
}
