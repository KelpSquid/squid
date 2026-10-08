package squidstore;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;
import squid.Main;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The store: tabs for Samuel's favorites, mods and resource packs, a page of items with an Install button each,
 * and arrows to go through the pages.
 */
final class StoreScreen extends Screen {
    private enum Tab {
        PICKS("Dev-picked"), MODS("Mods"), PACKS("Packs"), CAPES("Capes");

        final String label; // in English: translated where it's shown

        Tab(String label) {
            this.label = label;
        }
    }

    private static final int ROW = 36;
    private static final int TOP = 58; // where the first item starts, under the title and tabs

    private final Screen parent;
    private final Path gameFolder = Main.gameFolder();
    private final Map<String, String> state = new ConcurrentHashMap<>(); // item id -> "Installing...", "Installed!" or a problem
    private volatile List<Catalog.Item> items;
    private volatile String problem;
    private volatile String notice;
    private Tab tab = Tab.PICKS;
    private int page;

    StoreScreen(Screen parent) {
        super(Component.literal(Lang.t("Squid Store")));
        this.parent = parent;
        Thread load = new Thread(() -> {
            try {
                List<Catalog.Item> all = new ArrayList<>();
                for (Catalog.Item item : Catalog.load(Catalog.URL)) {
                    if (item.worksOn(Main.minecraftVersion())) all.add(item);
                }
                items = all;
            } catch (Exception e) {
                problem = Lang.t("Couldn't reach the store. Check your internet and try again.");
            }
            minecraft.execute(this::rebuildWidgets);
        }, "squid store");
        load.setDaemon(true);
        load.start();
    }

    /** The items on the open tab. Dev-picked shows Samuel's favorites from every category. */
    private List<Catalog.Item> shown() {
        List<Catalog.Item> list = new ArrayList<>();
        if (items == null) return list;
        for (Catalog.Item item : items) {
            boolean fits = switch (tab) {
                case PICKS -> item.devPicked();
                case MODS -> item.isMod();
                case PACKS -> "resourcepack".equals(item.type());
                case CAPES -> item.isCape();
            };
            if (fits) list.add(item);
        }
        return list;
    }

    private int perPage() {
        return Math.max(1, (height - TOP - 60) / ROW);
    }

    /** Which items are installed, worked out when the buttons are made (looking inside jars every frame would be slow). */
    private final java.util.Set<String> installedNow = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> updatesNow = ConcurrentHashMap.newKeySet(); // installed, but the Store has a newer one

    @Override
    protected void init() {
        installedNow.clear();
        updatesNow.clear();
        if (items != null) {
            for (Catalog.Item item : items) {
                if (Installer.installed(item, gameFolder)) installedNow.add(item.id());
                if (Installer.updateAvailable(item, gameFolder)) updatesNow.add(item.id());
            }
        }
        // Tabs along the top. The open one is greyed out, like a pressed button.
        int tabX = width / 2 - 155;
        for (Tab t : Tab.values()) { // four tabs of 74, side by side
            Button button = addRenderableWidget(Button.builder(Component.literal(Lang.t(t.label)), b -> {
                tab = t;
                page = 0;
                rebuildWidgets();
            }).bounds(tabX, 28, 74, 20).build());
            button.active = t != tab;
            tabX += 79;
        }

        List<Catalog.Item> list = shown();
        int pages = Math.max(1, (list.size() + perPage() - 1) / perPage());
        page = Math.min(page, pages - 1);
        int y = TOP;
        for (int i = page * perPage(); i < Math.min(list.size(), (page + 1) * perPage()); i++) {
            Catalog.Item item = list.get(i);
            String now = state.get(item.id());
            boolean installed = installedNow.contains(item.id());
            String label = now != null ? now : updatesNow.contains(item.id()) ? Lang.t("Update") : installed ? Lang.t("Reinstall") : Lang.t("Install");
            Button install = addRenderableWidget(Button.builder(Component.literal(label), b -> install(item))
                    .bounds(width / 2 + 85, y + 6, 70, 20).build());
            install.active = now == null; // anything can always be installed again
            y += ROW;
        }

        if (pages > 1) {
            Button back = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page--;
                rebuildWidgets();
            }).bounds(width / 2 - 155, height - 52, 20, 20).build());
            back.active = page > 0;
            Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page++;
                rebuildWidgets();
            }).bounds(width / 2 + 135, height - 52, 20, 20).build());
            next.active = page < pages - 1;
        }
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void install(Catalog.Item item) {
        state.put(item.id(), Lang.t("Installing..."));
        rebuildWidgets();
        Thread worker = new Thread(() -> {
            try {
                Installer.install(item, gameFolder);
                state.put(item.id(), Lang.t("Installed!"));
                notice = item.isMod() ? Lang.t("Restart the game to start {0}.", item.name())
                        : item.isCape() ? Lang.t("Wear {0} from Options > Skin Customization > Squid Skin & Cape.", item.name())
                        : Lang.t("Turn on {0} in Options > Resource Packs.", item.name());
            } catch (Exception e) {
                state.remove(item.id());
                notice = Lang.t("Couldn't install {0}: {1}", item.name(),
                        e.getMessage() != null ? e.getMessage() : Lang.t("something went wrong"));
            }
            minecraft.execute(this::rebuildWidgets);
        }, "squid store install");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("Squid Store"), width / 2, 12, 0xFFFFFFFF);

        if (items == null) {
            g.centeredText(font, problem != null ? problem : Lang.t("Loading the store..."), width / 2, height / 2 - 4,
                    problem != null ? 0xFFFF5555 : 0xFFA0A0A0);
            return;
        }
        List<Catalog.Item> list = shown();
        if (list.isEmpty()) {
            String empty = tab == Tab.PICKS ? Lang.t("No dev picks yet.") : Lang.t("Nothing here yet. Check back soon!");
            g.centeredText(font, empty, width / 2, height / 2 - 4, 0xFFA0A0A0);
        }
        int rowLeft = width / 2 - 155;
        int y = TOP;
        for (int i = page * perPage(); i < Math.min(list.size(), (page + 1) * perPage()); i++) {
            Catalog.Item item = list.get(i);
            g.fill(rowLeft - 4, y, width / 2 + 159, y + ROW - 4, 0x60000000);
            // Its icon on the left, when it has one; the words move over to make room
            net.minecraft.resources.Identifier icon = StoreIcons.of(item);
            int left = icon != null ? rowLeft + 24 : rowLeft;
            int textWidth = icon != null ? 211 : 235; // up to the Install button
            if (icon != null) g.blit(icon, rowLeft, y + 3, rowLeft + 20, y + 23, 0, 1, 0, 1);
            // The spaces stay outside the translations (language files trim them)
            String kind = tab == Tab.PICKS ? " " + (item.isMod() ? Lang.t("[Mod]") : item.isCape() ? Lang.t("[Cape]") : Lang.t("[Pack]")) : "";
            String named = item.name() + kind;
            String heading = item.author().isEmpty() ? named : Lang.t("{0} by {1}", named, item.author());
            boolean update = updatesNow.contains(item.id());
            String tag = update ? " " + Lang.t("Update available") : installedNow.contains(item.id()) ? " " + Lang.t("Installed") : "";
            String shownHeading = font.plainSubstrByWidth(heading, textWidth - font.width(tag));
            g.text(font, shownHeading, left, y + 5, 0xFFFFFFFF);
            if (!tag.isEmpty()) g.text(font, tag, left + font.width(shownHeading), y + 5, update ? 0xFFFFFF55 : 0xFF55FF55);
            g.text(font, fit(item.description(), textWidth), left, y + 17, 0xFFA0A0A0);
            y += ROW;
        }
        int pages = Math.max(1, (list.size() + perPage() - 1) / perPage());
        if (pages > 1) g.centeredText(font, Lang.t("Page {0} of {1}", page + 1, pages), width / 2, height - 46, 0xFFA0A0A0);
        if (notice != null) g.centeredText(font, notice, width / 2, height - 40 - (pages > 1 ? 12 : 0), 0xFFFFFF55);
    }

    /** Cuts text down with "..." until it fits. */
    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
