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
    private ModMaker() {
    }

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
        return out.toString();
    }

    /** A new easy mod's code: it says hello when you join a world, with ideas for what to add. */
    public static String template(String name, String className) {
        String shown = name.strip().isEmpty() ? className : name.strip().replace("\"", "'");
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
        String className = className(name);
        Path file = mods.resolve(className + ".java");
        if (Files.exists(file)) return file;
        Files.createDirectories(mods);
        Files.writeString(file, template(name, className), StandardCharsets.UTF_8);
        return file;
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
