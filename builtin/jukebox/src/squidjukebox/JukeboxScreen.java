package squidjukebox;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The Jukebox, from the Squid menu: play, pause, skip, shuffle and repeat, and every song in the music folder. Songs
 * dropped onto the window are copied into the music folder.
 */
final class JukeboxScreen extends Screen {
    private final Screen back;
    private List<Jukebox.Song> songs = List.of();
    private int page;
    private Jukebox.Song shownSong;
    private boolean shownPaused;
    private String message;
    private int messageColor;

    JukeboxScreen(Object back) {
        super(Component.literal(Lang.t("Jukebox")));
        this.back = back instanceof Screen s ? s : null;
    }

    private static Jukebox jukebox() {
        return Jukebox.instance;
    }

    @Override
    protected void init() {
        Jukebox j = jukebox();
        songs = j.songs();
        shownSong = j.current();
        SongPlayer p = j.player();
        shownPaused = p != null && p.paused;
        int x = width / 2 - 100;
        int y = 40;
        // Play/Pause, Stop, Previous, Next
        String playLabel = p == null ? Lang.t("Play") : p.paused ? Lang.t("Play") : Lang.t("Pause");
        addRenderableWidget(Button.builder(Component.literal(playLabel), b -> {
            if (j.player() == null) {
                if (!songs.isEmpty()) j.next();
            } else {
                j.togglePause();
            }
            rebuildWidgets();
        }).bounds(x, y, 62, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Stop")), b -> {
            j.stop();
            rebuildWidgets();
        }).bounds(x + 66, y, 62, 20).build()).active = p != null;
        addRenderableWidget(Button.builder(Component.literal("<<"), b -> {
            j.previous();
            rebuildWidgets();
        }).bounds(x + 132, y, 32, 20).build()).active = !songs.isEmpty();
        addRenderableWidget(Button.builder(Component.literal(">>"), b -> {
            j.next();
            rebuildWidgets();
        }).bounds(x + 168, y, 32, 20).build()).active = !songs.isEmpty();
        y += 24;
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Shuffle: {0}", Lang.t(j.shuffle() ? "On" : "Off"))), b -> {
            j.setShuffle(!j.shuffle());
            rebuildWidgets();
        }).bounds(x, y, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Repeat: {0}", Lang.t(j.repeat()))), b -> {
            String now = j.repeat();
            j.setRepeat(now.equals(Jukebox.ALL) ? Jukebox.ONE : now.equals(Jukebox.ONE) ? Jukebox.OFF : Jukebox.ALL);
            rebuildWidgets();
        }).bounds(x + 102, y, 98, 20).build());
        y += 40;
        // The songs, a page at a time
        int perPage = Math.max(1, (height - y - 60) / 22);
        int pages = Math.max(1, (songs.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        for (Jukebox.Song song : songs.subList(Math.min(songs.size(), page * perPage), Math.min(songs.size(), (page + 1) * perPage))) {
            boolean now = shownSong != null && shownSong.file().equals(song.file());
            String label = fit((now ? "> " : "") + song.shown(), 190);
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                j.play(song);
                rebuildWidgets();
            }).bounds(x, y, 200, 20).build());
            y += 22;
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = (page + pages - 1) % pages;
                rebuildWidgets();
            }).bounds(x - 24, height - 54, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = (page + 1) % pages;
                rebuildWidgets();
            }).bounds(x + 204, height - 54, 20, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Music Folder")), b -> openFolder()).bounds(x, height - 28, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(x + 102, height - 28, 98, 20).build());
    }

    /** Cuts a long title to fit a button, with "..." at the end. */
    private String fit(String text, int pixels) {
        if (font.width(text) <= pixels) return text;
        while (text.length() > 1 && font.width(text + "...") > pixels) text = text.substring(0, text.length() - 1);
        return text + "...";
    }

    @Override
    public void tick() {
        // The song changed by itself (it ended, or a bad file was skipped): show the new one
        Jukebox j = jukebox();
        SongPlayer p = j.player();
        boolean paused = p != null && p.paused;
        if (j.current() != shownSong || paused != shownPaused) rebuildWidgets();
    }

    private void openFolder() {
        Path folder = Jukebox.folder();
        try {
            Files.createDirectories(folder);
            String os = System.getProperty("os.name").toLowerCase();
            String[] command = os.contains("win") ? new String[] {"explorer", folder.toString()}
                    : os.contains("mac") ? new String[] {"open", folder.toString()} : new String[] {"xdg-open", folder.toString()};
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            say(Lang.t("Couldn't open the folder: {0}", e.getMessage()), 0xFFFF5555);
        }
    }

    /** Songs dropped onto the window go into the music folder. */
    @Override
    public void onFilesDrop(List<Path> files) {
        int added = 0;
        try {
            Files.createDirectories(Jukebox.folder());
            for (Path file : files) {
                if (!Jukebox.isSong(file)) continue;
                Files.copy(file, Jukebox.folder().resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                added++;
            }
        } catch (IOException e) {
            say(Lang.t("Couldn't add it: {0}", e.getMessage()), 0xFFFF5555);
            return;
        }
        if (added == 0) say(Lang.t("Songs are .mp3, .m4a, .flac, .wav, .ogg or .sqda files."), 0xFFFF5555);
        else say(added == 1 ? Lang.t("Added 1 song!") : Lang.t("Added {0} songs!", added), 0xFF55FF55);
        rebuildWidgets();
    }

    private void say(String text, int color) {
        message = text;
        messageColor = color;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Jukebox j = jukebox();
        g.centeredText(font, Lang.t("Jukebox"), width / 2, 12, 0xFFFFFFFF);
        Jukebox.Song song = j.current();
        SongPlayer p = j.player();
        int x = width / 2 - 100;
        if (song != null && p != null) {
            g.centeredText(font, fit(song.shown(), 260), width / 2, 26, 0xFF55FFFF);
            // How far along it is
            double length = p.lengthSeconds();
            double at = Math.min(length, p.seconds());
            int filled = length <= 0 ? 0 : (int) Math.round(200 * at / length);
            g.fill(x, 90, x + 200, 93, 0x80000000);
            g.fill(x, 90, x + filled, 93, 0xFF55FFFF);
            g.text(font, time(at), x, 95, 0xFFA0A0A0, true);
            String total = time(length);
            g.text(font, total, x + 200 - font.width(total), 95, 0xFFA0A0A0, true);
        } else {
            g.centeredText(font, Lang.t("Nothing playing"), width / 2, 26, 0xFFA0A0A0);
        }
        if (songs.isEmpty()) {
            g.centeredText(font, Lang.t("No songs yet. Drop .mp3, .m4a, .flac, .wav, .ogg or .sqda files here,"), width / 2, 112, 0xFFA0A0A0);
            g.centeredText(font, Lang.t("or put them in the Music Folder."), width / 2, 124, 0xFFA0A0A0);
        }
        String trouble = j.problem();
        String text = trouble != null ? trouble : message;
        if (text != null) g.centeredText(font, fit(text, width - 20), width / 2, height - 42, trouble != null ? 0xFFFF7777 : messageColor);
    }

    private static String time(double seconds) {
        int s = (int) seconds;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(back);
    }
}
