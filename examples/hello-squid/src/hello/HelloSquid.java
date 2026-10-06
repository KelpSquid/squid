package hello;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.network.chat.Component;
import squid.api.Squid;
import squid.api.SquidMod;

/** A tiny test mod: swaps Minecraft's yellow title screen splash for one of our own. */
public class HelloSquid implements SquidMod {
    @Override
    public void init(Squid squid) {
        squid.log("Hello from Squid!");
        squid.atStart("net.minecraft.client.resources.SplashManager", "getSplash", call ->
                call.cancel(new SplashRenderer(Component.literal("Squid is working!").withStyle(ChatFormatting.YELLOW))));
    }
}
