package squidmods;

import java.util.List;

/**
 * The Mod Maker's starter mods: ones that already do something fun, to change and learn from. They're the same as
 * Kelp's New Mod starters (kelp/src/kelp/ModStarters.java); keep the two lists the same. In the code, %1$s is the
 * mod's name and %2$s its class name.
 */
public final class ModStarters {
    /** One starter: its name, a line about it, and its code. */
    public record Starter(String name, String about, String code) {
    }

    private ModStarters() {
    }

    public static final List<Starter> ALL = List.of(
            new Starter("Rocket Boots", "Press R to blast into the sky.", """
                    // %1$s: press R to blast into the sky!
                    // Try changing POWER: 0.5 is a hop, 3 is the clouds. (Careful: falling still hurts.)

                    public class %2$s extends EasyMod {
                        double POWER = 1.2;

                        void start() {
                            onKey("R", () -> {
                                boost(POWER);
                                particles("firework", 20);
                                playSound("entity.firework_rocket.launch");
                                showText("Whoosh!");
                            });
                        }
                    }
                    """),
            new Starter("Creeper Alarm", "Warns you when a creeper sneaks up.", """
                    // %1$s: warns you when a creeper sneaks up on you.
                    // Try changing DISTANCE (in blocks), or the mob: nearby("zombie", DISTANCE) works for any mob.

                    public class %2$s extends EasyMod {
                        int DISTANCE = 12;
                        boolean warned;

                        void start() {
                            every(0.5, () -> {
                                int creepers = nearby("creeper", DISTANCE);
                                if (creepers == 0) {
                                    warned = false;
                                    return;
                                }
                                showText(creepers == 1 ? "A creeper is near you!" : creepers + " creepers are near you!");
                                if (!warned) {
                                    playSound("block.note_block.bell");
                                    warned = true; // the bell rings once, not every half second
                                }
                            });
                        }
                    }
                    """),
            new Starter("Day Night Switch", "Press N to flip between day and night (cheats on).", """
                    // %1$s: press N to flip between day and night.
                    // It uses the /time command, so cheats need to be on in that world.

                    public class %2$s extends EasyMod {
                        boolean night;

                        void start() {
                            onKey("N", () -> {
                                night = !night;
                                command(night ? "time set night" : "time set day");
                                showText(night ? "Goodnight!" : "Good morning!");
                            });
                        }
                    }
                    """),
            new Starter("Where Am I", "Always shows where you are, above the hotbar.", """
                    // %1$s: always shows where you are, just above your hotbar.
                    // Try adding your health: + "  Health " + health()

                    public class %2$s extends EasyMod {
                        void start() {
                            every(0.25, () -> {
                                if (inWorld()) showText("X " + x() + "   Y " + y() + "   Z " + z());
                            });
                        }
                    }
                    """),
            new Starter("Health Alarm", "Beeps when your health gets low.", """
                    // %1$s: beeps when your health gets low, and cheers you up when you die.
                    // Health goes from 0 to 20 (each heart is 2). Try changing LOW.

                    public class %2$s extends EasyMod {
                        int LOW = 6;

                        void start() {
                            onHurt(() -> {
                                if (health() > 0 && health() <= LOW) {
                                    title("", "Low health! Eat something!");
                                    playSound("block.note_block.bass");
                                }
                            });
                            onDeath(() -> title("Oops!", "You'll get it next time"));
                        }
                    }
                    """),
            new Starter("Lucky Button", "Press G for a random gift (cheats on).", """
                    // %1$s: press G for a random gift!
                    // It uses the /give command, so cheats need to be on. Add your own gifts to the list.

                    public class %2$s extends EasyMod {
                        String[] GIFTS = {"diamond", "golden_apple", "cake", "ender_pearl", "cookie", "emerald", "firework_rocket"};

                        void start() {
                            onKey("G", () -> {
                                String gift = GIFTS[random(0, GIFTS.length - 1)];
                                giveItem(gift);
                                say("Lucky! You got " + gift.replace('_', ' ') + "!");
                                playSound("entity.player.levelup");
                            });
                        }
                    }
                    """),
            new Starter("What's That", "Tells you what you're looking at.", """
                    // %1$s: tells you the name of whatever you're looking at, block or mob.
                    // Try adding what's in your hand: + "  (holding " + holding() + ")"

                    public class %2$s extends EasyMod {
                        void start() {
                            every(0.25, () -> {
                                String thing = lookingAt();
                                if (!thing.isEmpty()) showText(thing.replace('_', ' '));
                            });
                        }
                    }
                    """),
            new Starter("Biome Announcer", "Shows a title when you walk into a new biome.", """
                    // %1$s: shows the biome's name in big letters when you walk into a new one.
                    // Try playing a sound too, or making it say whether it's night: isNight()

                    public class %2$s extends EasyMod {
                        String last = "";

                        void start() {
                            every(1, () -> {
                                String now = biome();
                                if (now.isEmpty() || now.equals(last)) return;
                                if (!last.isEmpty()) title(now.replace('_', ' '), "You found a new biome!");
                                last = now;
                            });
                        }
                    }
                    """),
            new Starter("Chat Commands", "Type !dance, !where or !shout hello in the chat.", """
                    // %1$s: your own chat commands! They start with ! and never get sent to anyone.
                    // Try adding one: onCommand("jump", () -> boost(1));

                    public class %2$s extends EasyMod {
                        void start() {
                            onCommand("dance", () -> {
                                particles("note", 15);
                                playSound("block.note_block.pling");
                            });
                            onCommand("where", () -> say("You're at " + x() + ", " + y() + ", " + z() + " in a " + biome().replace('_', ' ')));
                            onCommand("shout", words -> title(words.isEmpty() ? "HEY!" : words));
                            // It can hear the chat too: say hello and it answers
                            onChat(text -> {
                                if (text.toLowerCase().contains("hello squid")) say("Hello to you too!");
                            });
                        }
                    }
                    """),
            new Starter("Creeper Prank", "Press P and a creeper hisses right behind you. Gotcha!", """
                    // %1$s: press P and a creeper hisses right behind you. Then, a second and a half later: Gotcha!
                    // Try other sounds: "entity.ghast.scream", "entity.warden.roar", "entity.tnt.primed".

                    public class %2$s extends EasyMod {
                        void start() {
                            onKey("P", () -> {
                                playSound("entity.creeper.primed");
                                after(1.5, () -> {
                                    title("Gotcha!", "It was only " + playerName());
                                    particles("happy_villager", 15);
                                });
                            });
                        }
                    }
                    """),
            new Starter("Creeper Radar", "Press K: every creeper nearby glows through walls for 10 seconds.", """
                    // %1$s: press K and every creeper glows, even behind walls, for 10 seconds.
                    // Try other mobs: "zombie", "skeleton", "pig", or glow two kinds at once.

                    public class %2$s extends EasyMod {
                        void start() {
                            onKey("K", () -> {
                                glow("creeper");
                                showText("Creeper radar on!");
                                playSound("block.note_block.chime");
                                after(10, () -> stopGlowing("creeper"));
                            });
                        }
                    }
                    """),
            new Starter("Diamond Counter", "Counts every diamond you collect, forever.", """
                    // %1$s: counts the diamonds you pick up, and remembers them even after you quit.
                    // Try counting something else: "emerald", "netherite_scrap", "golden_apple".

                    public class %2$s extends EasyMod {
                        int diamonds;

                        void start() {
                            diamonds = remembered("diamonds", 0);
                            keepShowing(() -> "Diamonds: " + diamonds); // always in the top-left corner
                            onPickup((item, amount) -> {
                                if (item.equals("diamond")) {
                                    diamonds = diamonds + amount;
                                    remember("diamonds", diamonds);
                                    title("Diamonds!", "That's " + diamonds + " ever");
                                    particles("happy_villager", 10);
                                }
                            });
                            onCommand("diamonds", () -> say("You've collected " + diamonds + " diamonds."));
                        }
                    }
                    """),
            new Starter("Dance Party", "Notes and hearts jump to the beat of your music.", """
                    // %1$s: play a song in Squid > Jukebox, and notes and hearts jump to its beat!
                    // Try other particles: "flame", "happy_villager", "end_rod". Or make the effect bigger when it's loud.

                    public class %2$s extends EasyMod {
                        String song = "";

                        void start() {
                            onBeat(() -> {
                                particles("note", 4);
                                if (musicLevel() > 0.6) particles("heart", 2); // the loud parts get hearts too
                            });
                            every(1, () -> {
                                String now = nowPlaying();
                                if (!now.isEmpty() && !now.equals(song)) title("", "Now dancing to " + now);
                                song = now;
                            });
                        }
                    }
                    """));
}
