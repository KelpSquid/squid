package squid.api;

import java.nio.file.Path;
import java.util.List;

/**
 * A mod's details, read from the squid.json inside its jar.
 *
 * @param main the mod's class that implements {@link SquidMod}
 * @param jar  where the mod's file is
 */
public record ModInfo(String id, String name, String version, String description, List<String> authors,
                      String main, Path jar) {
}
