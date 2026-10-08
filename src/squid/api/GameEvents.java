package squid.api;

import squid.Events;

/**
 * Squid's hooks for things that happen in the game: breaking a block, hitting a mob, picking something up, chat, and
 * chat commands. They're put into Minecraft once, as Squid starts, and hand each thing to the mods listening (see
 * {@link Squid#onBreak}, {@link Squid#onAttack}, {@link Squid#onPickup}, {@link Squid#onChat} and
 * {@link Squid#onChatCommand}). Mods don't call this; Squid does.
 */
public final class GameEvents {
    private static boolean installed;

    private GameEvents() {
    }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        String gameMode = "net.minecraft.client.multiplayer.MultiPlayerGameMode";
        String packets = "net.minecraft.client.multiplayer.ClientPacketListener";
        // The block's name is read just before it's broken (after, it's air), and handed on if it really broke
        String[] breaking = {""};
        Events.hookAtStartup(gameMode, "destroyBlock", true, call -> {
            breaking[0] = "";
            if (!Events.listening("break")) return;
            try {
                breaking[0] = Game.blockAt(call.args()[0]);
            } catch (RuntimeException e) {
                // not something Squid can name
            }
        });
        Events.hookAtStartup(gameMode, "destroyBlock", false, call -> {
            if (Boolean.TRUE.equals(call.returnValue()) && !breaking[0].isEmpty()) Events.fire("break", breaking[0]);
        });
        Events.hookAtStartup(gameMode, "attack", true, call -> {
            if (!Events.listening("attack")) return;
            String mob;
            try {
                mob = Game.entityName(call.args()[1]);
            } catch (RuntimeException e) {
                return;
            }
            Events.fire("attack", mob);
        });
        Events.hookAtStartup(packets, "handleTakeItemEntity", true, call -> {
            if (!Events.listening("pickup")) return;
            Object[] picked;
            try {
                picked = Game.pickedUp(call.args()[0]);
            } catch (RuntimeException e) {
                return;
            }
            if (picked != null) Events.fire("pickup", picked);
        });
        // Every message the chat box shows ends up here, just as it's shown: players', the server's, /say...
        Events.hookAtStartup("net.minecraft.client.gui.components.ChatComponent", "addMessage", false, call -> {
            if (Game.saying || !Events.listening("chat")) return; // a mod talking doesn't count
            Events.fire("chat", Game.plain(call.args()[0]));
        });
        Events.hookAtStartup(packets, "sendChat", true, call -> {
            if (Events.command(String.valueOf(call.args()[0]))) call.cancel(); // it's a mod's: it isn't sent
        });
    }
}
