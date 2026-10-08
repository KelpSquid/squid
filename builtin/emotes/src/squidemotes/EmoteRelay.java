package squidemotes;

import net.minecraft.server.level.ServerPlayer;
import squidnet.Net;

import java.util.Map;
import java.util.UUID;

/**
 * The server's side of {@link Emotes}: passes each emote on to the players near enough to see it. Its own class so it
 * only loads when the first emote comes in: it names Minecraft's player classes, which mustn't load while Squid starts.
 */
final class EmoteRelay {
    private EmoteRelay() {
    }

    /** A player picked an emote: everyone with Squid near them sees it (them too). Runs on the network thread. */
    static void relay(Map<UUID, Long> lastSent, Object sender, byte[] data) {
        ServerPlayer from = (ServerPlayer) sender;
        if (data.length != 1 || data[0] < 0 || data[0] >= Emotes.ALL.length) return;
        // Someone hiding (watching as a spectator, or invisible) mustn't give away where they are
        if (from.isSpectator() || from.isInvisible()) return;
        if (!Emotes.allowed(lastSent, from.getUUID(), System.currentTimeMillis())) return;
        var server = from.level().getServer();
        if (server == null) return;
        byte[] message = Emotes.message(from.getUUID(), data[0]);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == from.level() && player.distanceToSqr(from) <= Emotes.RANGE * Emotes.RANGE && Net.hasSquid(player)) {
                Net.toPlayer(player, "emote", message);
            }
        }
    }

    /** A player left: forget when they last sent one. */
    static void forget(Map<UUID, Long> lastSent, Object player) {
        lastSent.remove(((ServerPlayer) player).getUUID());
    }
}
