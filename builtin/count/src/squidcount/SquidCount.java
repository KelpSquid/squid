package squidcount;

import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import squid.Events;
import squid.Lang;
import squid.Main;
import squid.api.Hud;
import squid.api.ModSettings;
import squid.api.Squid;
import squid.api.SquidMod;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The Squid Count: like gamerscore, but for advancements. Every advancement you earn while playing with Squid
 * adds points (10, or 25 for a goal, 50 for a challenge), each one only once ever. It's kept per player,
 * in Kelp's folder, so it adds up across all your instances.
 */
public class SquidCount implements SquidMod {
    private CountFile count;
    private ModSettings settings;
    /** The milestone being celebrated, and when it started (ms), or 0 for none. */
    private volatile int celebrating;
    private volatile long celebratedAt;
    /** An achievement earned outside a world, shown when one is joined. */
    private volatile String waitingAchievement;

    @Override
    public void init(Squid squid) {
        count = CountFile.load(countFile());
        settings = squid.settings();
        celebrations();
        squid.onHud(this::hud);
        squid.onTick(this::tick);
        // Squid's own achievements, for making things: other parts of Squid say when one happens
        Events.on("achievement", squid.mod().id(), id -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) minecraft.execute(() -> achieved(String.valueOf(id))); // (none in tests)
        });

        // The game tells itself about advancements in batches. The first batch when you join a world (a "reset")
        // is everything already done there, so it's skipped: only ones that become done while you play count.
        squid.atEnd("net.minecraft.client.multiplayer.ClientAdvancements", "update", call -> {
            ClientboundUpdateAdvancementsPacket packet = (ClientboundUpdateAdvancementsPacket) call.args()[0];
            if (!packet.shouldReset()) earned((ClientAdvancements) call.self(), packet);
        });

        // The total, top-left of the title screen
        squid.atEnd("net.minecraft.client.gui.screens.TitleScreen", "extractRenderState", call -> {
            Minecraft minecraft = Minecraft.getInstance();
            CountFile.Player player = count.player(minecraft.getUser().getProfileId().toString().replace("-", ""));
            new Hud(call.args()[0]).text(Lang.t("Squid Count: {0}", player.points), 2, 2, 0xFFFFAA00);
        });
    }

    private void earned(ClientAdvancements advancements, ClientboundUpdateAdvancementsPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        String uuid = minecraft.player.getUUID().toString().replace("-", "");
        String name = minecraft.player.getName().getString();
        boolean changed = false;
        for (Map.Entry<Identifier, AdvancementProgress> entry : packet.progress().entrySet()) {
            if (!entry.getValue().isDone()) continue;
            AdvancementHolder holder = advancements.get(entry.getKey());
            if (holder == null) continue;
            Optional<DisplayInfo> display = holder.value().display();
            if (display.isEmpty()) continue; // recipes and other hidden bookkeeping aren't real advancements
            int before = count.player(uuid).points;
            int points = count.earn(uuid, name, entry.getKey().toString(), CountFile.points(typeName(display.get().type())));
            if (points > 0) {
                changed = true;
                int total = count.player(uuid).points;
                int milestone = milestoneBetween(before, total);
                if (milestone > 0 && celebrations()) celebrate(milestone);
                minecraft.gui.chatListener().handleOverlay(Component.literal(Lang.t("+{0} Squid Count  ({1} total)", points, total))
                        .withStyle(ChatFormatting.GOLD));
            }
        }
        if (changed) {
            try {
                count.save();
            } catch (Exception e) {
                System.out.println("[Squid Count] Couldn't save: " + e.getMessage());
            }
        }
    }

    /**
     * Squid's own achievements: making things with Squid, not just playing. Each one counts once ever, like an
     * advancement: its id (what the part of Squid that noticed it says), its name, and its points.
     */
    static final String[][] ACHIEVEMENTS = {
            {"paint", "Artist", "25"},           // painted a block, item, painting or mob in the Block Painter
            {"sound", "Sound Designer", "25"},   // swapped a sound in the Sound Swapper
            {"record", "Voice Actor", "25"},     // recorded your own sound
            {"mod", "Modder", "50"},             // made a mod in the Mod Maker that ran
            {"emote", "Hello There", "10"},      // sent an emote
            {"song", "DJ", "10"},                // played your own song in the Jukebox
            {"karaoke", "Karaoke Star", "25"}};  // played a song with lyrics

    /** An achievement happened: its points (the first time only), with its name above the hotbar. */
    private void achieved(String id) {
        String[] achievement = null;
        for (String[] a : ACHIEVEMENTS) if (a[0].equals(id)) achievement = a;
        if (achievement == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        String uuid = minecraft.getUser().getProfileId().toString().replace("-", "");
        String name = minecraft.getUser().getName();
        int before = count.player(uuid).points;
        int points = count.earn(uuid, name, "squid:" + id, Integer.parseInt(achievement[2]));
        if (points <= 0) return;
        int total = count.player(uuid).points;
        int milestone = milestoneBetween(before, total);
        if (milestone > 0 && celebrations()) celebrate(milestone);
        String text = Lang.t("{0}! +{1} Squid Count  ({2} total)", Lang.t(achievement[1]), points, total);
        // Earned outside a world (the Mod Maker or Painter from the title screen): it's shown on joining one
        if (minecraft.level == null) waitingAchievement = text;
        else minecraft.gui.chatListener().handleOverlay(Component.literal(text).withStyle(ChatFormatting.GOLD));
        if (milestone == 0) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.4f, 0.6f));
        }
        try {
            count.save();
        } catch (Exception e) {
            System.out.println("[Squid Count] Couldn't save: " + e.getMessage());
        }
    }

    boolean celebrations() {
        return settings.toggle("Milestone celebrations", true);
    }

    /** The milestones: 50, 100, 250, 500, 1000, then every 500. */
    static boolean isMilestone(int points) {
        return points == 50 || points == 100 || points == 250 || points == 500 || points >= 1000 && points % 500 == 0;
    }

    /** The biggest milestone passed going from `before` to `after` points, or 0 if none. */
    static int milestoneBetween(int before, int after) {
        int found = 0;
        for (int p = before + 1; p <= after; p++) if (isMilestone(p)) found = p;
        return found;
    }

    /** A milestone: a fanfare, fireworks all around you, and a banner for a few seconds. */
    private void celebrate(int milestone) {
        celebrating = milestone;
        celebratedAt = System.currentTimeMillis();
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f));
    }

    private void tick() {
        String waiting = waitingAchievement;
        Minecraft game = Minecraft.getInstance();
        if (waiting != null && game.level != null && game.player != null) {
            waitingAchievement = null;
            game.gui.chatListener().handleOverlay(Component.literal(waiting).withStyle(ChatFormatting.GOLD));
        }
        if (celebrating == 0) return;
        long age = System.currentTimeMillis() - celebratedAt;
        if (age > 5000) {
            celebrating = 0;
            return;
        }
        // Fireworks for the first two seconds: sparks shooting up and out around you (only you see them)
        Minecraft minecraft = Minecraft.getInstance();
        if (age > 2000 || minecraft.player == null || minecraft.level == null) return;
        var random = minecraft.player.getRandom();
        for (int i = 0; i < 6; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double x = minecraft.player.getX() + Math.cos(angle) * 1.5;
            double z = minecraft.player.getZ() + Math.sin(angle) * 1.5;
            minecraft.level.addParticle(net.minecraft.core.particles.ParticleTypes.FIREWORK, x, minecraft.player.getY() + 0.5, z,
                    Math.cos(angle) * 0.1, 0.3 + random.nextDouble() * 0.2, Math.sin(angle) * 0.1);
            if (i % 3 == 0) {
                minecraft.level.addParticle(net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING, x, minecraft.player.getY() + 1.5, z,
                        (random.nextDouble() - 0.5) * 0.5, 0.4, (random.nextDouble() - 0.5) * 0.5);
            }
        }
    }

    /** The banner: "Squid Count 500!" in gold, sliding down from the top. */
    private void hud(Hud hud) {
        int milestone = celebrating;
        if (milestone == 0) return;
        long age = System.currentTimeMillis() - celebratedAt;
        String big = Lang.t("Squid Count {0}!", milestone);
        String small = Lang.t("Milestone reached. Keep going!");
        int width = Math.max(hud.textWidth(big), hud.textWidth(small)) + 24;
        int x = (hud.width() - width) / 2;
        int y = age < 300 ? (int) (-30 + 50 * age / 300) : age > 4600 ? (int) (20 - 50 * (age - 4600) / 400) : 20;
        boolean bright = (age / 250) % 2 == 0 && age < 2000;
        hud.box(x, y, width, 28, 0xD0000000);
        hud.outline(x, y, width, 28, bright ? 0xFFFFFF55 : 0xFFFFAA00);
        hud.centeredText(big, hud.width() / 2, y + 5, bright ? 0xFFFFFF55 : 0xFFFFAA00);
        hud.centeredText(small, hud.width() / 2, y + 16, 0xFFE0E0E0);
    }

    private static String typeName(AdvancementType type) {
        if (type == AdvancementType.GOAL) return "goal";
        if (type == AdvancementType.CHALLENGE) return "challenge";
        return "task";
    }

    /**
     * squid-count.json in Kelp's folder, which Kelp passes as -Dsquid.home. Without it, two folders up from the
     * instance (Kelp/instances/&lt;instance&gt;), or the game folder itself.
     */
    public static Path countFile() {
        String home = System.getProperty("squid.home");
        if (home != null) return Path.of(home, "squid-count.json");
        Path game = Main.gameFolder().toAbsolutePath();
        if (game.getParent() != null && game.getParent().getFileName() != null
                && game.getParent().getFileName().toString().equals("instances") && game.getParent().getParent() != null) {
            return game.getParent().getParent().resolve("squid-count.json");
        }
        return game.resolve("squid-count.json");
    }
}
