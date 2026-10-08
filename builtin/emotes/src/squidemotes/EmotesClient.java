package squidemotes;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import squid.Lang;
import squid.api.Hud;
import squid.api.KeyBinding;
import squid.api.Squid;
import squidnet.Net;
import squidnet.NetClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The game's side of {@link Emotes}: the B key opens the wheel, a picked emote goes to the server, and emotes from
 * players nearby (and your own) show their particles above each one's head for a second and a half, with a little
 * "Steve: Hi!" on the left of the screen for a few seconds.
 */
final class EmotesClient {
    /** An emote being shown: who, which, its particles until when (ticks left), and its words until when. */
    private record Showing(UUID who, int emote, int[] ticksLeft, String name, long wordsUntil) {
    }

    private static KeyBinding key;
    private static final List<Showing> SHOWING = new CopyOnWriteArrayList<>();

    private EmotesClient() {
    }

    static void init(Squid squid) {
        key = squid.addKeyBinding("Emotes", InputConstants.KEY_B);
        // Messages arrive on the network thread: they're shown from the game's own thread
        Net.onClient("emote", data -> Minecraft.getInstance().execute(() -> arrived(data)));
        squid.onTick(EmotesClient::tick);
        squid.onHud(EmotesClient::hud);
    }

    /** Sends a picked emote. Without Squid on the server nobody else would see it, so it's only shown to you. */
    static void send(int emote) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        if (NetClient.serverHasSquid()) {
            NetClient.toServer("emote", new byte[] {(byte) emote});
        } else {
            show(minecraft.player.getUUID(), emote, minecraft.player.getName().getString());
        }
    }

    private static void arrived(byte[] data) {
        int emote = Emotes.emote(data);
        if (emote < 0) return;
        UUID who = Emotes.who(data);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        Player player = minecraft.level.getPlayerByUUID(who);
        String name = player != null ? player.getName().getString() : "?";
        show(who, emote, name);
    }

    private static void show(UUID who, int emote, String name) {
        SHOWING.removeIf(s -> s.who().equals(who)); // a new emote from someone replaces their last one
        SHOWING.add(new Showing(who, emote, new int[] {30}, name, System.currentTimeMillis() + 3000));
    }

    private static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        while (key.pressed()) {
            if (minecraft.gui.screen() == null && minecraft.player != null) minecraft.setScreenAndShow(new EmoteScreen());
        }
        if (minecraft.level == null) {
            SHOWING.clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (Showing s : SHOWING) {
            if (s.ticksLeft()[0] > 0 && s.ticksLeft()[0]-- % 3 == 0) particles(minecraft, s);
            if (s.ticksLeft()[0] <= 0 && now > s.wordsUntil()) SHOWING.remove(s);
        }
    }

    /** A few of the emote's particles just above the player's head. */
    private static void particles(Minecraft minecraft, Showing s) {
        Player player = minecraft.level.getPlayerByUUID(s.who());
        if (player == null) return;
        Object kind = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.withDefaultNamespace(Emotes.ALL[s.emote()][1]));
        if (!(kind instanceof SimpleParticleType particle)) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 2; i++) {
            minecraft.level.addParticle(particle, player.getX() + random.nextDouble(-0.4, 0.4), player.getEyeY() + 0.7 + random.nextDouble(0, 0.3),
                    player.getZ() + random.nextDouble(-0.4, 0.4), random.nextDouble(-0.02, 0.02), random.nextDouble(0.02, 0.06), random.nextDouble(-0.02, 0.02));
        }
    }

    /** "Steve: Hi!" on the left, for a few seconds after each emote. */
    private static void hud(Hud hud) {
        long now = System.currentTimeMillis();
        int y = hud.height() / 2 - 20;
        for (Showing s : SHOWING) {
            if (now > s.wordsUntil()) continue;
            String text = s.name() + ": " + Lang.t(Emotes.ALL[s.emote()][2]);
            hud.box(2, y - 2, hud.textWidth(text) + 6, 12, 0x90000000);
            hud.text(text, 5, y, 0xFFFFFF55);
            y += 13;
        }
    }
}
