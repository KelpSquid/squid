package squidemotes;

import net.minecraft.server.level.ServerPlayer;
import squid.Main;
import squid.api.Squid;
import squid.api.SquidMod;
import squidnet.Net;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emotes: press J for the emote wheel and pick one (wave, love, laugh, GG...). It pops up above your head, with its
 * particles, for everyone near you whose game has Squid. They're always one of the eight below, never typed words,
 * so they're safe for anyone to see.
 *
 * This part runs on the server (and on a single player or LAN game's built-in server): it passes each emote on to
 * the players near enough to see it, at most one a second from each player. {@link EmotesClient} is the game's side.
 *
 * The messages (on "squid:emote"): game to server, the emote's number (1 byte); server to game, who it's from
 * (16 bytes) and the emote's number (1 byte).
 */
public class Emotes implements SquidMod {
    /** Each emote: its name, the particle it shows, and the word that pops up. */
    static final String[][] ALL = {
            {"Wave", "happy_villager", "Hi!"},
            {"Love", "heart", "<3"},
            {"Laugh", "note", "Haha!"},
            {"GG", "totem_of_undying", "GG!"},
            {"Angry", "angry_villager", "Grr!"},
            {"Sad", "falling_water", ":("},
            {"Wow", "end_rod", "Wow!"},
            {"Sleepy", "cloud", "Zzz"}};
    /** How far away emotes are seen, in blocks. */
    static final double RANGE = 48;

    private final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();

    @Override
    public void init(Squid squid) {
        Net.onServer("emote", this::relay);
        squid.atStart("net.minecraft.server.players.PlayerList", "remove", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                call -> lastSent.remove(((ServerPlayer) call.args()[0]).getUUID()));
        if (!Main.isServer()) EmotesClient.init(squid);
    }

    /** A player picked an emote: everyone with Squid near them sees it (them too). Runs on the network thread. */
    void relay(ServerPlayer from, byte[] data) {
        if (data.length != 1 || data[0] < 0 || data[0] >= ALL.length) return;
        // Someone hiding (watching as a spectator, or invisible) mustn't give away where they are
        if (from.isSpectator() || from.isInvisible()) return;
        if (!allowed(lastSent, from.getUUID(), System.currentTimeMillis())) return;
        var server = from.level().getServer();
        if (server == null) return;
        byte[] message = message(from.getUUID(), data[0]);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == from.level() && player.distanceToSqr(from) <= RANGE * RANGE && Net.hasSquid(player)) {
                Net.toPlayer(player, "emote", message);
            }
        }
    }

    /** Whether a player may send an emote now: one a second at most, so nobody can flood everyone's screen. */
    static boolean allowed(Map<UUID, Long> lastSent, UUID player, long now) {
        Long last = lastSent.get(player);
        if (last != null && now - last < 1000) return false;
        lastSent.put(player, now);
        return true;
    }

    /** The server's message to the game: who sent it, and which emote. */
    static byte[] message(UUID who, int emote) {
        return ByteBuffer.allocate(17).putLong(who.getMostSignificantBits()).putLong(who.getLeastSignificantBits()).put((byte) emote).array();
    }

    /** Who a message from the server is from. */
    static UUID who(byte[] message) {
        ByteBuffer in = ByteBuffer.wrap(message);
        return new UUID(in.getLong(), in.getLong());
    }

    /** Which emote a message from the server is, or -1 if it isn't one Squid knows (from a newer Squid, say). */
    static int emote(byte[] message) {
        if (message.length != 17) return -1;
        int emote = message[16];
        return emote >= 0 && emote < ALL.length ? emote : -1;
    }
}
