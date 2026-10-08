package squid.api;

/**
 * What music is doing right now: .sqda sounds from resource packs, and songs from the Jukebox. A .sqda file carries
 * a volume track, and the Jukebox measures its songs as they play, so mods can make things pulse with the music
 * without listening to the sound themselves.
 */
public final class SquidAudio {
    private static volatile float level;
    private static volatile String playing = "";
    private static volatile float jukeboxLevel;
    private static volatile String jukeboxSong = "";

    private SquidAudio() {
    }

    /** How loud the music playing now is, 0 (silent) to 1 (full), or 0 if none is playing. */
    public static float level() {
        return Math.max(level, jukeboxLevel);
    }

    /** Which .sqda sound is playing (the loudest one if several are), else the Jukebox's song, or "" if neither. */
    public static String playing() {
        String sound = playing;
        return sound.isEmpty() ? jukeboxSong : sound;
    }

    /** The Jukebox's song, like "Pigstep - Lena Raine", or "" when it's quiet. */
    public static String jukebox() {
        return jukeboxSong;
    }

    /** Squid sets these as sounds play. */
    public static void update(String sound, float loudness) {
        playing = sound;
        level = loudness;
    }

    /** The Jukebox sets these as it plays. */
    public static void updateJukebox(String song, float loudness) {
        jukeboxSong = song;
        jukeboxLevel = loudness;
    }
}
