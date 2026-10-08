package squidnet;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import squid.Main;
import squid.api.Squid;
import squid.api.SquidMod;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Squid's messages between the game and a server. Minecraft has "custom payload" packets for this, but it throws away
 * the ones it doesn't know. Squid steps in where Minecraft decides that, and keeps every message on a "squid:"
 * channel, on both sides. When a player joins a server with Squid, the server says hello; the game answers, and from
 * then on both know the other has Squid, so nothing is sent to plain Minecraft servers.
 *
 * Other parts of Squid use {@link #onClient}, {@link #onServer}, {@link #toPlayer}, and {@link NetClient#toServer}.
 */
public class Net implements SquidMod {
    private static final Map<String, List<Consumer<byte[]>>> clientHandlers = new ConcurrentHashMap<>();
    private static final Map<String, List<BiConsumer<ServerPlayer, byte[]>>> serverHandlers = new ConcurrentHashMap<>();
    /** Players (on this server) whose game has Squid. */
    private static final Set<java.util.UUID> squidPlayers = ConcurrentHashMap.newKeySet();

    @Override
    public void init(Squid squid) {
        // Keep Squid's channels instead of letting Minecraft throw them away (both sides use this codec)
        squid.atStart("net.minecraft.network.protocol.common.custom.DiscardedPayload", "codec", call -> {
            Identifier id = (Identifier) call.args()[0];
            if ("squid".equals(id.getNamespace())) call.cancel(SquidPayload.codec(id, (int) call.args()[1]));
        });
        // A message from the server, in the game
        squid.atStart("net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl", "handleCustomPayload",
                "(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V", call -> {
                    if (((ClientboundCustomPayloadPacket) call.args()[0]).payload() instanceof SquidPayload p) {
                        for (Consumer<byte[]> handler : clientHandlers.getOrDefault(p.id().getPath(), List.of())) run(() -> handler.accept(p.data()));
                        call.cancel();
                    }
                });
        // A message from a player's game, on the server (these run on the network thread, so handlers must be quick)
        squid.atStart("net.minecraft.server.network.ServerGamePacketListenerImpl", "handleCustomPayload",
                "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V", call -> {
                    if (((ServerboundCustomPayloadPacket) call.args()[0]).payload() instanceof SquidPayload p) {
                        ServerPlayer player = ((ServerGamePacketListenerImpl) call.self()).player;
                        if (p.id().getPath().equals("hello")) squidPlayers.add(player.getUUID());
                        for (BiConsumer<ServerPlayer, byte[]> handler : serverHandlers.getOrDefault(p.id().getPath(), List.of())) {
                            run(() -> handler.accept(player, p.data()));
                        }
                        call.cancel();
                    }
                });
        // Say hello to every player who joins, and forget them when they leave
        squid.atEnd("net.minecraft.server.players.PlayerList", "placeNewPlayer", call -> toPlayer((ServerPlayer) call.args()[1], "hello", new byte[] {1}));
        squid.atStart("net.minecraft.server.players.PlayerList", "remove", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                call -> squidPlayers.remove(((ServerPlayer) call.args()[0]).getUUID()));
        if (!Main.isServer()) NetClient.init();
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.out.println("[Squid Net] A message broke: " + e);
        }
    }

    /** Runs in the game when the server sends a message on a channel ("voice" means "squid:voice"). */
    public static void onClient(String channel, Consumer<byte[]> handler) {
        clientHandlers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** Runs on the server when a player's game sends a message on a channel. */
    public static void onServer(String channel, BiConsumer<ServerPlayer, byte[]> handler) {
        serverHandlers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** Whether a player on this server has Squid in their game. */
    public static boolean hasSquid(ServerPlayer player) {
        return squidPlayers.contains(player.getUUID());
    }

    /** Sends a message to one player's game. */
    public static void toPlayer(ServerPlayer player, String channel, byte[] data) {
        if (player.connection == null) return;
        player.connection.send(new ClientboundCustomPayloadPacket(new SquidPayload(Identifier.fromNamespaceAndPath("squid", channel), data)));
    }
}
