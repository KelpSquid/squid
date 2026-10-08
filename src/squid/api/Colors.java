package squid.api;

import squid.Lang;

import java.util.Locale;
import java.util.Map;

/**
 * Colors for drawing, written the easy way: a name like "red", "gold" or "light_blue" (Minecraft's dye and chat
 * colors, and a few more), "#FF8800", or a number like 0xFF8800. A number without see-through (0xRRGGBB) is solid;
 * 0x80FF0000 is half see-through red.
 */
public final class Colors {
    private Colors() {
    }

    private static final Map<String, Integer> NAMES = Map.ofEntries(
            Map.entry("white", 0xFFFFFF), Map.entry("black", 0x000000), Map.entry("red", 0xFF5555),
            Map.entry("dark_red", 0xAA0000), Map.entry("green", 0x55FF55), Map.entry("dark_green", 0x00AA00),
            Map.entry("blue", 0x5555FF), Map.entry("dark_blue", 0x0000AA), Map.entry("yellow", 0xFFFF55),
            Map.entry("gold", 0xFFAA00), Map.entry("orange", 0xF9801D), Map.entry("aqua", 0x55FFFF),
            Map.entry("dark_aqua", 0x00AAAA), Map.entry("cyan", 0x169C9C), Map.entry("light_blue", 0x3AB3DA),
            Map.entry("purple", 0xAA00AA), Map.entry("light_purple", 0xFF55FF), Map.entry("magenta", 0xC74EBD),
            Map.entry("pink", 0xF38BAA), Map.entry("lime", 0x80C71F), Map.entry("brown", 0x835432),
            Map.entry("gray", 0x808080), Map.entry("grey", 0x808080), Map.entry("dark_gray", 0x555555),
            Map.entry("dark_grey", 0x555555), Map.entry("light_gray", 0xAAAAAA), Map.entry("light_grey", 0xAAAAAA),
            Map.entry("silver", 0xC0C0C0), Map.entry("diamond", 0x4AEDD9), Map.entry("emerald", 0x17DD62),
            Map.entry("redstone", 0xFF0000), Map.entry("lapis", 0x345EC3), Map.entry("netherite", 0x4D494D));

    /** A color as 0xAARRGGBB, from a name, "#RRGGBB", "#AARRGGBB", or a number. Unknown names are explained. */
    public static int of(Object color) {
        if (color instanceof Number n) {
            long value = n.longValue();
            return (value >>> 24) == 0 ? (int) value | 0xFF000000 : (int) value;
        }
        if (color == null) throw new IllegalArgumentException(Lang.t("the color is missing (null). Try \"red\" or 0xFF0000"));
        String text = color.toString().strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        Integer named = NAMES.get(text);
        if (named != null) return named | 0xFF000000;
        String hex = text.startsWith("#") ? text.substring(1) : text.startsWith("0x") ? text.substring(2) : null;
        if (hex != null && (hex.length() == 6 || hex.length() == 8)) {
            try {
                long value = Long.parseLong(hex, 16);
                return hex.length() == 6 ? (int) value | 0xFF000000 : (int) value;
            } catch (NumberFormatException e) {
                // falls through to the explanation
            }
        }
        throw new IllegalArgumentException(Lang.t("Squid doesn't know the color \"{0}\". Try \"red\", \"gold\", \"light_blue\" or 0xFF8800", color));
    }

    /** The same color, this see-through: 0 is invisible, 1 is solid. */
    public static int withAlpha(int argb, double alpha) {
        int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
