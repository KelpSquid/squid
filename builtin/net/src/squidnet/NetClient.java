package squidnet;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;

/**
 * The game's side of Squid's messages. Kept apart from {@link Net} because a server doesn't have the game's classes.
 */
public final class NetClient {
    private static volatile boolean serverHasSquid;

    private NetClient() {
    }

    static void init() {
        // The server said hello: answer, and from now on it's fine to send it Squid messages
        Net.onClient("hello", data -> {
            serverHasSquid = true;
            toServer("hello", new byte[] {1});
        });
    }

    /** Whether the server the game is on has Squid (false on plain Minecraft servers, and in menus). */
    public static boolean serverHasSquid() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) serverHasSquid = false;
        return serverHasSquid;
    }

    /** Sends a message to the server (only once it said it has Squid). */
    public static void toServer(String channel, byte[] data) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        if (!serverHasSquid && !channel.equals("hello")) return;
        connection.send(new ServerboundCustomPayloadPacket(new SquidPayload(Identifier.fromNamespaceAndPath("squid", channel), data)));
    }
}
