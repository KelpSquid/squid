package squidemotes;

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
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void init(Squid squid) {
        // Minecraft's player classes aren't named here (the handler takes plain Objects): naming them while Squid
        // starts would load them, and LivingEntity with them, before other parts' hooks into those are in
        java.util.function.BiConsumer relay = (Object from, Object data) -> EmoteRelay.relay(lastSent, from, (byte[]) data);
        Net.onServer("emote", relay);
        squid.atStart("net.minecraft.server.players.PlayerList", "remove", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                call -> EmoteRelay.forget(lastSent, call.args()[0]));
        if (!Main.isServer()) EmotesClient.init(squid);
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
