package squidpaint;

import squid.Lang;
import squid.audio.Pcm;

import java.util.List;

/**
 * Fun effects for the Sound Swapper, put on a sound before it's swapped in: Chipmunk (high and fast), Giant (deep
 * and slow), Robot, Echo and Backwards. They work on mono sounds, which is what the Sound Swapper makes.
 */
final class Effects {
    static final List<String> ALL = List.of("None", "Chipmunk", "Giant", "Robot", "Echo", "Backwards");

    private Effects() {
    }

    /** The effect's name in the player's language. */
    static String name(String effect) {
        return switch (effect) {
            case "Chipmunk" -> Lang.t("Chipmunk");
            case "Giant" -> Lang.t("Giant");
            case "Robot" -> Lang.t("Robot");
            case "Echo" -> Lang.t("Echo");
            case "Backwards" -> Lang.t("Backwards");
            default -> Lang.t("No effect");
        };
    }

    /** The sound with the effect on. */
    static Pcm apply(String effect, Pcm sound) {
        short[] in = sound.samples();
        short[] out = switch (effect) {
            case "Chipmunk" -> speed(in, 1.6);
            case "Giant" -> speed(in, 0.65);
            case "Robot" -> robot(in, sound.rate());
            case "Echo" -> echo(in, sound.rate());
            case "Backwards" -> backwards(in);
            default -> in;
        };
        return new Pcm(out, 1, sound.rate());
    }

    /** Played faster (higher) or slower (deeper), like a record at the wrong speed. */
    static short[] speed(short[] in, double factor) {
        int length = (int) (in.length / factor);
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            double at = i * factor;
            int a = (int) at;
            int b = Math.min(in.length - 1, a + 1);
            double between = at - a;
            out[i] = (short) Math.round(in[a] * (1 - between) + in[b] * between);
        }
        return out;
    }

    /** Multiplied by a low hum, which gives the classic robot voice. */
    static short[] robot(short[] in, int rate) {
        short[] out = new short[in.length];
        for (int i = 0; i < in.length; i++) {
            double hum = Math.sin(2 * Math.PI * 50 * i / rate);
            out[i] = (short) Math.round(in[i] * hum);
        }
        return out;
    }

    /** Two echoes, a quarter of a second apart, each quieter. */
    static short[] echo(short[] in, int rate) {
        int gap = rate / 4;
        short[] out = new short[in.length + 2 * gap];
        for (int i = 0; i < out.length; i++) {
            double v = 0;
            if (i < in.length) v += in[i];
            if (i >= gap && i - gap < in.length) v += in[i - gap] * 0.5;
            if (i >= 2 * gap && i - 2 * gap < in.length) v += in[i - 2 * gap] * 0.25;
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v * 0.7)));
        }
        return out;
    }

    static short[] backwards(short[] in) {
        short[] out = new short[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[in.length - 1 - i];
        return out;
    }
}
