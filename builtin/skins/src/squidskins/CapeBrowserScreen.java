package squidskins;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import squid.Lang;
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
 * Every cape you can wear, as pictures: Official (a slot for every vanilla cape; press Download on one to get its picture
 * from Mojang), Community (approved capes from the Squid Store) and Yours (the Kelp and Squid capes, and ones you added
 * or painted). Click one to wear it.
 */
final class CapeBrowserScreen extends Screen {
    private enum Tab {
        OFFICIAL("Official"), COMMUNITY("Community"), YOURS("Yours");

        final String label; // in English: translated where it's shown

        Tab(String label) {
            this.label = label;
        }
    }

    /**
     * One cape in the grid. cape is how a choice names it; a Store cape that isn't here yet also has its item, and an
     * official one its place in the list.
     */
    private record Entry(String name, String cape, Catalog.Item storeItem, OfficialCapes.Cape official) {
        Entry(String name, String cape, Catalog.Item storeItem) {
            this(name, cape, storeItem, null);
        }

        /** An official cape whose picture hasn't been downloaded from Mojang yet. */
        boolean needsDownload() {
            return official != null && !OfficialCapes.downloaded(Skins.wardrobe.officialCapes(), official.hash());
        }
    }

    private static final int CELL_W = 46;
    private static final int OFFICIAL_CELL_W = 58; // wider, so "Download" fits
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
    private boolean officialRefreshed;
    /** Official capes being downloaded from Mojang right now (their picture ids). */
    private final java.util.Set<String> downloading = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> previewing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    CapeBrowserScreen(WardrobeScreen parent) {
        super(Component.literal(Lang.t("Capes")));
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
            Button button = Button.builder(Component.literal(Lang.t(t.label)), b -> show(t)).bounds(tabX, 24, 98, 20).build();
            button.active = t != tab;
            addRenderableWidget(button);
            tabX += 102;
        }
        int pages = pages();
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> turn(-1)).bounds(width / 2 - 126, height - 28, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> turn(1)).bounds(width / 2 + 106, height - 28, 20, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
        if (tab == Tab.OFFICIAL && !officialRefreshed) loadOfficial();
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
                for (OfficialCapes.Cape cape : officialCapes()) entries.add(new Entry(cape.name(), cape.choice(), null, cape));
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
                entries.add(new Entry(Lang.t("None"), "", null));
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

    /** The official capes: the Store's list once it's loaded, and until then (or with no internet) Squid's own. */
    private List<OfficialCapes.Cape> officialCapes() {
        List<OfficialCapes.Cape> fromStore = official;
        return fromStore != null && !fromStore.isEmpty() ? fromStore : OfficialCapes.bundled();
    }

    private int cellW() {
        return tab == Tab.OFFICIAL ? OFFICIAL_CELL_W : CELL_W;
    }

    private int columns() {
        return Math.max(1, (width - 130) / cellW());
    }

    private int rows() {
        return Math.max(1, (height - 52 - 44) / CELL_H);
    }

    private int pages() {
        int perPage = columns() * rows();
        return Math.max(1, (entries().size() + perPage - 1) / perPage);
    }

    private int gridLeft() {
        return 110 + Math.max(0, (width - 120 - columns() * cellW()) / 2);
    }

    // ---- Loading the lists ----

    /** Every slot is there from Squid's own list; the Store's list can add capes newer than this Squid. */
    private void loadOfficial() {
        officialRefreshed = true;
        background(() -> official = OfficialCapes.load());
    }

    /** Downloads an official cape's picture from Mojang's texture server (textures.minecraft.net); then it can be worn. */
    private void download(Entry entry) {
        String hash = entry.official().hash();
        if (!downloading.add(hash)) return;
        say(Lang.t("Downloading {0} from Mojang...", entry.name()), 0xFFA0A0A0);
        Thread thread = new Thread(() -> {
            String done;
            int color;
            try {
                OfficialCapes.fetch(Skins.wardrobe.officialCapes(), hash);
                done = Lang.t("Got {0}! Click it to wear it.", entry.name());
                color = 0xFF55FF55;
            } catch (IOException e) {
                done = Lang.t("Couldn't download it: {0}", e.getMessage());
                color = 0xFFFF5555;
            } catch (InterruptedException e) {
                return;
            } finally {
                downloading.remove(hash);
            }
            String text = done;
            int textColor = color;
            minecraft.execute(() -> say(text, textColor));
        }, "Squid official cape download");
        thread.setDaemon(true);
        thread.start();
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
        if (entry.needsDownload()) {
            download(entry);
            return;
        }
        Wardrobe.Choice now = Skins.choice(Skins.myId());
        try {
            if (entry.storeItem() != null) {
                Installer.install(entry.storeItem(), Main.gameFolder()); // checks its fingerprint, like the Store does
            }
            Skins.choose(Skins.myId(), now.withCape(entry.cape()));
            if (OfficialCapes.isOfficial(entry.cape())) {
                say(Lang.t("Wearing {0}. You don't own it, so others will see a tag next to your name.", entry.name()), 0xFFFFFF55);
            } else {
                say(entry.cape().isEmpty() ? Lang.t("No cape.") : Lang.t("Wearing {0}!", entry.name()), 0xFF55FF55);
            }
        } catch (IOException e) {
            say(Lang.t("Couldn't get it: {0}", e.getMessage()), 0xFFFF5555);
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
            int x = gridLeft() + ((i - first) % columns) * cellW();
            int y = 52 + ((i - first) / columns) * CELL_H;
            if (mouseX >= x && mouseX < x + cellW() - 4 && mouseY >= y && mouseY < y + CELL_H - 4) return entries.get(i);
        }
        return null;
    }

    // ---- Drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, pages() > 1 ? Lang.t("Capes") + "  (" + (page + 1) + " / " + pages() + ")" : Lang.t("Capes"), width / 2, 10, 0xFFFFFFFF);
        String wearing = Skins.choice(Skins.myId()).cape();
        List<Entry> entries = entries();
        int columns = columns();
        int first = page * columns * rows();
        for (int i = first; i < Math.min(entries.size(), first + columns * rows()); i++) {
            Entry entry = entries.get(i);
            int x = gridLeft() + ((i - first) % columns) * cellW();
            int y = 52 + ((i - first) / columns) * CELL_H;
            boolean over = mouseX >= x && mouseX < x + cellW() - 4 && mouseY >= y && mouseY < y + CELL_H - 4;
            boolean worn = entry.cape().equals(wearing) && entry.storeItem() == null;
            int cellW = cellW();
            g.fill(x, y, x + cellW - 4, y + CELL_H - 4, worn ? 0xFF2E7D32 : over ? 0x80FFFFFF : 0x60000000);
            int px = x + (cellW - 4 - PICTURE_W) / 2;
            int py = y + 3;
            boolean needsDownload = entry.needsDownload();
            Identifier picture = entry.cape().isEmpty() || needsDownload ? null
                    : entry.storeItem() != null ? storePreview(entry.storeItem()) : Skins.capePicture(entry.cape());
            if (needsDownload) {
                // An empty slot with a Download button on it: nothing comes from Mojang until it's pressed
                g.fill(px, py, px + PICTURE_W, py + PICTURE_H, 0xFF303030);
                boolean busy = downloading.contains(entry.official().hash());
                int bx = x + 3;
                int by = py + PICTURE_H / 2 - 7;
                g.fill(bx, by, x + cellW - 7, by + 14, busy ? 0xFF404040 : over ? 0xFF4A7A4A : 0xFF3A5A3A);
                g.centeredText(font, font.plainSubstrByWidth(busy ? "..." : Lang.t("Download"), cellW - 12), x + (cellW - 4) / 2, by + 3,
                        busy ? 0xFFA0A0A0 : 0xFFFFFFFF);
            } else if (picture != null) {
                // The cape's back: pixels 1-11 across and 1-17 down of a 64x32 cape (each frame, for animated ones)
                g.blit(picture, px, py, px + PICTURE_W, py + PICTURE_H, 1 / 64f, 11 / 64f, 1 / 32f, 17 / 32f);
            } else {
                g.fill(px, py, px + PICTURE_W, py + PICTURE_H, 0xFF303030);
                g.centeredText(font, entry.cape().isEmpty() ? "-" : "...", px + PICTURE_W / 2, py + PICTURE_H / 2 - 4, 0xFF808080);
            }
            small(g, entry.name(), x + (cellW - 4) / 2, y + PICTURE_H + 5, cellW - 6,
                    entry.storeItem() != null || needsDownload ? 0xFFA0A0A0 : 0xFFFFFFFF);
        }

        String hint = switch (tab) {
            case OFFICIAL -> hoveredOfficial(mouseX, mouseY) instanceof Entry over
                    ? (over.official().group().isEmpty() ? over.name() : over.name() + " (" + Lang.t(over.official().group()) + ")")
                    : Lang.t("Every vanilla cape. Download gets it from Mojang. Wearing one you don't own shows a tag by your name.");
            case COMMUNITY -> community == null ? (loadProblem != null ? Lang.t("Couldn't load them: {0}", loadProblem) : Lang.t("Loading the Store..."))
                    : community.isEmpty() ? Lang.t("No community capes yet.") : Lang.t("Approved capes from the Squid Store. Click one to get it and wear it.");
            case YOURS -> Lang.t("Yours: drop pictures on the wardrobe, or paint one.");
        };
        g.centeredText(font, font.plainSubstrByWidth(message != null ? message : hint, width - 20), width / 2, height - 41,
                message != null ? messageColor : 0xFF808080);
    }

    /** Text at three quarters size, centred on x, cut to fit in width. */
    private void small(GuiGraphicsExtractor g, String text, int x, int y, int width, int color) {
        float scale = 0.75f;
        String fits = font.plainSubstrByWidth(text, (int) (width / scale));
        if (fits.length() < text.length()) fits = font.plainSubstrByWidth(text, (int) (width / scale) - font.width("..")) + "..";
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.centeredText(font, fits, 0, 0, color);
        g.pose().popMatrix();
    }

    private Entry hoveredOfficial(int mouseX, int mouseY) {
        Entry entry = entryAt(mouseX, mouseY);
        return entry != null && entry.official() != null ? entry : null;
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
