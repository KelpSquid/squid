package squid;

import squid.api.ModScreen;

import java.util.function.Consumer;

/**
 * Opens mods' own screens (squid.api.ModScreen). Squid is built without Minecraft, so Squid Mods, which is built with
 * it, gives the code that makes a real Minecraft screen from one.
 */
public final class Screens {
    private Screens() {
    }

    /** Set by Squid Mods as it starts. */
    public static volatile Consumer<ModScreen> opener;

    /** A mod's screen kept breaking: Kelp hears about it like a broken hook. */
    public static void problem(String modId, Throwable e) {
        Main.hookProblem(modId, Main.describe(e));
    }
}
