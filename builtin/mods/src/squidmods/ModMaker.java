package squidmods;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The Mod Maker's files: the easy mods (one .java file each) in the mods folder, and new ones made from a name.
 * Nothing here needs Minecraft, so it can be tested on its own. The screens are {@link ModMakerScreen} and
 * {@link CodeScreen}.
 */
public final class ModMaker {
    /** Class names a mod can't have, because Java or Squid already means something else by them. */
    private static final java.util.Set<String> TAKEN = java.util.Set.of("EasyMod", "Squid", "SquidMod", "Game", "Hud",
            "KeyBinding", "ModInfo", "ModSettings", "Object", "String", "Math", "System", "Integer", "Double", "Boolean",
            "Thread", "Runnable", "Record", "Enum", "Class", "Exception", "Error");

    private ModMaker() {
    }

    /**
     * The Mod Maker's Commands list: what each easy mod command is called, and a line of code that uses it, which is
     * typed in where the cursor is.
     */
    public static final List<String[]> SNIPPETS = List.of(
            new String[] {"say", "say(\"Hi!\");"},
            new String[] {"title", "title(\"Boss!\", \"Good luck\");"},
            new String[] {"showText", "showText(\"Above the hotbar\");"},
            new String[] {"onJoin", "onJoin(() -> say(\"Welcome!\"));"},
            new String[] {"onKey", "onKey(\"R\", () -> boost(1.2));"},
            new String[] {"every", "every(10, () -> particles(\"heart\", 5));"},
            new String[] {"after", "after(3, () -> say(\"Boom!\"));"},
            new String[] {"onBreak", "onBreak(block -> say(\"You broke \" + block));"},
            new String[] {"onAttack", "onAttack(mob -> particles(\"crit\", 5));"},
            new String[] {"onPickup", "onPickup(item -> { if (item.equals(\"diamond\")) say(\"Shiny!\"); });"},
            new String[] {"onHurt", "onHurt(() -> playSound(\"entity.villager.no\"));"},
            new String[] {"onLevelUp", "onLevelUp(level -> title(\"Level \" + level + \"!\"));"},
            new String[] {"onNight", "onNight(() -> say(\"Night is falling. Watch out!\"));"},
            new String[] {"onDeath", "onDeath(() -> title(\"Oops!\", \"Try again\"));"},
            new String[] {"onCommand", "onCommand(\"dance\", () -> particles(\"note\", 10));"},
            new String[] {"onChat", "onChat(text -> { if (text.contains(\"hi\")) say(\"Hello!\"); });"},
            new String[] {"glow", "glow(\"creeper\");"},
            new String[] {"keepShowing", "keepShowing(() -> \"Health: \" + health());"},
            new String[] {"playSound", "playSound(\"entity.experience_orb.pickup\");"},
            new String[] {"particles", "particles(\"flame\", 10);"},
            new String[] {"boost", "boost(1.2);"},
            new String[] {"dash", "dash(2);"},
            new String[] {"giveItem", "giveItem(\"diamond\", 3);"},
            new String[] {"command", "command(\"time set day\");"},
            new String[] {"remember", "remember(\"score\", remembered(\"score\", 0) + 1);"},
            new String[] {"nearby", "if (nearby(\"creeper\", 16) > 0) title(\"Creeper!\");"},
            new String[] {"isNight", "if (isNight()) say(\"It's night!\");"},
            new String[] {"isRaining", "if (isRaining()) title(\"Rain!\", \"Get inside\");"},
            new String[] {"random", "if (random(1, 6) == 6) say(\"You rolled a 6!\");"},
            new String[] {"markBlock", "markBlock(x(), y() - 1, z(), \"gold\");"},
            new String[] {"waypoint", "waypoint(\"Home\", x(), y(), z());"},
            new String[] {"floatingText", "floatingText(\"Treasure here!\", x(), y() + 2, z());"},
            new String[] {"drawLine", "drawLine(0, 64, 0, x(), y(), z(), \"red\");"},
            new String[] {"clearMarks", "clearMarks();"},
            new String[] {"keepDrawing", "keepDrawing(draw -> draw.block(x(), y() - 1, z(), \"lime\"));"},
            new String[] {"screen", "onKey(\"M\", () -> screen(\"My Menu\").button(\"Day\", () -> command(\"time set day\")).open());"},
            new String[] {"send", "send(\"score\", 10);"},
            new String[] {"onMessage", "onMessage(\"score\", (from, data) -> say(from + \" scored \" + data));"},
            new String[] {"signal", "signal(\"treasure-found\", 5);"},
            new String[] {"onSignal", "onSignal(\"treasure-found\", value -> say(\"Treasure! \" + value));"},
            new String[] {"hasMod", "if (hasMod(\"minimap\")) say(\"You have the minimap too!\");"});

    /** Every easy mod in the folder, by name. */
    public static List<Path> easyMods(Path mods) {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(mods)) return found;
        try (Stream<Path> list = Files.list(mods)) {
            list.filter(p -> p.getFileName().toString().endsWith(".java") && Files.isRegularFile(p)).sorted().forEach(found::add);
        } catch (IOException e) {
            // nothing to show
        }
        return found;
    }

    /** A name like "rocket boots!" as a class name, RocketBoots (letters and numbers, starting with a capital). */
    public static String className(String name) {
        StringBuilder out = new StringBuilder();
        boolean capital = true;
        for (char c : name.strip().toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                out.append(capital ? Character.toUpperCase(c) : c);
                capital = false;
            } else {
                capital = true;
            }
        }
        if (out.isEmpty() || Character.isDigit(out.charAt(0))) out.insert(0, "My");
        if (out.toString().equals("My")) out.append("Mod");
        // A name Java or Squid already uses for something else (class EasyMod extends EasyMod can't work)
        if (TAKEN.contains(out.toString())) out.insert(0, "My");
        return out.toString();
    }

    /** A new easy mod's code: it says hello when you join a world, with ideas for what to add. */
    public static String template(String name, String className) {
        // Shown in a comment and in "...", so no quotes, and no backslashes (Java reads a backslash-u even in comments)
        String shown = name.strip().replace("\"", "'").replace("\\", "");
        if (shown.isEmpty()) shown = className;
        return """
                import squid.api.*;

                // %1$s: made in the game with Squid's Mod Maker. Save, and it runs right away.
                // Ideas: onKey("R", () -> boost(1.2));   every(10, () -> particles("heart", 5));
                //        onBreak(block -> say("You broke " + block));   keepShowing(() -> "Health: " + health());

                public class %2$s extends EasyMod {
                    void start() {
                        onJoin(() -> say("Hello from %1$s!"));
                    }
                }
                """.formatted(shown, className);
    }

    /**
     * Makes a new easy mod in the folder and gives back its file. If there's one by that name already, that one is
     * given back as it is (nothing is overwritten).
     */
    public static Path create(Path mods, String name) throws IOException {
        return create(mods, name, null);
    }

    /** Like create(mods, name), starting from one of the starter mods (or the hello template when it's null). */
    public static Path create(Path mods, String name, ModStarters.Starter starter) throws IOException {
        String className = className(name);
        Path file = mods.resolve(className + ".java");
        if (Files.exists(file)) return file;
        Files.createDirectories(mods);
        String code = starter == null ? template(name, className)
                : "import squid.api.*;\n\n" + starter.code().formatted(shownName(name, className), className);
        Files.writeString(file, code, StandardCharsets.UTF_8);
        return file;
    }

    /** The name as it's shown in the code: no quotes or backslashes, which would break it. */
    static String shownName(String name, String className) {
        String shown = name.strip().replace("\"", "'").replace("\\", "");
        return shown.isEmpty() ? className : shown;
    }

    /** A file's code with Windows line endings made plain, for the editor. */
    public static String read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            text = new String(bytes, java.nio.charset.Charset.forName("windows-1252")); // saved by an old Windows editor
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        return text.replace("\r\n", "\n").replace("\t", "    ");
    }
}
