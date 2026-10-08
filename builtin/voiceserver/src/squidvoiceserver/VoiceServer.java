package squidvoiceserver;

import net.minecraft.server.level.ServerPlayer;
import squid.Main;
import squid.api.Squid;
import squid.api.SquidMod;
import squidnet.Net;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passes voice chat between players. A player's game sends its voice to the server; the server sends it on to the
 * players who should hear it, and nobody else. The server owner picks who that is in config/squid-voice.properties:
 *
 *   mode=proximity   players near you (within distance blocks, in the same dimension), and your group anywhere
 *   mode=world       everyone on the server
 *   mode=groups      only the people in your group
 *   enabled=false    no voice chat at all
 *
 * Voice never goes to a game without Squid, and the server never keeps any of it.
 *
 * The messages (all on "squid:" channels):
 *   voice        game to server: sequence (2 bytes), then one Squid Voice packet
 *   voice        server to game: speaker id (16), kind (1: 0 near, 1 group/world), sequence (2), x y z (3 floats), packet
 *   voice_config server to game: version (1), enabled (1), mode (1: 0 proximity, 1 world, 2 groups), distance (2), groups (1)
 *   voice_group  game to server: the group name to join, as UTF-8 (empty leaves)
 *   voice_group  server to game: the group you're in now, as UTF-8 (empty for none)
 */
public class VoiceServer implements SquidMod {
    static final int VERSION = 1;
    private static final int MAX_PACKET = 200;

    private volatile Config config;
    private final Map<UUID, String> groups = new ConcurrentHashMap<>();

    record Config(boolean enabled, int mode, int distance, boolean groups) {
    }

    @Override
    public void init(Squid squid) {
        config = loadConfig(Main.gameFolder().resolve("config").resolve("squid-voice.properties"));
        Net.onServer("hello", (player, data) -> Net.toPlayer(player, "voice_config", configMessage()));
        Net.onServer("voice", this::relay);
        Net.onServer("voice_group", (player, data) -> {
            String name = new String(data, StandardCharsets.UTF_8).strip();
            if (name.length() > 24) name = name.substring(0, 24);
            if (name.isEmpty() || !config.groups()) groups.remove(player.getUUID());
            else groups.put(player.getUUID(), name.toLowerCase(java.util.Locale.ROOT));
            Net.toPlayer(player, "voice_group", (groups.containsKey(player.getUUID()) ? name : "").getBytes(StandardCharsets.UTF_8));
        });
        squid.atStart("net.minecraft.server.players.PlayerList", "remove", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                call -> groups.remove(((ServerPlayer) call.args()[0]).getUUID()));
    }

    private byte[] configMessage() {
        Config c = config;
        return new byte[] {VERSION, (byte) (c.enabled() ? 1 : 0), (byte) c.mode(), (byte) (c.distance() >> 8), (byte) c.distance(), (byte) (c.groups() ? 1 : 0)};
    }

    /** A player talked: send it to everyone who should hear it. Runs on the network thread, so it stays quick. */
    void relay(ServerPlayer speaker, byte[] data) {
        Config c = config;
        if (!c.enabled() || data.length < 3 || data.length > MAX_PACKET) return;
        var server = speaker.level().getServer();
        if (server == null) return;
        String group = groups.get(speaker.getUUID());
        double x = speaker.getX();
        double y = speaker.getEyeY();
        double z = speaker.getZ();
        byte[] near = null;
        byte[] far = null;
        for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
            if (listener == speaker || !Net.hasSquid(listener)) continue;
            boolean sameGroup = group != null && group.equals(groups.get(listener.getUUID()));
            int kind = hears(c, speaker, listener, sameGroup);
            if (kind < 0) continue;
            if (kind == 0) {
                if (near == null) near = message(speaker.getUUID(), 0, data, x, y, z);
                Net.toPlayer(listener, "voice", near);
            } else {
                if (far == null) far = message(speaker.getUUID(), 1, data, x, y, z);
                Net.toPlayer(listener, "voice", far);
            }
        }
    }

    /** -1: doesn't hear it; 0: hears it from where the speaker is; 1: hears it as if on a call (group or whole world). */
    static int hears(Config c, ServerPlayer speaker, ServerPlayer listener, boolean sameGroup) {
        if (sameGroup && c.groups()) return 1;
        return switch (c.mode()) {
            case 1 -> 1;
            case 2 -> -1;
            default -> listener.level() == speaker.level() && listener.distanceToSqr(speaker) <= (double) c.distance() * c.distance() ? 0 : -1;
        };
    }

    static byte[] message(UUID speaker, int kind, byte[] data, double x, double y, double z) {
        ByteBuffer out = ByteBuffer.allocate(16 + 1 + 12 + data.length);
        out.putLong(speaker.getMostSignificantBits()).putLong(speaker.getLeastSignificantBits());
        out.put((byte) kind);
        out.put(data, 0, 2); // the sequence number
        out.putFloat((float) x).putFloat((float) y).putFloat((float) z);
        out.put(data, 2, data.length - 2);
        return out.array();
    }

    /** Reads the server owner's settings, writing the file with the defaults the first time. */
    static Config loadConfig(Path file) {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException e) {
                System.out.println("[Squid Voice] Couldn't read " + file + ": " + e.getMessage());
            }
        } else {
            try {
                Files.createDirectories(file.getParent());
                try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    out.write("""
                            # Squid Voice: voice chat for players who have Squid.
                            # enabled: true or false
                            # mode: proximity (players near each other), world (everyone), or groups (only people in the same group)
                            # distance: how far voices carry in proximity mode, in blocks (8 to 128)
                            # groups: whether players can make groups (a group hears each other anywhere)
                            enabled=true
                            mode=proximity
                            distance=48
                            groups=true
                            """);
                }
            } catch (IOException e) {
                System.out.println("[Squid Voice] Couldn't write " + file + ": " + e.getMessage());
            }
        }
        int mode = switch (p.getProperty("mode", "proximity").strip().toLowerCase(java.util.Locale.ROOT)) {
            case "world" -> 1;
            case "groups" -> 2;
            default -> 0;
        };
        int distance;
        try {
            distance = Math.clamp(Integer.parseInt(p.getProperty("distance", "48").strip()), 8, 128);
        } catch (NumberFormatException e) {
            distance = 48;
        }
        return new Config(!"false".equalsIgnoreCase(p.getProperty("enabled", "true").strip()), mode, distance,
                !"false".equalsIgnoreCase(p.getProperty("groups", "true").strip()));
    }
}
