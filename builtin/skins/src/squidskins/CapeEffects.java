package squidskins;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What a cape can do besides sit there: move (animated capes), look special (enchanted, glowing, rainbow) and leave a
 * trail (bubbles, water, fire...). The picture work happens here on plain pixel arrays, so it can be tested without
 * Minecraft; {@link Skins} hands the results to the game.
 *
 * An animated cape is a picture with its frames stacked top to bottom, each one a normal cape (twice as wide as it
 * is tall), the same way MinecraftCapes does it. It plays at 10 frames a second.
 */
public final class CapeEffects {
    private CapeEffects() {
    }

    /** How long each frame of an animated cape shows, in milliseconds. */
    public static final int FRAME_MS = 100;

    /** Every effect, in the order the wardrobe shows them. */
    public enum Effect {
        ENCHANTED("Enchanted", false), GLOW("Glow", false), RAINBOW("Rainbow", false),
        BUBBLES("Bubbles", true), WATER("Water", true), FIRE("Fire", true), SPARKLES("Sparkles", true),
        HEARTS("Hearts", true), SNOW("Snow", true);

        public final String label;
        /** Whether it's a trail of particles behind the player, instead of a change to the cape itself. */
        public final boolean trail;

        Effect(String label, boolean trail) {
            this.label = label;
            this.trail = trail;
        }

        /** Its name in squid-skins.json, like "sparkles". */
        public String id() {
            return name().toLowerCase();
        }

        /** The effect with this id, or null if Squid doesn't know it (a newer Squid's effect, say). */
        public static Effect byId(String id) {
            for (Effect effect : values()) {
                if (effect.id().equals(id)) return effect;
            }
            return null;
        }
    }

    /** The effects in this list of ids that Squid knows, in the wardrobe's order, each once. */
    public static List<Effect> parse(Collection<String> ids) {
        List<Effect> effects = new ArrayList<>();
        for (Effect effect : Effect.values()) {
            if (ids.contains(effect.id())) effects.add(effect);
        }
        return effects;
    }

    /** How many frames a cape picture holds: 1 for a normal cape, more for an animated one. 0 if it isn't a cape. */
    public static int frames(int width, int height) {
        if (width < 64 || width % 64 != 0) return 0;
        int frameHeight = width / 2;
        if (height % frameHeight != 0) return 0;
        int frames = height / frameHeight;
        return frames == 2 ? 0 : frames; // two frames would be square, which is a skin
    }

    /** Which frame shows at this moment. */
    public static int frameAt(int frames, long millis) {
        return frames <= 1 ? 0 : (int) ((millis / FRAME_MS) % frames);
    }

    /** One frame of an animated cape, cut out of the whole picture (pixels in rows, top to bottom). */
    public static int[] frame(int[] picture, int width, int frame) {
        int frameHeight = width / 2;
        int[] out = new int[width * frameHeight];
        System.arraycopy(picture, frame * width * frameHeight, out, 0, out.length);
        return out;
    }

    /** Whether the cape's picture changes over time, so it has to be drawn again and again. */
    public static boolean moving(int frames, Collection<Effect> effects) {
        return frames > 1 || effects.contains(Effect.RAINBOW);
    }

    /**
     * Applies the effects that change the picture itself (just Rainbow: the others are drawn by the game or are
     * trails). Pixels are 0xAARRGGBB, and see-through ones stay see-through.
     */
    public static void paint(int[] pixels, int width, Collection<Effect> effects, long millis) {
        if (!effects.contains(Effect.RAINBOW)) return;
        int height = pixels.length / width;
        float shift = (millis % 3000) / 3000f; // the colors go all the way around every 3 seconds
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int argb = pixels[i];
                int alpha = argb >>> 24;
                if (alpha == 0) continue;
                // Keep the cape's light and dark parts, and color them with a rainbow that slides along it
                float light = (0.30f * ((argb >> 16) & 0xFF) + 0.59f * ((argb >> 8) & 0xFF) + 0.11f * (argb & 0xFF)) / 255f;
                float hue = (x / (float) width + y / (float) height * 0.5f + shift) % 1f;
                int rgb = java.awt.Color.HSBtoRGB(hue, 0.75f, 0.35f + 0.65f * light) & 0xFFFFFF;
                pixels[i] = (alpha << 24) | rgb;
            }
        }
    }
}
