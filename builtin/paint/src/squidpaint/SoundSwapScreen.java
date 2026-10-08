package squidpaint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import squid.Lang;
import squid.audio.Audio;
import squid.audio.Pcm;
import squid.audio.Sqda;
import squid.audio.SqdaTool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Sound Swapper: swap any Minecraft sound (a pig's oink, a creeper's hiss, the door creak) for your own. Pick the
 * sound, then drop a sound file onto the window (MP3, M4A, WAV, FLAC, Ogg or .sqda). It's squeezed into a .sqda,
 * made mono so the game can place it in the world, and saved in the Squid Paint pack under the sound's own name, so
 * Squid plays it in its place. Or press Record and be the pig yourself, with an effect (Chipmunk, Giant, Robot...). Hear it plays the sound as it is now, and
 * Reset brings Minecraft's sound back.
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
    private volatile boolean working; // a sound is being squeezed in the background
    private final Recorder recorder = new Recorder();
    private boolean wasRecording;
    private String effect = "None";
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
    static Path file(Identifier sound) {
        return Paint.folder().resolve("assets").resolve(sound.getNamespace()).resolve(sound.getPath());
    }

    static boolean swapped(Identifier sound) {
        return Files.exists(file(sound));
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
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Hear it")), b -> hear())
                .bounds(width / 2 - 100, height / 2 + 20, 98, 20).build()).active = !working;
        addRenderableWidget(Button.builder(Component.literal(recorder.recording() ? Lang.t("Stop") : Lang.t("Record")), b -> record())
                .bounds(width / 2 + 2, height / 2 + 20, 98, 20).build()).active = !working || recorder.recording();
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Reset")), b -> reset())
                .bounds(width / 2 - 100, height / 2 + 44, 98, 20).build()).active = !working && swapped(picked);
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Back")), b -> {
            picked = null;
            rebuildWidgets();
        }).bounds(width / 2 + 2, height / 2 + 44, 98, 20).build());
        // An effect for the next sound you drop or record; pressing it goes to the next one
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Effect: {0}", Effects.name(effect))), b -> {
            effect = Effects.ALL.get((Effects.ALL.indexOf(effect) + 1) % Effects.ALL.size());
            rebuildWidgets();
        }).bounds(width / 2 - 100, height / 2 + 68, 200, 20).build()).active = !working;
    }

    /** Plays the picked sound as it is now: yours if you've swapped it, else Minecraft's. */
    private void hear() {
        Identifier sound = picked;
        float volume = minecraft.options.getFinalSoundSourceVolume(SoundSource.MASTER);
        Util.backgroundExecutor().execute(() -> {
            try {
                byte[] data;
                if (swapped(sound)) {
                    data = Files.readAllBytes(file(sound));
                } else {
                    Optional<Resource> resource = minecraft.getResourceManager().getResource(sound);
                    if (resource.isEmpty()) return;
                    try (InputStream in = resource.get().open()) {
                        data = in.readAllBytes();
                    }
                }
                Preview.play(Audio.decode(data), volume);
            } catch (IOException | RuntimeException e) {
                String why = e.getMessage();
                minecraft.execute(() -> say(Lang.t("Couldn't play it: {0}", why), 0xFFFF5555));
            }
        });
    }

    /**
     * Records your own sound from the microphone (press again to stop), so you can be the pig. The quiet bits at
     * the start and end are cut off, and it's swapped in like a dropped file.
     */
    private void record() {
        if (recorder.recording()) {
            recorder.stop();
            rebuildWidgets();
            return;
        }
        if (working) return;
        Preview.stop();
        Identifier sound = picked;
        say(Lang.t("Recording... Make your sound, then press Stop."), 0xFFFF5555);
        recorder.start(samples -> minecraft.execute(() -> {
            if (samples.length == 0) {
                say(Lang.t("Didn't hear anything. Is the microphone on?"), 0xFFFF5555);
                rebuildWidgets();
                return;
            }
            swapIn(sound, () -> new Pcm(samples, 1, Recorder.RATE), Lang.t("Recorded in Squid"));
        }), problem -> minecraft.execute(() -> {
            say(Lang.t("No microphone: {0}", problem), 0xFFFF5555);
            rebuildWidgets();
        }));
        rebuildWidgets();
    }

    @Override
    public void tick() {
        // The 10 seconds ran out: the Stop button turns back into Record
        if (picked != null && !recorder.recording() && wasRecording) rebuildWidgets();
        wasRecording = recorder.recording();
    }

    @Override
    public void removed() {
        recorder.stop();
        Preview.stop();
    }

    private List<Identifier> shown() {
        String wanted = filter.toLowerCase(Locale.ROOT).strip().replace(' ', '_');
        if (wanted.isEmpty()) return all;
        return all.stream().filter(s -> s.getPath().contains(wanted)).toList();
    }

    /**
     * A sound file dropped onto the window takes the picked sound's place. It's squeezed in the background, so the
     * game doesn't freeze on a long file.
     */
    @Override
    public void onFilesDrop(List<Path> files) {
        if (picked == null || files.isEmpty() || working || recorder.recording()) return;
        Path dropped = files.getFirst();
        boolean song = isSong(picked);
        swapIn(picked, () -> {
            // A minute of the biggest sound file there is (a WAV) is about 10 MB (8 minutes of song about 85 MB), so
            // anything far past that is too long; this is checked before reading it all
            if (Files.size(dropped) > (song ? 200L << 20 : 64L << 20)) {
                throw new TooLong(song ? Lang.t("That file is too big. Songs can be 8 minutes at most.")
                        : Lang.t("That file is too big. Sounds can be a minute at most."));
            }
            return Audio.decode(Files.readAllBytes(dropped));
        }, dropped.getFileName().toString());
    }

    /**
     * Music discs (sounds/records) and the game's music (sounds/music) take whole songs, up to 8 minutes, so you can
     * make a music disc of your favorite song. The game's music stays stereo; discs play from the jukebox's spot in
     * the world, so they're mono like other sounds.
     */
    static boolean isSong(Identifier sound) {
        return sound.getPath().startsWith("sounds/records/") || sound.getPath().startsWith("sounds/music/");
    }

    /** Reading a sound, which might not work. */
    private interface SoundReader {
        Pcm get() throws IOException;
    }

    /** A sound too long to swap in, with the message saying so. */
    private static final class TooLong extends RuntimeException {
        TooLong(String message) {
            super(message);
        }
    }

    /** Squeezes a sound into the pack in place of this one, in the background, then reloads so it's heard. */
    private void swapIn(Identifier sound, SoundReader read, String title) {
        String withEffect = effect;
        boolean song = isSong(sound);
        boolean stereo = sound.getPath().startsWith("sounds/music/") && withEffect.equals("None"); // effects work in mono
        working = true;
        say(Lang.t("Squeezing it in..."), 0xFFA0A0A0);
        rebuildWidgets();
        Util.backgroundExecutor().execute(() -> {
            String problem = null;
            try {
                Pcm pcm = read.get();
                if (!stereo) pcm = Effects.apply(withEffect, SqdaTool.toMono(pcm)); // Minecraft only places mono sounds in the world
                if (pcm.seconds() > (song ? 8 * 60 : 60)) {
                    problem = song ? Lang.t("That's {0} seconds long. Songs can be 8 minutes at most.", (int) pcm.seconds())
                            : Lang.t("That's {0} seconds long. Sounds can be a minute at most.", (int) pcm.seconds());
                } else {
                    Sqda sqda = Sqda.fromSound(pcm, 6);
                    sqda.info.put("title", title);
                    Paint.pack();
                    Path target = file(sound);
                    Files.createDirectories(target.getParent());
                    Files.write(target, sqda.write());
                }
            } catch (TooLong e) {
                problem = e.getMessage();
            } catch (IOException | RuntimeException e) {
                problem = Lang.t("Couldn't use that file: {0}", e.getMessage());
            }
            String failed = problem;
            minecraft.execute(() -> {
                working = false;
                if (failed != null) {
                    say(failed, 0xFFFF5555);
                    rebuildWidgets();
                    return;
                }
                say(Lang.t("Swapped! Reloading so you can hear it..."), 0xFF55FF55);
                Paint.apply();
                rebuildWidgets();
            });
        });
    }

    private void reset() {
        if (working || recorder.recording()) return;
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
            if (picked.getPath().startsWith("sounds/records/")) {
                g.centeredText(font, Lang.t("A music disc: drop a whole song on it, then play the disc in a jukebox."), width / 2, height / 2 - 54, 0xFFFFFF55);
            }
            g.centeredText(font, Lang.t("Drop a sound file here (MP3, M4A, WAV, FLAC, Ogg or .sqda) to swap it in."),
                    width / 2, height / 2 - 20, 0xFFA0A0A0);
            g.centeredText(font, Lang.t("Or press Record and make the sound yourself."), width / 2, height / 2 - 8, 0xFFA0A0A0);
            g.centeredText(font, Lang.t("Only players with Squid hear swapped sounds."), width / 2, height / 2 + 4, 0xFF808080);
            if (recorder.recording()) {
                // How loud the mic is, and how long is left
                int bar = Math.round(recorder.level() * 196);
                int y = height / 2 + 94;
                g.fill(width / 2 - 100, y, width / 2 + 100, y + 8, 0xFF000000);
                g.fill(width / 2 - 98, y + 2, width / 2 - 98 + bar, y + 6, 0xFF55FF55);
                int left = (int) Math.ceil(Recorder.MOST_SECONDS - recorder.seconds());
                g.centeredText(font, Lang.t("{0} seconds left", Math.max(0, left)), width / 2, y + 12, 0xFFFF5555);
            }
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
