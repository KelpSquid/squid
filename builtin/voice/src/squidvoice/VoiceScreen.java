package squidvoice;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import squid.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Voice Chat, from the Squid menu: how you talk, your group, and muting people. Volumes and the rest are in
 * Squid > Mods > Squid Voice > Settings.
 */
final class VoiceScreen extends Screen {
    private final Screen back;
    private EditBox groupBox;
    private int page;

    VoiceScreen(Object back) {
        super(Component.literal(Lang.t("Voice Chat")));
        this.back = back instanceof Screen s ? s : null;
    }

    private static Voice voice() {
        return Voice.instance;
    }

    @Override
    protected void init() {
        Voice v = voice();
        int x = width / 2 - 100;
        int y = 40;
        if (Voice.parentOff() || !v.available()) {
            addRenderableWidget(Button.builder(Component.literal(Lang.t("Back")), b -> onClose()).bounds(x, height - 28, 200, 20).build());
            return;
        }
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Talk: {0}", Lang.t(v.talk()))), b -> {
            String now = v.talk();
            v.setTalk(now.equals(Voice.PUSH) ? Voice.ALWAYS : now.equals(Voice.ALWAYS) ? Voice.LISTEN : Voice.PUSH);
            rebuildWidgets();
        }).bounds(x, y, 200, 20).build());
        y += 30;
        if (v.config != null && v.config.groups()) {
            groupBox = new EditBox(font, x, y, 128, 20, Component.literal(Lang.t("Group")));
            groupBox.setMaxLength(24);
            groupBox.setHint(Component.literal(Lang.t("Group name")));
            groupBox.setValue(v.group);
            addRenderableWidget(groupBox);
            addRenderableWidget(Button.builder(Component.literal(Lang.t("Join")), b -> v.joinGroup(groupBox.getValue())).bounds(x + 132, y, 32, 20).build());
            addRenderableWidget(Button.builder(Component.literal(Lang.t("Leave")), b -> {
                groupBox.setValue("");
                v.joinGroup("");
            }).bounds(x + 168, y, 32, 20).build());
            y += 30;
        }
        // Everyone else here, with a Mute button each
        List<PlayerInfo> others = others();
        int perPage = Math.max(1, (height - y - 70) / 24);
        int pages = Math.max(1, (others.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        for (PlayerInfo info : others.subList(page * perPage, Math.min(others.size(), (page + 1) * perPage))) {
            UUID id = info.getProfile().id();
            boolean muted = v.muted.contains(id);
            addRenderableWidget(Button.builder(Component.literal(Lang.t(muted ? "Unmute" : "Mute")), b -> {
                v.setMuted(id, !muted);
                rebuildWidgets();
            }).bounds(x + 140, y, 60, 20).build());
            y += 24;
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = (page + pages - 1) % pages;
                rebuildWidgets();
            }).bounds(x, height - 54, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = (page + 1) % pages;
                rebuildWidgets();
            }).bounds(x + 180, height - 54, 20, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal(Lang.t("Done")), b -> onClose()).bounds(x, height - 28, 200, 20).build());
    }

    private List<PlayerInfo> others() {
        List<PlayerInfo> out = new ArrayList<>();
        var connection = minecraft.getConnection();
        if (connection == null || minecraft.player == null) return out;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (!info.getProfile().id().equals(minecraft.player.getUUID())) out.add(info);
        }
        out.sort((a, b) -> a.getProfile().name().compareToIgnoreCase(b.getProfile().name()));
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Voice v = voice();
        g.centeredText(font, Lang.t("Voice Chat"), width / 2, 12, 0xFFFFFFFF);
        if (Voice.parentOff()) {
            g.centeredText(font, Lang.t("A parent turned voice chat off in Kelp's Parent Controls."), width / 2, height / 2 - 10, 0xFFA0A0A0);
            return;
        }
        if (!v.listening()) {
            g.centeredText(font, Lang.t("Voice chat is off. Turn it on in Squid > Mods > Squid Voice > Settings."), width / 2, height / 2 - 10, 0xFFA0A0A0);
            return;
        }
        if (!v.available()) {
            g.centeredText(font, Lang.t("This server doesn't have voice chat. It needs Squid on the server too."), width / 2, height / 2 - 10, 0xFFA0A0A0);
            return;
        }
        int x = width / 2 - 100;
        String mode = switch (v.config.mode()) {
            case 1 -> Lang.t("Everyone on this server hears you.");
            case 2 -> Lang.t("Only your group hears you.");
            default -> Lang.t("Players within {0} blocks hear you.", v.config.distance());
        };
        g.centeredText(font, mode, width / 2, 26, 0xFFA0A0A0);
        if (v.mic.problem() != null && !Voice.LISTEN.equals(v.talk())) {
            g.centeredText(font, Lang.t("No microphone: {0}", v.mic.problem()), width / 2, height - 66, 0xFFFF7777);
        }
        int y = 70 + (v.config.groups() ? 30 : 0);
        List<PlayerInfo> others = others();
        if (others.isEmpty()) g.text(font, Lang.t("Nobody else is here yet."), x, y + 6, 0xFFA0A0A0, true);
        int perPage = Math.max(1, (height - y - 70) / 24);
        for (PlayerInfo info : others.subList(page * perPage, Math.min(others.size(), (page + 1) * perPage))) {
            Speakers.Speaker s = v.speakers.all().get(info.getProfile().id());
            boolean talking = s != null && s.talking();
            g.text(font, info.getProfile().name(), x, y + 6, talking ? 0xFF55FF55 : 0xFFFFFFFF, true);
            y += 24;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(back);
    }
}
