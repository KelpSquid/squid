package squid.api;

import squid.Lang;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Draws on the screen while playing: boxes, outlines and text. {@link Squid#onHud} hands one to the mod every frame.
 * Positions and sizes are in the game's GUI pixels, so they follow the player's GUI Scale setting.
 * Colors are written 0xAARRGGBB: 0xFFFF0000 is solid red, 0x80000000 is see-through black.
 */
public final class Hud {
    // Squid is built without Minecraft, so it finds the game's drawing methods by name, once
    private static Method guiWidth;
    private static Method guiHeight;
    private static Method fill;
    private static Method text;
    private static Method fontWidth;
    private static Object font;

    private final Object graphics;

    public Hud(Object graphics) {
        this.graphics = graphics;
        if (fill == null) findMethods(graphics);
    }

    private static synchronized void findMethods(Object graphics) {
        try {
            Class<?> g = graphics.getClass();
            ClassLoader loader = g.getClassLoader();
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", true, loader);
            Object instance = minecraft.getMethod("getInstance").invoke(null);
            Field fontField = minecraft.getField("font");
            font = fontField.get(instance);
            Class<?> fontClass = fontField.getType();
            guiWidth = g.getMethod("guiWidth");
            guiHeight = g.getMethod("guiHeight");
            fill = g.getMethod("fill", int.class, int.class, int.class, int.class, int.class);
            text = g.getMethod("text", fontClass, String.class, int.class, int.class, int.class, boolean.class);
            fontWidth = fontClass.getMethod("width", String.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't find Minecraft's drawing methods"), e);
        }
    }

    /** The game's own drawing object (a GuiGraphicsExtractor), for advanced mods that need more than this class offers. */
    public Object graphics() {
        return graphics;
    }

    /** How wide the screen is, in GUI pixels. */
    public int width() {
        return (int) call(guiWidth);
    }

    /** How tall the screen is, in GUI pixels. */
    public int height() {
        return (int) call(guiHeight);
    }

    /** A filled box. */
    public void box(int x, int y, int width, int height, int color) {
        call(fill, x, y, x + width, y + height, color);
    }

    /** A one-pixel border around a box. */
    public void outline(int x, int y, int width, int height, int color) {
        box(x, y, width, 1, color);
        box(x, y + height - 1, width, 1, color);
        box(x, y + 1, 1, height - 2, color);
        box(x + width - 1, y + 1, 1, height - 2, color);
    }

    /** Text with a shadow, starting at x, y. */
    public void text(String text, int x, int y, int color) {
        call(Hud.text, font, text, x, y, color, true);
    }

    /** Text with a shadow, centered on x. */
    public void centeredText(String text, int x, int y, int color) {
        text(text, x - textWidth(text) / 2, y, color);
    }

    /** How wide some text is, in GUI pixels. */
    public int textWidth(String text) {
        try {
            return (int) fontWidth.invoke(font, text);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object call(Method method, Object... args) {
        try {
            return method.invoke(graphics, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't draw on the screen"), e);
        }
    }
}
