package squid.api;

import java.nio.file.Path;
import java.util.List;

/**
 * A mod's details, read from the squid.json inside its jar.
 *
 * @param depends   the ids of mods this one needs. Squid starts those first.
 * @param minecraft the Minecraft versions the mod was made for, like "26.3" or "26.3.x". Empty means any.
 * @param main      the mod's class that implements {@link SquidMod}
 * @param jar       where the mod's file is
 */
public record ModInfo(String id, String name, String version, String description, List<String> authors,
                      List<String> depends, List<String> minecraft, String main, Path jar) {
    /** Whether the mod says it works on this Minecraft version. "26.3.x" means 26.3 and every 26.3 update. */
    public boolean worksOn(String minecraftVersion) {
        if (minecraft.isEmpty() || minecraftVersion == null) return true;
        for (String wanted : minecraft) {
            if (wanted.equals(minecraftVersion)) return true;
            if (wanted.endsWith(".x")) {
                String series = wanted.substring(0, wanted.length() - 2);
                if (minecraftVersion.equals(series) || minecraftVersion.startsWith(series + ".")) return true;
            }
        }
        return false;
    }
}
