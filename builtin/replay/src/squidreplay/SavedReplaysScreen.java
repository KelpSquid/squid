package squidreplay;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import squid.Lang;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * The replays saved in the world and dimension you're in, newest first. Pick one to watch it in the replay editor,
 * or delete it. Replays from other worlds aren't listed, since a replay plays back in the place it happened.
 */
final class SavedReplaysScreen extends Screen {
    private final ReplayScreen editor;
    private List<ReplayFile.Info> saved;
    private String message;

    SavedReplaysScreen(ReplayScreen editor) {
        super(Component.literal(Lang.t("Saved Replays")));
        this.editor = editor;
    }

    @Override
    protected void init() {
        saved = ReplayFile.here(minecraft);
        int y = 34;
        for (ReplayFile.Info info : saved.subList(0, Math.min(saved.size(), Math.max(1, (height - 90) / 24)))) {
            String label = info.saved().replace('T', ' ') + "  (" + (info.ticks() / 20) + "s)";
            addRenderableWidget(Button.builder(Component.literal(label), b -> open(info)).bounds(width / 2 - 120, y, 190, 20).build());
            addRenderableWidget(Button.builder(Component.literal(Lang.t("Delete")), b -> delete(info)).bounds(width / 2 + 74, y, 46, 20).build());
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Back")), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void open(ReplayFile.Info info) {
        try {
            Timeline.Recording recording = ReplayFile.load(minecraft, info.file());
            if (recording.length() < 2) throw new IOException(Lang.t("it's too short"));
            editor.playback().stop();
            Playback playback = Playback.start(minecraft, recording);
            minecraft.setScreenAndShow(new ReplayScreen(playback));
        } catch (IOException | RuntimeException e) {
            message = Lang.t("Couldn't open it: {0}", e.getMessage());
        }
    }

    private void delete(ReplayFile.Info info) {
        try {
            Files.deleteIfExists(info.file());
        } catch (IOException e) {
            message = Lang.t("Couldn't delete it: {0}", e.getMessage());
        }
        rebuildWidgets();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x90000000); // dim the replay behind, so the list is easy to read
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Lang.t("Saved Replays"), width / 2, 12, 0xFFFFFFFF);
        if (saved.isEmpty()) g.centeredText(font, Lang.t("No saved replays in this world yet. Press Save in the replay editor."), width / 2, height / 2, 0xFFA0A0A0);
        if (message != null) g.centeredText(font, message, width / 2, height - 44, 0xFFFF5555);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(editor);
    }
}
