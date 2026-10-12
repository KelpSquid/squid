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

    /**
     * A player joined a group (or left theirs, with an empty name). Everyone in the group they left and the group they
     * joined is sent who's in it now, so nobody can listen in on a group without its members seeing their name.
     */
    static void joinGroup(VoiceServer voice, Object who, byte[] data) {
        ServerPlayer player = (ServerPlayer) who;
        if (!voice.groupLimit.allow(player.getUUID())) return; // joining over and over would spam every member
        String name = new String(data, StandardCharsets.UTF_8).strip();
        if (name.length() > 24) name = name.substring(0, 24);
        String before = voice.groups.get(player.getUUID());
        if (name.isEmpty() || !voice.config.groups()) voice.groups.remove(player.getUUID());
        else voice.groups.put(player.getUUID(), name.toLowerCase(java.util.Locale.ROOT));
        Net.toPlayer(player, "voice_group", (voice.groups.containsKey(player.getUUID()) ? name : "").getBytes(StandardCharsets.UTF_8));
        var server = player.level().getServer();
        if (server == null) return;
        if (before != null) sendMembers(voice, server, before);
        String now = voice.groups.get(player.getUUID());
        if (now != null && !now.equals(before)) sendMembers(voice, server, now);
        if (now == null) Net.toPlayer(player, "voice_members", new byte[0]);
    }

    /** A player left the server: they're out of their group, and the rest of it is told. */
    static void left(VoiceServer voice, Object who) {
        ServerPlayer player = (ServerPlayer) who;
        voice.limit.forget(player.getUUID());
        voice.groupLimit.forget(player.getUUID());
        String group = voice.groups.remove(player.getUUID());
        var server = player.level().getServer();
        if (group != null && server != null) {
            for (ServerPlayer member : members(voice, server, group)) {
                if (member != player) Net.toPlayer(member, "voice_members", memberNames(voice, server, group, player));
            }
        }
    }

    private static java.util.List<ServerPlayer> members(VoiceServer voice, net.minecraft.server.MinecraftServer server, String group) {
        java.util.List<ServerPlayer> out = new java.util.ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (group.equals(voice.groups.get(p.getUUID()))) out.add(p);
        }
        return out;
    }

    /** The names in a group, one per line (leaving out someone on their way out of the server). */
    private static byte[] memberNames(VoiceServer voice, net.minecraft.server.MinecraftServer server, String group, ServerPlayer leaving) {
        StringBuilder names = new StringBuilder();
        for (ServerPlayer p : members(voice, server, group)) {
            if (p == leaving) continue;
            if (!names.isEmpty()) names.append('\n');
            names.append(p.getGameProfile().name());
        }
        return names.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Tells everyone in a group who's in it now. */
    private static void sendMembers(VoiceServer voice, net.minecraft.server.MinecraftServer server, String group) {
        byte[] names = memberNames(voice, server, group, null);
        for (ServerPlayer member : members(voice, server, group)) Net.toPlayer(member, "voice_members", names);
    }

    /** A player talked: send it to everyone who should hear it. Runs on the network thread, so it stays quick. */
    static void relay(VoiceServer voice, Object from, byte[] data) {
        ServerPlayer speaker = (ServerPlayer) from;
        VoiceServer.Config c = voice.config;
        Map<UUID, String> groups = voice.groups;
        if (!c.enabled() || data.length < 3 || data.length > VoiceServer.MAX_PACKET) return;
        // One packet is 20 ms of voice, so about 50 a second; more than that is someone flooding everyone
        if (!voice.limit.allow(speaker.getUUID())) return;
        var server = speaker.level().getServer();
        if (server == null) return;
        String group = groups.get(speaker.getUUID());
        // Someone hiding (watching as a spectator, or invisible) mustn't give away where they are, like emotes
        boolean hiding = speaker.isSpectator() || speaker.isInvisible();
        byte[] near = null;
        byte[] far = null;
        for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
            if (listener == speaker || !Net.hasSquid(listener)) continue;
            boolean sameGroup = group != null && group.equals(groups.get(listener.getUUID()));
            int kind = hears(c, speaker, listener, sameGroup);
            if (kind < 0 || kind == 0 && hiding) continue;
            if (kind == 0) {
                if (near == null) near = VoiceServer.message(speaker.getUUID(), 0, data, speaker.getX(), speaker.getEyeY(), speaker.getZ());
                Net.toPlayer(listener, "voice", near);
            } else {
                // Heard as if on a call, so the game never uses the place: it isn't sent (it would show where they are)
                if (far == null) far = VoiceServer.message(speaker.getUUID(), 1, data, 0, 0, 0);
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
