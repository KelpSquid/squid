package squid.api;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import squid.Lang;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns key names like "G", "5", "SPACE" or "F6" into Minecraft's key codes.
 * The codes are read straight out of Minecraft's InputConstants class file, so they're always right for this
 * Minecraft, without starting any of the game's code early.
 */
final class Keys {
    private static Map<String, Integer> codes;

    private Keys() {
    }

    static synchronized int code(String key, ClassLoader game) {
        if (codes == null) codes = read(game);
        String name = key.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        Integer code = codes.get("KEY_" + name);
        if (code == null) {
            throw new IllegalArgumentException(Lang.t("Squid doesn't know the key \"{0}\". Try a letter like \"G\","
                    + " a number like \"5\", or a key like \"SPACE\" or \"F6\"", key));
        }
        return code;
    }

    private static Map<String, Integer> read(ClassLoader game) {
        Map<String, Integer> found = new HashMap<>();
        try (InputStream in = game.getResourceAsStream("com/mojang/blaze3d/platform/InputConstants.class")) {
            if (in == null) throw new IllegalStateException(Lang.t("Squid couldn't find Minecraft's key list"));
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            for (FieldNode field : node.fields) {
                if (field.name.startsWith("KEY_") && field.value instanceof Integer value) found.put(field.name, value);
            }
        } catch (IOException e) {
            throw new IllegalStateException(Lang.t("Squid couldn't read Minecraft's key list"), e);
        }
        return found;
    }
}
