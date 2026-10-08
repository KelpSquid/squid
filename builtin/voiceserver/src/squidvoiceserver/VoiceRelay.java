package squidvoiceserver;

import net.minecraft.server.level.ServerPlayer;
import squidnet.Net;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * The work {@link VoiceServer} does with players: passing voices on, groups, and saying hello. Its own class so it
 * only loads when the first message comes in: it names Minecraft's player classes, which mustn't load while Squid
 * starts (other mods' hooks into them go in after this part starts).
 */
final class VoiceRelay {
    private VoiceRelay() {
    }

    /** A game with Squid joined: tell it how voice chat works on this server. */
    static void hello(VoiceServer voice, Object player) {
        Net.toPlayer((ServerPlayer) player, "voice_config", voice.configMessage());
    }

    /** A player joined a group (or left theirs, with an empty name). */
    static void joinGroup(VoiceServer voice, Object who, byte[] data) {
        ServerPlayer player = (ServerPlayer) who;
        String name = new String(data, StandardCharsets.UTF_8).strip();
        if (name.length() > 24) name = name.substring(0, 24);
        if (name.isEmpty() || !voice.config.groups()) voice.groups.remove(player.getUUID());
        else voice.groups.put(player.getUUID(), name.toLowerCase(java.util.Locale.ROOT));
        Net.toPlayer(player, "voice_group", (voice.groups.containsKey(player.getUUID()) ? name : "").getBytes(StandardCharsets.UTF_8));
    }

    /** A player left the server: they're out of their group. */
    static void left(VoiceServer voice, Object player) {
        voice.groups.remove(((ServerPlayer) player).getUUID());
    }

    /** A player talked: send it to everyone who should hear it. Runs on the network thread, so it stays quick. */
    static void relay(VoiceServer voice, Object from, byte[] data) {
        ServerPlayer speaker = (ServerPlayer) from;
        VoiceServer.Config c = voice.config;
        Map<UUID, String> groups = voice.groups;
        if (!c.enabled() || data.length < 3 || data.length > VoiceServer.MAX_PACKET) return;
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
                if (near == null) near = VoiceServer.message(speaker.getUUID(), 0, data, x, y, z);
                Net.toPlayer(listener, "voice", near);
            } else {
                if (far == null) far = VoiceServer.message(speaker.getUUID(), 1, data, x, y, z);
                Net.toPlayer(listener, "voice", far);
            }
        }
    }

    /** -1: doesn't hear it; 0: hears it from where the speaker is; 1: hears it as if on a call (group or whole world). */
    static int hears(VoiceServer.Config c, ServerPlayer speaker, ServerPlayer listener, boolean sameGroup) {
        if (sameGroup && c.groups()) return 1;
        return switch (c.mode()) {
            case 1 -> 1;
            case 2 -> -1;
            default -> listener.level() == speaker.level() && listener.distanceToSqr(speaker) <= (double) c.distance() * c.distance() ? 0 : -1;
        };
    }
}
