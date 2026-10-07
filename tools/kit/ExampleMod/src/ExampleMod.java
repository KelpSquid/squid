// Example Mod: a Squid project to start from.
// Your code goes in src (as many files as you like), pictures and sounds in resources.
//
// Start with the easy commands: say, showText, playSound, giveItem, command, onKey, onJoin, every...
// When you want more, squid() reaches into any part of Minecraft. Your editor knows every command:
// type squid(). and have a look.

import squid.api.*;

public class ExampleMod extends EasyMod {
    void start() {
        say("Example Mod is working!");

        onKey("H", () -> {
            say("Hello from your project! You're at " + x() + ", " + y() + ", " + z());
            playSound("entity.experience_orb.pickup");
        });
    }
}
