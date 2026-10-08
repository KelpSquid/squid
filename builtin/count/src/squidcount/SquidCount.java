package squidcount;

import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import squid.Lang;
import squid.Main;
import squid.api.Hud;
import squid.api.Squid;
import squid.api.SquidMod;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The Squid Count: like gamerscore, but for advancements. Every advancement you earn while playing with Squid
 * adds points (10, or 25 for a goal, 50 for a challenge), each one only once ever. It's kept per player,
 * in Kelp's folder, so it adds up across all your instances.
 */
public class SquidCount implements SquidMod {
    private CountFile count;

    @Override
    public void init(Squid squid) {
        count = CountFile.load(countFile());

        // The game tells itself about advancements in batches. The first batch when you join a world (a "reset")
        // is everything already done there, so it's skipped: only ones that become done while you play count.
        squid.atEnd("net.minecraft.client.multiplayer.ClientAdvancements", "update", call -> {
            ClientboundUpdateAdvancementsPacket packet = (ClientboundUpdateAdvancementsPacket) call.args()[0];
            if (!packet.shouldReset()) earned((ClientAdvancements) call.self(), packet);
        });

        // The total, top-left of the title screen
        squid.atEnd("net.minecraft.client.gui.screens.TitleScreen", "extractRenderState", call -> {
            Minecraft minecraft = Minecraft.getInstance();
            CountFile.Player player = count.player(minecraft.getUser().getProfileId().toString().replace("-", ""));
            new Hud(call.args()[0]).text(Lang.t("Squid Count: {0}", player.points), 2, 2, 0xFFFFAA00);
        });
    }

    private void earned(ClientAdvancements advancements, ClientboundUpdateAdvancementsPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        String uuid = minecraft.player.getUUID().toString().replace("-", "");
        String name = minecraft.player.getName().getString();
        boolean changed = false;
        for (Map.Entry<Identifier, AdvancementProgress> entry : packet.progress().entrySet()) {
            if (!entry.getValue().isDone()) continue;
            AdvancementHolder holder = advancements.get(entry.getKey());
            if (holder == null) continue;
            Optional<DisplayInfo> display = holder.value().display();
            if (display.isEmpty()) continue; // recipes and other hidden bookkeeping aren't real advancements
            int points = count.earn(uuid, name, entry.getKey().toString(), CountFile.points(typeName(display.get().type())));
            if (points > 0) {
                changed = true;
                int total = count.player(uuid).points;
                minecraft.gui.chatListener().handleOverlay(Component.literal(Lang.t("+{0} Squid Count  ({1} total)", points, total))
                        .withStyle(ChatFormatting.GOLD));
            }
        }
        if (changed) {
            try {
                count.save();
            } catch (Exception e) {
                System.out.println("[Squid Count] Couldn't save: " + e.getMessage());
            }
        }
    }

    private static String typeName(AdvancementType type) {
        if (type == AdvancementType.GOAL) return "goal";
        if (type == AdvancementType.CHALLENGE) return "challenge";
        return "task";
    }

    /**
     * squid-count.json in Kelp's folder, which Kelp passes as -Dsquid.home. Without it, two folders up from the
     * instance (Kelp/instances/&lt;instance&gt;), or the game folder itself.
     */
    public static Path countFile() {
        String home = System.getProperty("squid.home");
        if (home != null) return Path.of(home, "squid-count.json");
        Path game = Main.gameFolder().toAbsolutePath();
        if (game.getParent() != null && game.getParent().getFileName() != null
                && game.getParent().getFileName().toString().equals("instances") && game.getParent().getParent() != null) {
            return game.getParent().getParent().resolve("squid-count.json");
        }
        return game.resolve("squid-count.json");
    }
}
