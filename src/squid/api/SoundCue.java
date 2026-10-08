package squid.api;

/**
 * A moment in a .sqda sound that's playing: a beat, a bar, a section, a named cue, or a light cue. Mods get these
 * with {@link Squid#onSoundCue}, right as the moment is heard, to make lights, effects or anything else follow the
 * music.
 *
 * @param sound      which sound it's from, like "minecraft:music/menu/theme"
 * @param kind       "beat", "bar", "section", "cue" or "light"
 * @param name       the cue's name (empty for beats and bars, and the lights' group for light cues)
 * @param seconds    where in the sound it is
 * @param length     for light cues, how long the light stays on, in seconds (0 for the rest)
 * @param color      for light cues, the color as 0xRRGGBB
 * @param brightness for light cues, 0 to 255
 * @param effect     for light cues, "on", "flash", "fade" or "strobe"
 */
public record SoundCue(String sound, String kind, String name, double seconds, double length, int color, int brightness, String effect) {
}
