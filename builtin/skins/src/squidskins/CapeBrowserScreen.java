package squidskins;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import squid.Main;
import squidstore.Catalog;
import squidstore.Installer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Every cape you can wear, as pictures: Official (Mojang's, loaded from Mojang), Community (approved capes from the
 * Squid Store) and Yours (the Kelp and Squid capes, and ones you added or painted). Click one to wear it.
 */
final class CapeBrowserScreen extends Screen {
    private enum Tab {
        OFFICIAL("Official"), COMMUNITY("Community"), YOURS("Yours");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    /** One cape in the grid. cape is how a choice names it; a Store cape that isn't here yet also has its item. */
    private record Entry(String name, String cape, Catalog.Item storeItem) {
    }

    private static final int CELL_W = 46;
    private static final int CELL_H = 64;
    private static final int PICTURE_W = 30; // a cape's back is 10x16 pixels, shown 3 times as big
    private static final int PICTURE_H = 48;

    private final WardrobeScreen parent;
    private Tab tab = Tab.YOURS;
    private int page;
    private String message;
    private int messageColor = 0xFFA0A0A0;
    private volatile List<OfficialCapes.Cape> official;
    private volatile List<Catalog.Item> community;
    private volatile String loadProblem;
    private final java.util.Set<String> previewing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    CapeBrowserScreen(WardrobeScreen parent) {
        super(Component.literal("Capes"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int previewHeight = Math.min(150, height - 100);
        PlayerSkinWidget preview = new PlayerSkinWidget(80, previewHeight, minecraft.getEntityModels(), Skins::preview);
        preview.setX(12);
        preview.setY((height - previewHeight) / 2);
        addRenderableWidget(preview);

        int tabX = width / 2 - 150;
        for (Tab t : Tab.values()) {
            Button button = Button.builder(Component.literal(t.label), b -> show(t)).bounds(tabX, 24, 98, 20).build();
            button.active = t != tab;
            addRenderableWidget(button);
            tabX += 102;
        }
        int pages = pages();
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> turn(-1)).bounds(width / 2 - 100, height - 52, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> turn(1)).bounds(width / 2 + 80, height - 52, 20, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
        if (tab == Tab.OFFICIAL && official == null) loadOfficial();
        if (tab == Tab.COMMUNITY && community == null) loadCommunity();
    }

    private void show(Tab t) {
        tab = t;
        page = 0;
        message = null;
        rebuildWidgets();
    }

    private void turn(int by) {
        page = Math.floorMod(page + by, pages());
        rebuildWidgets();
    }

    // ---- What's in each tab ----

    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        switch (tab) {
            case OFFICIAL -> {
                if (official != null) for (OfficialCapes.Cape cape : official) entries.add(new Entry(cape.name(), cape.choice(), null));
            }
            case COMMUNITY -> {
                if (community != null) {
                    for (Catalog.Item item : community) {
                        boolean here = Files.exists(Skins.wardrobe.capes().resolve(item.file()));
                        entries.add(new Entry(item.name(), "file:" + item.file(), here ? null : item));
                    }
                }
            }
            case YOURS -> {
                entries.add(new Entry("None", "", null));
                for (String builtIn : Wardrobe.BUILT_IN_CAPES) {
                    entries.add(new Entry(Character.toUpperCase(builtIn.charAt(0)) + builtIn.substring(1), builtIn, null));
                }
                for (String file : Wardrobe.pictures(Skins.wardrobe.capes())) {
                    entries.add(new Entry(file.replaceAll("(?i)\\.png$", ""), "file:" + file, null));
                }
            }
        }
        return entries;
    }

    private int columns() {
        return Math.max(1, (width - 130) / CELL_W);
    }

    private int rows() {
        return Math.max(1, (height - 110) / CELL_H);
    }

    private int pages() {
        int perPage = columns() * rows();
        return Math.max(1, (entries().size() + perPage - 1) / perPage);
    }

    private int gridLeft() {
        return 110 + Math.max(0, (width - 120 - columns() * CELL_W) / 2);
    }

    // ---- Loading the lists ----

    private void loadOfficial() {
        background(() -> official = OfficialCapes.load());
    }

    private void loadCommunity() {
        background(() -> community = Catalog.load(Catalog.URL).stream().filter(Catalog.Item::isCape).toList());
    }

    private interface Work {
        void run() throws Exception;
    }

    /** Does something slow (like loading a list from the internet) without stopping the game, then redraws. */
    private void background(Work work) {
        Thread thread = new Thread(() -> {
            try {
                work.run();
                loadProblem = null;
            } catch (Exception e) {
                loadProblem = e.getMessage();
            }
            minecraft.execute(this::rebuildWidgets);
        }, "Squid cape browser");
        thread.setDaemon(true);
        thread.start();
    }

    /** A Store cape's picture, to look at before getting it: downloaded once, and kept only if its fingerprint matches. */
    private Identifier storePreview(Catalog.Item item) {
        Path file = Skins.wardrobe.storePreviews().resolve(item.sha256().toLowerCase(Locale.ROOT) + ".png");
        if (Files.exists(file)) return Skins.picture(file);
        if (previewing.add(item.sha256())) {
            background(() -> {
                Files.createDirectories(file.getParent());
                HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
                HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(item.url())).timeout(Duration.ofSeconds(30)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                byte[] picture = response.body();
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(picture));
                if (response.statusCode() == 200 && sha.equalsIgnoreCase(item.sha256())) Files.write(file, picture);
            });
        }
        return null;
    }

    // ---- Picking ----

    private void pick(Entry entry) {
        Wardrobe.Choice now = Skins.choice(Skins.myId());
        try {
            if (entry.storeItem() != null) {
                Installer.install(entry.storeItem(), Main.gameFolder()); // checks its fingerprint, like the Store does
            }
            Skins.choose(Skins.myId(), now.withCape(entry.cape()));
            if (OfficialCapes.isOfficial(entry.cape())) {
                say("Wearing " + entry.name() + ". You don't own it, so others will see a tag next to your name.", 0xFFFFFF55);
            } else {
                say(entry.cape().isEmpty() ? "No cape." : "Wearing " + entry.name() + "!", 0xFF55FF55);
            }
        } catch (IOException e) {
            say("Couldn't get it: " + e.getMessage(), 0xFFFF5555);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        rebuildWidgets();
    }

    private void say(String text, int color) {
        message = text;
        messageColor = color;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        Entry entry = entryAt((int) event.x(), (int) event.y());
        if (entry != null) {
            pick(entry);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private Entry entryAt(int mouseX, int mouseY) {
        List<Entry> entries = entries();
        int columns = columns();
        int first = page * columns * rows();
        for (int i = first; i < Math.min(entries.size(), first + columns * rows()); i++) {
            int x = gridLeft() + ((i - first) % columns) * CELL_W;
            int y = 52 + ((i - first) / columns) * CELL_H;
            if (mouseX >= x && mouseX < x + CELL_W - 4 && mouseY >= y && mouseY < y + CELL_H - 4) return entries.get(i);
        }
        return null;
    }

    // ---- Drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, "Capes", width / 2, 10, 0xFFFFFFFF);
        String wearing = Skins.choice(Skins.myId()).cape();
        List<Entry> entries = entries();
        int columns = columns();
        int first = page * columns * rows();
        for (int i = first; i < Math.min(entries.size(), first + columns * rows()); i++) {
            Entry entry = entries.get(i);
            int x = gridLeft() + ((i - first) % columns) * CELL_W;
            int y = 52 + ((i - first) / columns) * CELL_H;
            boolean over = mouseX >= x && mouseX < x + CELL_W - 4 && mouseY >= y && mouseY < y + CELL_H - 4;
            boolean worn = entry.cape().equals(wearing) && entry.storeItem() == null;
            g.fill(x, y, x + CELL_W - 4, y + CELL_H - 4, worn ? 0xFF2E7D32 : over ? 0x80FFFFFF : 0x60000000);
            int px = x + (CELL_W - 4 - PICTURE_W) / 2;
            int py = y + 3;
            Identifier picture = entry.cape().isEmpty() ? null
                    : entry.storeItem() != null ? storePreview(entry.storeItem()) : Skins.capePicture(entry.cape());
            if (picture != null) {
                // The cape's back: pixels 1-11 across and 1-17 down of a 64x32 cape (each frame, for animated ones)
                g.blit(picture, px, py, px + PICTURE_W, py + PICTURE_H, 1 / 64f, 11 / 64f, 1 / 32f, 17 / 32f);
            } else {
                g.fill(px, py, px + PICTURE_W, py + PICTURE_H, 0xFF303030);
                g.centeredText(font, entry.cape().isEmpty() ? "-" : "...", px + PICTURE_W / 2, py + PICTURE_H / 2 - 4, 0xFF808080);
            }
            String name = font.plainSubstrByWidth(entry.name(), CELL_W - 6);
            g.centeredText(font, name, x + (CELL_W - 4) / 2, y + PICTURE_H + 6, entry.storeItem() != null ? 0xFFA0A0A0 : 0xFFFFFFFF);
        }

        String hint = switch (tab) {
            case OFFICIAL -> official == null ? (loadProblem != null ? "Couldn't load them: " + loadProblem : "Loading from Mojang...")
                    : "Mojang's capes. Wearing one you don't own shows a tag by your name.";
            case COMMUNITY -> community == null ? (loadProblem != null ? "Couldn't load them: " + loadProblem : "Loading the Store...")
                    : community.isEmpty() ? "No community capes yet." : "Approved capes from the Squid Store. Click one to get it and wear it.";
            case YOURS -> "Yours: drop pictures on the wardrobe, or paint one.";
        };
        if (pages() > 1) g.centeredText(font, (page + 1) + " / " + pages(), width / 2, height - 46, 0xFFA0A0A0);
        g.centeredText(font, font.plainSubstrByWidth(message != null ? message : hint, width - 20), width / 2, height - 64,
                message != null ? messageColor : 0xFF808080);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
