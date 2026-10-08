package squid.api;

/**
 * What .sqda sounds are doing right now. A .sqda file can carry a volume track, so mods can make things pulse with
 * the music without listening to the sound themselves.
 */
public final class SquidAudio {
    private static volatile float level;
    private static volatile String playing = "";

    private SquidAudio() {
    }

    /** How loud the .sqda music playing now is, 0 (silent) to 1 (full), or 0 if none is playing. */
    public static float level() {
        return level;
    }

    /** Which .sqda sound is playing (the loudest one if several are), or "" if none. */
    public static String playing() {
        return playing;
    }

    /** Squid sets these as sounds play. */
    public static void update(String sound, float loudness) {
        playing = sound;
        level = loudness;
    }
}
