package squidskins;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Type any player's Minecraft name to copy their skin, straight from Mojang. */
final class NameScreen extends Screen {
    private final WardrobeScreen parent;
    private EditBox name;
    private Button get;
    private volatile String problem;
    private volatile boolean working;

    NameScreen(WardrobeScreen parent) {
        super(Component.literal("Get Skin by Name"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        name = new EditBox(font, width / 2 - 100, height / 2 - 20, 200, 20, Component.literal("Player name"));
        name.setMaxLength(16);
        name.setHint(Component.literal("Player name"));
        addRenderableWidget(name);
        setInitialFocus(name);
        get = addRenderableWidget(Button.builder(Component.literal("Get Skin"), b -> fetch()).bounds(width / 2 - 100, height / 2 + 10, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 + 2, height / 2 + 10, 98, 20).build());
    }

    private void fetch() {
        String typed = name.getValue().trim();
        if (typed.isEmpty() || working) return;
        working = true;
        problem = null;
        Thread worker = new Thread(() -> {
            try {
                Wardrobe.Fetched fetched = Skins.wardrobe.fetchSkin(typed);
                Wardrobe.Choice now = Skins.choice(Skins.myId());
                Skins.choose(Skins.myId(), new Wardrobe.Choice(fetched.file(), fetched.slim(), now.cape()));
                parent.say("Now wearing " + fetched.file().replaceAll("(?i)\\.png$", "") + "'s skin!", 0xFF55FF55);
                minecraft.execute(this::onClose);
            } catch (Exception e) {
                problem = e.getMessage() != null ? e.getMessage() : "Something went wrong.";
            } finally {
                working = false;
            }
        }, "squid skin lookup");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        get.active = !working;
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, "Get Skin by Name", width / 2, height / 2 - 50, 0xFFFFFFFF);
        g.centeredText(font, "Any player's skin, straight from Mojang.", width / 2, height / 2 - 36, 0xFFA0A0A0);
        if (working) g.centeredText(font, "Getting it...", width / 2, height / 2 + 40, 0xFFA0A0A0);
        else if (problem != null) g.centeredText(font, font.plainSubstrByWidth(problem, width - 20), width / 2, height / 2 + 40, 0xFFFF5555);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
