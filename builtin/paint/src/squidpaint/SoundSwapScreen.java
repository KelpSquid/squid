package squidpaint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import squid.Lang;
import squid.audio.Audio;
import squid.audio.Pcm;
import squid.audio.Sqda;
import squid.audio.SqdaTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Sound Swapper: swap any Minecraft sound (a pig's oink, a creeper's hiss, the door creak) for your own. Pick the
 * sound, then drop a sound file onto the window (MP3, M4A, WAV, FLAC, Ogg or .sqda). It's squeezed into a .sqda,
 * made mono so the game can place it in the world, and saved in the Squid Paint pack under the sound's own name, so
 * Squid plays it in its place. Reset brings Minecraft's sound back.
 *
 * Only players with Squid hear swapped sounds (the file is a .sqda inside), which is why it lives in Squid's own pack.
 */
final class SoundSwapScreen extends Screen {
    private final Screen back;
    private List<Identifier> all;
    private EditBox search;
    private String filter = "";
    private int page;
    private Identifier picked;
    private String message;
    private int messageColor;

    SoundSwapScreen(Object back) {
        super(Component.literal(Lang.t("Sound Swapper")));
        this.back = back instanceof Screen s ? s : null;
    }

    /** Every sound file there is, like minecraft:sounds/mob/pig/say1.ogg. */
    private static List<Identifier> sounds() {
        List<Identifier> found = new ArrayList<>(Minecraft.getInstance().getResourceManager()
                .listResources("sounds", id -> id.getPath().endsWith(".ogg")).keySet());
        found.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        return found;
    }

    /** "minecraft:sounds/mob/pig/say1.ogg" is shown as "mob/pig/say1". */
    static String nice(Identifier sound) {
        String path = sound.getPath();
        if (path.startsWith("sounds/")) path = path.substring("sounds/".length());
        if (path.endsWith(".ogg")) path = path.substring(0, path.length() - ".ogg".length());
        return path;
    }

    /** Where the swapped sound goes in the pack: the same name, so it's played in place of Minecraft's. */
    static Path file(Identifier sound) throws IOException {
        return Paint.pack().resolve("assets").resolve(sound.getNamespace()).resolve(sound.getPath());
    }

    static boolean swapped(Identifier sound) {
        try {
            return Files.exists(file(sound));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    protected void init() {
        int x = width / 2 - 150;
        if (picked != null) {
            initPicked(x);
            return;
        }
        if (all == null) all = sounds();
        search = new EditBox(font, x, 30, 300, 20, Component.literal(Lang.t("Search")));
        search.setHint(Component.literal(Lang.t("Search sounds, like \"pig\" or \"door\"")));
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
        int columns = 2;
        int rows = Math.max(1, (height - 120) / 22);
        int perPage = columns * rows;
        int pages = Math.max(1, (shown.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        List<Identifier> onPage = shown.subList(Math.min(shown.size(), page * perPage), Math.min(shown.size(), (page + 1) * perPage));
        for (int i = 0; i < onPage.size(); i++) {
            Identifier sound = onPage.get(i);
            String name = (swapped(sound) ? "* " : "") + nice(sound);
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(name, 140)), b -> {
                picked = sound;
                message = null;
                rebuildWidgets();
            }).bounds(x + (i % columns) * 152, 58 + (i / columns) * 22, 148, 20).build());
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

    private void initPicked(int x) {
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Reset")), b -> reset())
                .bounds(width / 2 - 100, height / 2 + 20, 98, 20).build()).active = swapped(picked);
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Back")), b -> {
            picked = null;
            rebuildWidgets();
        }).bounds(width / 2 + 2, height / 2 + 20, 98, 20).build());
    }

    private List<Identifier> shown() {
        String wanted = filter.toLowerCase(Locale.ROOT).strip().replace(' ', '_');
        if (wanted.isEmpty()) return all;
        return all.stream().filter(s -> s.getPath().contains(wanted)).toList();
    }

    /** A sound file dropped onto the window takes the picked sound's place. */
    @Override
    public void onFilesDrop(List<Path> files) {
        if (picked == null || files.isEmpty()) return;
        try {
            Pcm pcm = Audio.decode(Files.readAllBytes(files.getFirst()));
            pcm = SqdaTool.toMono(pcm); // Minecraft only places mono sounds in the world
            if (pcm.seconds() > 60) {
                say(Lang.t("That's {0} seconds long. Sounds can be a minute at most.", (int) pcm.seconds()), 0xFFFF5555);
                return;
            }
            Sqda sqda = Sqda.fromSound(pcm, 6);
            sqda.info.put("title", files.getFirst().getFileName().toString());
            Path target = file(picked);
            Files.createDirectories(target.getParent());
            Files.write(target, sqda.write());
            say(Lang.t("Swapped! Reloading so you can hear it..."), 0xFF55FF55);
            Paint.apply();
            rebuildWidgets();
        } catch (IOException | RuntimeException e) {
            say(Lang.t("Couldn't use that file: {0}", e.getMessage()), 0xFFFF5555);
        }
    }

    private void reset() {
        try {
            Files.deleteIfExists(file(picked));
            say(Lang.t("Minecraft's own sound is back."), 0xFF55FF55);
            Paint.apply();
            rebuildWidgets();
        } catch (IOException e) {
            say(Lang.t("Couldn't reset it: {0}", e.getMessage()), 0xFFFF5555);
        }
    }

    private void say(String text, int color) {
        message = text;
        messageColor = color;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("Sound Swapper"), width / 2, 12, 0xFFFFFFFF);
        if (picked != null) {
            g.centeredText(font, nice(picked), width / 2, height / 2 - 40, 0xFF55FFFF);
            g.centeredText(font, Lang.t("Drop a sound file here (MP3, M4A, WAV, FLAC, Ogg or .sqda) to swap it in."),
                    width / 2, height / 2 - 20, 0xFFA0A0A0);
            g.centeredText(font, Lang.t("Only players with Squid hear swapped sounds."), width / 2, height / 2 - 6, 0xFF808080);
        } else {
            if (shown().isEmpty()) g.centeredText(font, Lang.t("No sounds with that name."), width / 2, height / 2, 0xFFA0A0A0);
            g.centeredText(font, Lang.t("Pick a sound to swap. A * means you've swapped it."), width / 2, height - 40, 0xFF808080);
        }
        if (message != null) g.centeredText(font, font.plainSubstrByWidth(message, width - 20), width / 2, height - 64, messageColor);
    }

    @Override
    public void onClose() {
        if (picked != null) {
            picked = null;
            rebuildWidgets();
            return;
        }
        minecraft.setScreenAndShow(back);
    }
}
