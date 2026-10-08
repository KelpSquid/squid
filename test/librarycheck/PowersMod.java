package librarycheck;

import squid.api.*;

/**
 * Uses every new power of the Squid library, so the build can check a mod using them compiles with just
 * squid-api.jar (what Kelp's mod projects and the Squid Kit build against). It's only compiled, never run.
 */
public class PowersMod extends EasyMod {
    int level;

    void start() {
        keepDrawing(draw -> draw.throughWalls(true).waypoint("Home", 0, 64, 0, "gold"));
        markBlock(0, 64, 0, "red");
        floatingText("Hi", 0, 66, 0);
        onMessage("ping", (from, data) -> from.reply("pong", data));
        onSignal("treasure-found", value -> say("Treasure! " + value));
        screen("Menu").label(() -> "Level " + level).toggle("Fly", false, on -> { }).slider("Speed", 1, 10, 5, speed -> { })
                .textBox("Name", "", text -> { }).button("Go", () -> send("go", true)).open();
        Squid squid = squid();
        squid.around("net.minecraft.world.entity.LivingEntity", "getMaxHealth", (call, original) -> (float) original.call() * 2);
        squid.atCall("net.minecraft.world.entity.LivingEntity", "causeFallDamage", "net.minecraft.world.entity.LivingEntity",
                "calculateFallDamage", (call, original) -> original.callOn(call.self(), call.args()));
        squid.onWorldDraw(draw -> draw.filledBox(0, 0, 0, 1, 1, 1, Colors.withAlpha(Colors.of("lime"), 0.5)));
        squid.offer("double", x -> (Integer) x * 2);
        int doubled = squid.ask("double", 21);
        level = Reflect.get(this, "level");
        Reflect.call(this, "say", "Doubled: " + doubled);
    }
}
