package squidjukebox;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import squid.Lang;
import squid.Main;
import squid.api.Hud;
import squid.api.ModSettings;
import squid.api.Squid;
import squid.api.SquidAudio;
import squid.api.SquidMod;
import squid.audio.Tags;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The Jukebox: your own music in the game. Songs go in the music folder in Kelp's folder (so every instance shares
 * them), or get dropped onto the Jukebox screen. MP3, FLAC, WAV, Ogg and .sqda all play, with Squid's own decoders.
 *
 * While a song plays, Minecraft's own background music waits, a "Now Playing" card shows the title and artist for a
 * few seconds, and mods can follow its loudness through SquidAudio. It follows Minecraft's Music volume slider.
 */
public class Jukebox implements SquidMod {
    static final String[] KINDS = {".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".sqda"};
    static final String OFF = "Off";
    static final String ALL = "All";
    static final String ONE = "One";

    static Jukebox instance;

    private ModSettings settings;
    private volatile SongPlayer player;
    private volatile Song current;
    /** The player whose song just ended by itself (finished, or couldn't play), for the game thread to move on. */
    private volatile SongPlayer ended;
    /** Songs that couldn't play since one last did. Once every song is in here, the Jukebox stops instead of trying forever. */
    private final java.util.Set<Path> failed = new java.util.HashSet<>();
    private volatile long shownAt;
    private boolean gameMusicStopped;
    private final Random random = new Random();
    private final Map<String, Song> tagCache = new ConcurrentHashMap<>();

    /** A song: its file, and the title and artist it says (the file's name when it doesn't say). */
    record Song(Path file, String title, String artist) {
        String shown() {
            return artist.isEmpty() ? title : title + " - " + artist;
        }
    }

    @Override
    public void init(Squid squid) {
        instance = this;
        settings = squid.settings();
        volume(); // so the settings show up in Squid's Mods screen in this order
        shuffle();
        repeat();
        pauseGameMusic();
        showNowPlaying();
        musicBars();
        // Minecraft's own music waits while a song plays
        squid.atStart("net.minecraft.client.sounds.MusicManager", "tick", call -> {
            if (player != null && pauseGameMusic()) call.cancel();
        });
        squid.onTick(this::tick);
        squid.onHud(this::hud);
        squid.addMenuButton("Jukebox", false, menu -> Minecraft.getInstance().setScreenAndShow(new JukeboxScreen(menu)));
    }

    int volume() {
        return settings.number("Volume", 100, 0, 100);
    }

    boolean shuffle() {
        return settings.toggle("Shuffle", false);
    }

    String repeat() {
        return settings.choice("Repeat", ALL, OFF, ALL, ONE);
    }

    boolean pauseGameMusic() {
        return settings.toggle("Pause Minecraft's music", true);
    }

    boolean showNowPlaying() {
        return settings.toggle("Show Now Playing", true);
    }

    boolean musicBars() {
        return settings.toggle("Music bars", false);
    }

    void setShuffle(boolean on) {
        settings.set("Shuffle", String.valueOf(on));
    }

    void setRepeat(String mode) {
        settings.set("Repeat", mode);
    }

    /** The music folder: in Kelp's folder, shared by every instance (or the game's folder without Kelp). */
    static Path folder() {
        String home = System.getProperty("squid.home");
        return home != null ? Path.of(home).resolve("music") : Main.gameFolder().resolve("music");
    }

    static boolean isSong(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String kind : KINDS) if (name.endsWith(kind)) return true;
        return false;
    }

    /** Every song in the music folder, by name. */
    List<Song> songs() {
        List<Song> songs = new ArrayList<>();
        Path folder = folder();
        if (!Files.isDirectory(folder)) return songs;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(f -> Files.isRegularFile(f) && isSong(f)).sorted().toList()) songs.add(song(file));
        } catch (IOException e) {
            System.out.println("[Squid Jukebox] Couldn't look in " + folder + ": " + e.getMessage());
        }
        return songs;
    }

    /** A song's title and artist, from its tags. Only the start and end of the file are read, and each file only once. */
    Song song(Path file) {
        String key;
        try {
            key = file + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            key = file.toString();
        }
        return tagCache.computeIfAbsent(key, k -> {
            String name = file.getFileName().toString();
            String plain = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            Map<String, String> tags = readTags(file);
            return new Song(file, tags.getOrDefault("title", plain), tags.getOrDefault("artist", ""));
        });
    }

    private static Map<String, String> readTags(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(10);
            int wanted = 256 * 1024;
            // An MP3's tags can hold its cover picture, so read as far as they go (up to 8 MB)
            if (head.length == 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                int size = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14) | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
                wanted = Math.min(8 << 20, size + 10);
            }
            byte[] rest = in.readNBytes(Math.max(0, wanted - head.length));
            byte[] start = new byte[head.length + rest.length];
            System.arraycopy(head, 0, start, 0, head.length);
            System.arraycopy(rest, 0, start, head.length, rest.length);
            Map<String, String> tags = Tags.read(start);
            if (!tags.containsKey("title") && Files.size(file) > 128) {
                try (var channel = Files.newByteChannel(file)) {
                    java.nio.ByteBuffer last = java.nio.ByteBuffer.allocate(128);
                    channel.position(Files.size(file) - 128);
                    while (last.hasRemaining() && channel.read(last) > 0) {
                        // keep reading
                    }
                    Tags.readEnd(last.array()).forEach(tags::putIfAbsent);
                }
            }
            return tags;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    Song current() {
        return current;
    }

    SongPlayer player() {
        return player;
    }

    boolean playing() {
        return player != null;
    }

    void play(Song song) {
        SongPlayer old = player;
        if (old != null) old.stop();
        current = song;
        SongPlayer[] self = new SongPlayer[1];
        SongPlayer next = new SongPlayer(song.file(), ONE.equals(repeat()), () -> ended = self[0]);
        self[0] = next;
        next.volume = gain();
        player = next;
        shownAt = System.currentTimeMillis();
        gameMusicStopped = false;
        next.start();
    }

    void stop() {
        SongPlayer old = player;
        if (old != null) old.stop();
        player = null;
        current = null;
    }

    void togglePause() {
        SongPlayer p = player;
        if (p != null) p.paused = !p.paused;
    }

    /** The next song: the one after this, or a random other one when shuffling. Stops at the end unless repeating all. */
    void next() {
        List<Song> songs = songs();
        if (songs.isEmpty()) {
            stop();
            return;
        }
        Song now = current;
        int index = -1;
        for (int i = 0; i < songs.size(); i++) if (now != null && songs.get(i).file().equals(now.file())) index = i;
        int pick;
        if (shuffle() && songs.size() > 1) {
            do {
                pick = random.nextInt(songs.size());
            } while (pick == index);
        } else {
            pick = index + 1;
            if (pick >= songs.size()) {
                if (OFF.equals(repeat()) && now != null) {
                    stop();
                    return;
                }
                pick = 0;
            }
        }
        play(songs.get(pick));
    }

    void previous() {
        List<Song> songs = songs();
        if (songs.isEmpty()) return;
        Song now = current;
        int index = 0;
        for (int i = 0; i < songs.size(); i++) if (now != null && songs.get(i).file().equals(now.file())) index = i;
        play(songs.get((index - 1 + songs.size()) % songs.size()));
    }

    /** The Jukebox's volume times Minecraft's master and music sliders. */
    private float gain() {
        try {
            var options = Minecraft.getInstance().options;
            return volume() / 100f * options.getSoundSourceVolume(SoundSource.MASTER) * options.getSoundSourceVolume(SoundSource.MUSIC);
        } catch (RuntimeException e) {
            return volume() / 100f;
        }
    }

    private void tick() {
        SongPlayer finished = ended;
        if (finished != null) {
            ended = null;
            // Only the song that's playing now moves things on (not one you'd already skipped)
            if (finished == player) {
                if (finished.problem != null && !finished.started) {
                    // A song that can't play: shown for a moment, then on to the next one, unless they all fail
                    problem = Lang.t("Couldn't play {0}: {1}", current == null ? "?" : current.shown(), finished.problem);
                    problemAt = System.currentTimeMillis();
                    if (current != null) failed.add(current.file());
                    List<Song> all = songs();
                    if (all.isEmpty() || all.stream().allMatch(s -> failed.contains(s.file()))) {
                        failed.clear();
                        stop();
                        return;
                    }
                } else {
                    failed.clear();
                }
                next();
            }
        }
        SongPlayer p = player;
        Song song = current;
        if (p == null || song == null) {
            SquidAudio.updateJukebox("", 0);
            return;
        }
        p.volume = gain();
        p.wantBars = musicBars();
        SquidAudio.updateJukebox(song.shown(), p.paused ? 0 : p.level);
        if (!gameMusicStopped && pauseGameMusic()) {
            Minecraft.getInstance().getMusicManager().stopPlaying();
            gameMusicStopped = true;
        }
    }

    private volatile String problem;
    private volatile long problemAt;

    String problem() {
        return System.currentTimeMillis() - problemAt < 8000 ? problem : null;
    }

    /** What the bars show: they jump up with the music and fall back gently. */
    private final float[] shownBars = new float[8];

    /** The Now Playing card, top right, for a few seconds after a song starts, and the music bars if they're on. */
    private void hud(Hud hud) {
        String trouble = problem();
        if (trouble != null) hud.text(trouble, 4, hud.height() - 34, 0xFFFF7777);
        Song song = current;
        if (song == null) return;
        long age = System.currentTimeMillis() - shownAt;
        SongPlayer p = player;
        if (musicBars() && p != null) {
            float[] target = p.paused ? new float[8] : p.bars;
            int top = showNowPlaying() && age <= 6000 ? 32 : 4;
            for (int i = 0; i < shownBars.length; i++) {
                shownBars[i] = Math.max(i < target.length ? target[i] : 0, shownBars[i] * 0.85f);
                int height = Math.max(1, Math.round(shownBars[i] * 16));
                int x = hud.width() - 4 - (shownBars.length - i) * 4;
                hud.box(x, top + 16 - height, 3, height, i < 3 ? 0xFF55FFFF : i < 6 ? 0xFF55FF55 : 0xFFFFFF55);
            }
        }
        if (!showNowPlaying() || age > 6000) return;
        String heading = Lang.t("Now Playing");
        int width = Math.max(hud.textWidth(heading), hud.textWidth(song.shown())) + 22;
        // Slides in from the right, and back out at the end
        int slide = age < 300 ? (int) ((300 - age) * width / 300) : age > 5700 ? (int) ((age - 5700) * width / 300) : 0;
        int x = hud.width() - width - 4 + slide;
        int y = 4;
        hud.box(x, y, width, 24, 0xC0000000);
        hud.outline(x, y, width, 24, 0xFF55FFFF);
        // A music note: a stem and a head
        hud.box(x + 8, y + 5, 1, 10, 0xFF55FFFF);
        hud.box(x + 9, y + 5, 3, 2, 0xFF55FFFF);
        hud.box(x + 5, y + 13, 4, 3, 0xFF55FFFF);
        hud.text(heading, x + 17, y + 3, 0xFFA0A0A0);
        hud.text(song.shown(), x + 17, y + 13, 0xFFFFFFFF);
    }
}
