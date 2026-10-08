package squidvoice;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import squid.Lang;
import squid.Main;
import squid.api.Hud;
import squid.api.KeyBinding;
import squid.api.ModSettings;
import squid.api.Squid;
import squid.api.SquidMod;
import squidnet.Net;
import squidnet.NetClient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voice chat on servers that have Squid. Hold V to talk (or pick Always on). You hear players near you, quieter the
 * farther they are and from the side they're on; the server owner can make it the whole server instead, and players
 * can make groups that hear each other anywhere. On servers without Squid nothing happens at all.
 *
 * A parent can turn it off in Kelp's Parent Controls (Kelp starts the game with squid.voice=off), and then the
 * microphone is never opened.
 */
public class Voice implements SquidMod {
    static final String PUSH = "Push to talk";
    static final String ALWAYS = "Always on";
    static final String LISTEN = "Listen only";

    static Voice instance;

    private ModSettings settings;
    private KeyBinding talkKey;
    final Mic mic = new Mic(message -> NetClient.toServer("voice", message));
    final Speakers speakers = new Speakers(Voice::ear);
    final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    /** What the server said about its voice chat, or null if it hasn't (no Squid there, or not joined yet). */
    volatile Config config;
    volatile String group = "";
    private boolean wasTalking;

    record Config(boolean enabled, int mode, int distance, boolean groups) {
    }

    /** Whether a parent turned voice chat off in Kelp. */
    static boolean parentOff() {
        return "off".equalsIgnoreCase(System.getProperty("squid.voice", "on"));
    }

    @Override
    public void init(Squid squid) {
        instance = this;
        settings = squid.settings();
        on(); // so the settings show up in Squid's Mods screen in this order
        talk();
        voiceVolume();
        micVolume();
        showTalking();
        talkKey = squid.addKeyBinding("Talk", InputConstants.KEY_V);
        loadMuted();
        Net.onClient("voice_config", data -> {
            if (data.length < 6 || data[0] < 1) return;
            config = new Config(data[1] != 0, data[2], ((data[3] & 0xFF) << 8) | (data[4] & 0xFF), data[5] != 0);
            speakers.setDistance(config.distance());
        });
        Net.onClient("voice", data -> {
            if (!listening() || data.length < 16) return;
            ByteBuffer b = ByteBuffer.wrap(data);
            if (muted.contains(new UUID(b.getLong(0), b.getLong(8)))) return;
            speakers.received(data);
            speakers.start();
        });
        Net.onClient("voice_group", data -> group = new String(data, StandardCharsets.UTF_8));
        squid.onTick(this::tick);
        squid.onHud(this::hud);
        squid.addMenuButton("Voice Chat", true, menu -> Minecraft.getInstance().setScreenAndShow(new VoiceScreen(menu)));
    }

    String talk() {
        return settings.choice("Talk", PUSH, PUSH, ALWAYS, LISTEN);
    }

    int voiceVolume() {
        return settings.number("Voice volume", 100, 0, 200);
    }

    int micVolume() {
        return settings.number("Mic volume", 100, 0, 200);
    }

    /** The voice changer for your mic: None, Robot, Chipmunk, Giant or Echo. */
    String voiceEffect() {
        return settings.choice("Voice effect", VoiceEffect.NONE, VoiceEffect.ALL);
    }

    void setVoiceEffect(String effect) {
        settings.set("Voice effect", effect);
    }

    boolean showTalking() {
        return settings.toggle("Show who's talking", true);
    }

    boolean on() {
        return settings.toggle("Voice chat", true);
    }

    /** Whether voices are played: voice chat on, and not turned off by a parent. */
    boolean listening() {
        return on() && !parentOff();
    }

    void setTalk(String value) {
        settings.set("Talk", value);
    }

    private static Speakers.Ear ear() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        return new Speakers.Ear(player.getX(), player.getEyeY(), player.getZ(), player.getYRot());
    }

    /** Whether voice chat works where you are: a server with Squid that has it on, and no parent switch. */
    boolean available() {
        Config c = config;
        return listening() && c != null && c.enabled() && NetClient.serverHasSquid();
    }

    private void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null || !listening()) {
            // Left the server: forget it
            if (config != null || mic.isOpen()) {
                config = null;
                group = "";
                mic.close();
                speakers.clear();
            }
            return;
        }
        speakers.setVolume(voiceVolume());
        // The mic only opens when there's someone to talk to
        boolean someoneElse = minecraft.getConnection().getOnlinePlayers().size() > 1;
        boolean micWanted = available() && someoneElse && !LISTEN.equals(talk());
        if (micWanted && !mic.isOpen() && mic.problem() == null) mic.open();
        if (!micWanted && mic.isOpen()) mic.close();
        mic.set(talkKey.isDown(), // (never down while a menu or the chat is open)
                ALWAYS.equals(talk()), micVolume());
        mic.setEffect(voiceEffect());
        // A little click when you start and stop talking with the key, so you know it's on
        boolean talking = mic.talking();
        if (talking != wasTalking && !ALWAYS.equals(talk())) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI((talking ? SoundEvents.NOTE_BLOCK_HAT : SoundEvents.NOTE_BLOCK_BASEDRUM).value(), 1.6f, 0.25f));
        }
        wasTalking = talking;
    }

    private void hud(Hud hud) {
        if (!available()) return;
        int y = hud.height() - 22;
        if (mic.problem() != null && !LISTEN.equals(talk())) {
            hud.text(Lang.t("Voice chat: no microphone ({0})", mic.problem()), 4, y, 0xFFFF7777);
            return;
        }
        if (mic.talking()) {
            // A mic icon (two boxes) and a level bar
            hud.box(4, y - 1, 4, 7, 0xFF55FF55);
            hud.box(3, y + 6, 6, 1, 0xFF55FF55);
            hud.box(10, y + 2, 30, 3, 0x80000000);
            hud.box(10, y + 2, Math.round(30 * mic.level()), 3, 0xFF55FF55);
        }
        if (!showTalking()) return;
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        List<String> names = new ArrayList<>();
        for (Speakers.Speaker s : speakers.all().values()) {
            if (!s.talking()) continue;
            PlayerInfo info = connection.getPlayerInfo(s.id);
            names.add(info == null ? "?" : info.getProfile().name());
        }
        int ty = 4;
        for (String name : names) {
            hud.box(hud.width() - hud.textWidth(name) - 14, ty - 1, hud.textWidth(name) + 12, 11, 0x80000000);
            hud.box(hud.width() - hud.textWidth(name) - 11, ty + 1, 3, 5, 0xFF55FF55);
            hud.text(name, hud.width() - hud.textWidth(name) - 5, ty, 0xFFFFFFFF);
            ty += 12;
        }
    }

    void joinGroup(String name) {
        NetClient.toServer("voice_group", name.strip().getBytes(StandardCharsets.UTF_8));
    }

    void setMuted(UUID id, boolean mute) {
        if (mute) {
            muted.add(id);
            speakers.remove(id);
        } else {
            muted.remove(id);
        }
        saveMuted();
    }

    private static Path mutedFile() {
        return Main.gameFolder().resolve("config").resolve("squid").resolve("squid-voice-muted.txt");
    }

    private void loadMuted() {
        try {
            if (!Files.exists(mutedFile())) return;
            for (String line : Files.readAllLines(mutedFile())) {
                try {
                    if (!line.isBlank()) muted.add(UUID.fromString(line.strip()));
                } catch (IllegalArgumentException ignored) {
                    // not an id: skip it
                }
            }
        } catch (IOException e) {
            System.out.println("[Squid Voice] Couldn't read the muted list: " + e.getMessage());
        }
    }

    private void saveMuted() {
        try {
            Files.createDirectories(mutedFile().getParent());
            List<String> lines = new ArrayList<>();
            for (UUID id : muted) lines.add(id.toString());
            Files.write(mutedFile(), lines);
        } catch (IOException e) {
            System.out.println("[Squid Voice] Couldn't save the muted list: " + e.getMessage());
        }
    }
}
