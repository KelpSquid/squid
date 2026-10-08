package squidreplay;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import squid.Lang;
import squid.api.Game;
import squid.api.KeyBinding;
import squid.api.ModSettings;
import squid.api.Squid;
import squid.api.SquidMod;


/**
 * Skate 3 style replays. While you play, Squid quietly remembers the last few minutes around you: every player, mob
 * and item, how they moved and looked, and every block that changed. Press F9 (or Replay in the pause menu) to rewind
 * and watch it again from any angle, in slow motion or backwards, with its sounds. Nothing is saved to disk yet.
 */
public class Replay implements SquidMod {
    private final Recorder recorder = new Recorder();
    private ModSettings settings;
    private KeyBinding openKey;

    @Override
    public void init(Squid squid) {
        settings = squid.settings();
        openKey = squid.addKeyBinding("Open Replay", InputConstants.KEY_F9);
        squid.onTick(this::tick);

        // Every block that changes in the world you're in
        squid.atStart("net.minecraft.client.multiplayer.ClientLevel", "setBlock",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", call -> {
                    if (Playback.current() != null) return; // the replay putting blocks back isn't something that happened
                    ClientLevel level = (ClientLevel) call.self();
                    BlockPos pos = (BlockPos) call.args()[0];
                    recorder.blockChanged(level, pos, level.getBlockState(pos), (BlockState) call.args()[1]);
                });
        // Every sound, so replays aren't silent
        squid.atStart("net.minecraft.client.sounds.SoundManager", "play",
                "(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", call -> {
                    if (Playback.current() == null) {
                        recorder.soundPlayed(Minecraft.getInstance(), (net.minecraft.client.resources.sounds.SoundInstance) call.args()[0]);
                    }
                });
        // Every particle, so smoke, sparks and splashes come back too
        squid.atStart("net.minecraft.client.particle.ParticleEngine", "createParticle", call -> {
            if (Playback.current() != null) return;
            Object[] a = call.args();
            recorder.particleMade(Minecraft.getInstance(), a[0], (double) a[1], (double) a[2], (double) a[3], (double) a[4], (double) a[5], (double) a[6]);
        });
        // While a replay plays, only its look-alikes are drawn
        squid.atStart("net.minecraft.client.renderer.entity.EntityRenderDispatcher", "shouldRender", call -> {
            if (Playback.hides(call.args()[0])) call.cancel(false);
        });
        // Their arms swing the way they did
        squid.atEnd("net.minecraft.world.entity.LivingEntity", "getSwingAnimation", call -> {
            Float swing = Playback.swingOf(call.self());
            if (swing != null) call.setReturnValue(swing);
        });
        // The replay camera's lens
        squid.atEnd("net.minecraft.client.Camera", "calculateFov", call -> {
            Float fov = Playback.fovOverride();
            if (fov != null) call.setReturnValue(fov);
        });
        squid.addMenuButton("Replay", true, menu -> open());
    }

    private boolean recording() {
        return settings.toggle("Record replays", true);
    }

    private int keepTicks() {
        return settings.number("Minutes kept", 2, 1, 5) * 60 * 20;
    }

    private void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        while (openKey.pressed()) open();
        if (Playback.current() != null) return; // nothing new happens while you watch
        if (recording()) recorder.tick(minecraft, keepTicks());
        else recorder.clear();
    }

    /** Opens the replay editor on what's been recorded so far. */
    void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || Playback.current() != null) return;
        if (recorder.timeline.size() < 20) {
            Game.chat(recording() ? Lang.t("Nothing to replay yet. Play a little first!")
                    : Lang.t("Replays are off. Turn them on in Squid > Mods > Squid Replay > Settings."), "YELLOW");
            minecraft.setScreenAndShow(null);
            return;
        }
        Playback playback = Playback.start(minecraft, recorder.timeline.freeze());
        minecraft.setScreenAndShow(new ReplayScreen(playback));
    }
}
