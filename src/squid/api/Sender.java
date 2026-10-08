package squid.api;

import java.util.UUID;

/**
 * Who sent a mod a message (see {@link Squid#onMessage}): the server, or a player (when the mod runs on a server, or
 * in single player, where your world's server runs in your game). Written as text it's their name.
 */
public final class Sender {
    private final String modId;
    private final String name;
    private final Object player;
    private final UUID id;

    /** Squid makes these as messages arrive. */
    public Sender(String modId, String name, Object player, UUID id) {
        this.modId = modId;
        this.name = name;
        this.player = player;
        this.id = id;
    }

    /** The player's name, or "server". */
    public String name() {
        return name;
    }

    /** Whether the message came from the server (to the game), rather than from a player (to the server). */
    public boolean isServer() {
        return player == null;
    }

    /** The player who sent it, as Minecraft's ServerPlayer, or null if it came from the server. */
    public Object player() {
        return player;
    }

    /** The player's id, or null if it came from the server. */
    public UUID id() {
        return id;
    }

    /** Answers on a channel: to the server if it came from the server, or back to the player who sent it. */
    public boolean reply(String channel, Object data) {
        return isServer() ? squid.ModNet.send(modId, channel, data) : squid.ModNet.sendTo(modId, this, channel, data);
    }

    @Override
    public String toString() {
        return name;
    }
}
