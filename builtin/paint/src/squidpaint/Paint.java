package squidpaint;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.Resource;
import squid.api.Squid;
import squid.api.SquidMod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Block Painter (and the Sound Swapper, see {@link SoundSwapScreen}): repaint any block's texture inside the game. Painted textures go into a resource pack Squid
 * makes and keeps for you ("Squid Paint", in the instance's resourcepacks folder), which is switched on and
 * reloaded on every save, so the world changes right away. Reset puts Minecraft's own texture back.
 */
public class Paint implements SquidMod {
    static final String PACK = "Squid Paint";

    @Override
    public void init(Squid squid) {
        squid.addMenuButton("Block Painter", false, menu -> Minecraft.getInstance().setScreenAndShow(new BlockPickScreen(menu)));
        squid.addMenuButton("Sound Swapper", false, menu -> Minecraft.getInstance().setScreenAndShow(new SoundSwapScreen(menu)));
    }

    /** The pack's folder. It's only made (by {@link #pack()}) when something is saved into it. */
    static Path folder() {
        return Minecraft.getInstance().getResourcePackDirectory().resolve(PACK);
    }

    /**
     * The pack's folder, made with its pack.mcmeta. The pack.mcmeta is written again whenever Minecraft's version
     * of it changes, and it says it fits every 97.x (not just the one it was made in), so a game update doesn't
     * switch the pack off and hide all your paintings.
     */
    static Path pack() throws IOException {
        Path folder = folder();
        Files.createDirectories(folder);
        Path meta = folder.resolve("pack.mcmeta");
        int major = SharedConstants.RESOURCE_PACK_FORMAT_MAJOR;
        int minor = SharedConstants.RESOURCE_PACK_FORMAT_MINOR;
        String wanted = """
                {
                    "pack": {
                        "description": "Made with Squid's Block Painter and Sound Swapper",
                        "min_format": [%d, %d],
                        "max_format": %d
                    }
                }
                """.formatted(major, minor, major);
        if (!Files.exists(meta) || !Files.readString(meta, StandardCharsets.UTF_8).equals(wanted)) {
            Files.writeString(meta, wanted, StandardCharsets.UTF_8);
        }
        return folder;
    }

    /** Where a texture like minecraft:block/stone goes in the pack. */
    static Path file(Identifier texture) {
        return folder().resolve("assets").resolve(texture.getNamespace()).resolve("textures").resolve(texture.getPath() + ".png");
    }

    /** The texture's settings file next to it (animation, how it looks far away), like stone.png.mcmeta. */
    static Path settingsFile(Identifier texture) {
        Path png = file(texture);
        return png.resolveSibling(png.getFileName() + ".mcmeta");
    }

    /** Whether a texture has been painted (it's in the pack). */
    static boolean painted(Identifier texture) {
        return Files.exists(file(texture));
    }

    /**
     * Minecraft's own settings for a texture (its .png.mcmeta: animation frames, how leaves and glass look far away),
     * from the packs below this one. Null if it has none. Minecraft only looks for them in the pack the picture
     * comes from, so a painted texture needs its own copy.
     */
    static byte[] originalSettings(Identifier texture) {
        return lastNotOurs(texture.withPath("textures/" + texture.getPath() + ".png.mcmeta"));
    }

    /** Minecraft's own texture, from the game and its resource packs other than this one. Null if there's none. */
    static byte[] original(Identifier texture) {
        return lastNotOurs(texture.withPath("textures/" + texture.getPath() + ".png"));
    }

    private static byte[] lastNotOurs(Identifier path) {
        try {
            // The stack goes from the bottom pack (the game) to the top one: the last one that isn't ours is what
            // shows without the painting, your other resource packs included
            Resource shown = null;
            for (Resource resource : Minecraft.getInstance().getResourceManager().getResourceStack(path)) {
                if (!resource.sourcePackId().endsWith(PACK)) shown = resource;
            }
            if (shown == null) return null;
            try (InputStream in = shown.open()) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** What it looks like now: the painted texture if there is one, else the original. */
    static byte[] current(Identifier texture) {
        try {
            Path file = file(texture);
            if (Files.exists(file)) return Files.readAllBytes(file);
        } catch (IOException e) {
            // fall back to the original
        }
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager()
                .getResource(texture.withPath("textures/" + texture.getPath() + ".png"));
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open()) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** Every block texture there is, like minecraft:block/stone, by name. */
    static List<Identifier> blockTextures() {
        return textures("block");
    }

    /** Every item texture, like minecraft:item/diamond_sword. */
    static List<Identifier> itemTextures() {
        return textures("item");
    }

    private static List<Identifier> textures(String kind) {
        List<Identifier> found = new ArrayList<>();
        for (Identifier id : Minecraft.getInstance().getResourceManager()
                .listResources("textures/" + kind, id -> id.getPath().endsWith(".png")).keySet()) {
            String path = id.getPath();
            found.add(id.withPath(path.substring("textures/".length(), path.length() - ".png".length())));
        }
        found.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        return found;
    }

    /**
     * Switches the pack on and puts it on top of your other packs (so the painting wins, even over packs you turned
     * on later), then reloads the game's resources, so painted blocks show right away.
     */
    static void apply() {
        Minecraft minecraft = Minecraft.getInstance();
        PackRepository repository = minecraft.getResourcePackRepository();
        repository.reload();
        String id = "file/" + PACK;
        List<String> selected = new ArrayList<>(repository.getSelectedIds());
        boolean onTop = !selected.isEmpty() && selected.getLast().equals(id);
        if (!onTop && repository.getAvailableIds().contains(id)) {
            selected.remove(id);
            selected.add(id); // the last one is on top
            repository.setSelected(selected);
            minecraft.options.updateResourcePacks(repository); // saves, and reloads because the list changed
        } else {
            minecraft.reloadResourcePacks();
        }
    }
}
