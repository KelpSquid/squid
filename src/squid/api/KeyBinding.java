package squid.api;

import java.lang.reflect.Method;

/**
 * A key a mod added to Minecraft's Controls screen. Players can change it there,
 * and Minecraft saves it with the rest of their keys. Made with {@link Squid#addKeyBinding}.
 */
public final class KeyBinding {
    private final String name;
    private final int defaultKey;
    private volatile Object mapping; // Minecraft's own KeyMapping, made when Minecraft loads its options
    private volatile Method isDown;
    private volatile Method consumeClick;

    public KeyBinding(String name, int defaultKey) {
        this.name = name;
        this.defaultKey = defaultKey;
    }

    /** What the Controls screen calls it. */
    public String name() {
        return name;
    }

    /** The key it starts on, as one of Minecraft's key codes like InputConstants.KEY_Z. */
    public int defaultKey() {
        return defaultKey;
    }

    /** Whether the key is held down right now. Always false while a menu or the chat is open. */
    public boolean isDown() {
        Object m = mapping;
        if (m == null) return false; // Minecraft hasn't loaded its options yet
        try {
            return (boolean) isDown.invoke(m);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Couldn't check the " + name + " key", e);
        }
    }

    /**
     * Whether the key was pressed since the last check. Each press counts once, so it's good for
     * switching something on and off. Check it in {@link Squid#onTick}.
     */
    public boolean pressed() {
        Object m = mapping;
        if (m == null) return false;
        try {
            return (boolean) consumeClick.invoke(m);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Couldn't check the " + name + " key", e);
        }
    }

    /** Squid calls this once it has made the matching Minecraft KeyMapping. */
    public void attach(Object mapping) throws ReflectiveOperationException {
        this.isDown = mapping.getClass().getMethod("isDown");
        this.consumeClick = mapping.getClass().getMethod("consumeClick");
        this.mapping = mapping;
    }

    public Object mapping() {
        return mapping;
    }
}
