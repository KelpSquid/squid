package squid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The buttons in the Squid menu (the one Squid button on Minecraft's title screen and pause menu). Mods add theirs
 * with {@link squid.api.Squid#addMenuButton}; a mod reloaded while the game runs replaces its own, not adds twice.
 */
public final class MenuButtons {
    /** One button: whose it is, what it says (in English: it's translated where it's shown), and what it opens. */
    public record Entry(String modId, String label, boolean inWorldOnly, Consumer<Object> open) {
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private MenuButtons() {
    }

    public static synchronized void add(Entry entry) {
        ENTRIES.put(entry.modId() + "/" + entry.label(), entry);
    }

    /** Every button, in the order they were added. */
    public static synchronized List<Entry> all() {
        return new ArrayList<>(ENTRIES.values());
    }

    /** Takes away a mod's buttons, when it's turned off. */
    public static synchronized void removeMod(String modId) {
        ENTRIES.values().removeIf(e -> e.modId().equals(modId));
    }
}
