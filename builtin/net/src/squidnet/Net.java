package squidnet;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import squid.Main;
import squid.ModNet;
import squid.api.Squid;
import squid.api.SquidMod;

import java.util.Collection;
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
 * Mods' own messages ({@link squid.api.Squid#send}) go on the "squid:mods" channel, through {@link squid.ModNet}.
 */
public class Net implements SquidMod {
    private static final Map<String, List<Consumer<byte[]>>> clientHandlers = new ConcurrentHashMap<>();
    private static final Map<String, List<BiConsumer<ServerPlayer, byte[]>>> serverHandlers = new ConcurrentHashMap<>();
    /** Players (on this server) whose game has Squid. */
    private static final Set<java.util.UUID> squidPlayers = ConcurrentHashMap.newKeySet();
    /**
     * The server's player list, once someone has joined (to find players by name, and send to everyone). Held weakly
     * and let go when the server stops, so a single player world you left isn't kept in memory.
     */
    private static volatile java.lang.ref.WeakReference<PlayerList> playerList = new java.lang.ref.WeakReference<>(null);

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
        squid.atEnd("net.minecraft.server.players.PlayerList", "placeNewPlayer", call -> {
            playerList = new java.lang.ref.WeakReference<>((PlayerList) call.self());
            toPlayer((ServerPlayer) call.args()[1], "hello", new byte[] {1});
        });
        squid.atStart("net.minecraft.server.players.PlayerList", "remove", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                call -> {
                    java.util.UUID id = ((ServerPlayer) call.args()[0]).getUUID();
                    squidPlayers.remove(id);
                    ModNet.forget(id); // their message limits
                });
        // A server stopping (like leaving a single player world) lets go of its players
        squid.atStart("net.minecraft.server.MinecraftServer", "stopServer", call -> {
            playerList = new java.lang.ref.WeakReference<>(null);
            squidPlayers.clear();
        });
        if (!Main.isServer()) NetClient.init();
        // Mods' own messages
        onClient("mods", ModNet::fromServer);
        onServer("mods", fromPlayers());
        ModNet.attach(new ModTransport());
    }

    /**
     * Mods' messages from players, handed to ModNet. Written as a BiConsumer of Object, so starting Squid Net doesn't
     * load ServerPlayer (and with it LivingEntity, which other parts hook): a ServerPlayer-typed lambda would.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BiConsumer<ServerPlayer, byte[]> fromPlayers() {
        BiConsumer<Object, byte[]> handler = ModNet::fromPlayer;
        return (BiConsumer) handler;
    }

    /** How mods' messages get sent, and where they're handled: on the game's thread, or the server's. */
    private static final class ModTransport implements ModNet.Transport {
        @Override
        public boolean toServer(byte[] message) {
            return !Main.isServer() && NetClient.toSquidServer("mods", message);
        }

        @Override
        public void toPlayer(Object player, byte[] message) {
            if (player instanceof ServerPlayer p && hasSquid(p)) Net.toPlayer(p, "mods", message);
        }

        @Override
        public Collection<?> players() {
            PlayerList list = playerList.get();
            if (list == null) return List.of();
            return list.getPlayers().stream().filter(Net::hasSquid).toList();
        }

        @Override
        public Object findPlayer(Object nameOrId) {
            PlayerList list = playerList.get();
            if (nameOrId instanceof ServerPlayer p) return p;
            if (list == null || nameOrId == null) return null;
            if (nameOrId instanceof java.util.UUID id) return list.getPlayer(id);
            return list.getPlayerByName(String.valueOf(nameOrId));
        }

        @Override
        public String nameOf(Object player) {
            return ((ServerPlayer) player).getGameProfile().name();
        }

        @Override
        public java.util.UUID idOf(Object player) {
            return ((ServerPlayer) player).getUUID();
        }

        @Override
        public void onMainThread(Object player, Runnable task) {
            try {
                if (player == null) {
                    NetClient.onGameThread(task);
                    return;
                }
                MinecraftServer server = ((ServerPlayer) player).level().getServer();
                if (server != null) {
                    server.execute(task);
                    return;
                }
            } catch (RuntimeException | LinkageError e) {
                // no game or server to hand it to (like in tests): run it here
            }
            task.run();
        }
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
