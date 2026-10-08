package coords;

import squid.api.EasyMod;

/** Where you are (X, Y and Z) and the biome you're in, always in the top-left corner. */
public class Coords extends EasyMod {
    void start() {
        setting("Show biome", true); // so the setting is in the Mods screen right away
        keepShowing(() -> "XYZ: " + x() + " " + y() + " " + z());
        keepShowing(() -> setting("Show biome", true) ? biome().replace('_', ' ') : "");
    }
}
