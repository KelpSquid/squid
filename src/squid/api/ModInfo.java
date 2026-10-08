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
    /**
     * Whether the mod says it works on this Minecraft version. "26.3.x" means 26.3 and every 26.3 update, and
     * "&gt;=26.3" means 26.3 and every version after it.
     */
    public boolean worksOn(String minecraftVersion) {
        if (minecraft.isEmpty() || minecraftVersion == null) return true;
        for (String wanted : minecraft) {
            wanted = wanted.trim();
            if (wanted.equals(minecraftVersion) || wanted.equals("*")) return true;
            if (wanted.startsWith(">=") && atLeast(minecraftVersion, wanted.substring(2).trim())) return true;
            if (wanted.endsWith(".x")) {
                String series = wanted.substring(0, wanted.length() - 2);
                if (minecraftVersion.equals(series) || minecraftVersion.startsWith(series + ".")) return true;
            }
        }
        return false;
    }

    /** Whether version is the same as or after oldest, number by number: 26.10 is after 26.9. */
    private static boolean atLeast(String version, String oldest) {
        String[] a = version.split("\\.");
        String[] b = oldest.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? number(a[i]) : 0;
            long y = i < b.length ? number(b[i]) : 0;
            if (x != y) return x > y;
        }
        return true;
    }

    private static long number(String part) {
        String digits = part.replaceAll("\\D.*", "");
        return digits.isEmpty() || digits.length() > 18 ? 0 : Long.parseLong(digits);
    }
}
