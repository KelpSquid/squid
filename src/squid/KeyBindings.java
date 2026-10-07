package squid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldNode;
import squid.api.KeyBinding;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Puts mods' keys into Minecraft's own list of keys, so they show up on the Controls screen.
 * Minecraft builds that list when it sets up its options and then loads the player's saved keys,
 * so Squid adds the mods' keys right before that loading, and their saved choices get loaded too.
 */
public final class KeyBindings {
    private static final List<KeyBinding> ALL = new ArrayList<>();

    private KeyBindings() {
    }

    public static synchronized void add(KeyBinding binding) {
        ALL.add(binding);
    }

    /** The key a mod already added with this name, or null. A reloaded mod gets its old keys back this way. */
    public static synchronized KeyBinding find(String name) {
        for (KeyBinding binding : ALL) {
            if (binding.name().equals(name)) return binding;
        }
        return null;
    }

    /**
     * Puts keys added after Minecraft set up its options (by a mod reloaded while playing) into its key list, and has
     * Minecraft look them up again so they work right away.
     */
    static synchronized void addToRunningGame(ClassLoader gameLoader) throws ReflectiveOperationException {
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", true, gameLoader);
        Object game = minecraft.getMethod("getInstance").invoke(null);
        Object options = game == null ? null : minecraft.getField("options").get(game);
        if (options == null) return;
        addTo(options);
        Class.forName("net.minecraft.client.KeyMapping", true, gameLoader).getMethod("resetMapping").invoke(null);
    }

    static void registerHooks() {
        // keyMappings is final, so it can't be swapped for a longer list. Squid drops the "final" as Options loads.
        Transformers.add("net.minecraft.client.Options", new Transformers.RawPatch("squid", options -> {
            for (FieldNode field : options.fields) {
                if (field.name.equals("keyMappings")) field.access &= ~Opcodes.ACC_FINAL;
            }
        }));
        Transformers.add("net.minecraft.client.Options", new Transformers.HookPatch("load", "()V", true,
                Hooks.register("squid", call -> {
                    try {
                        addTo(call.self());
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(Lang.t("Couldn't add the mods' keys to Minecraft's controls"), e);
                    }
                })));
    }

    /** Adds every mod key that isn't in this Options' key list yet. They go in Minecraft's "Miscellaneous" group. */
    static synchronized void addTo(Object options) throws ReflectiveOperationException {
        if (ALL.isEmpty()) return;
        ClassLoader loader = options.getClass().getClassLoader();
        Class<?> keyMapping = Class.forName("net.minecraft.client.KeyMapping", true, loader);
        Class<?> category = Class.forName("net.minecraft.client.KeyMapping$Category", true, loader);
        Object misc = category.getField("MISC").get(null);
        Constructor<?> make = keyMapping.getConstructor(String.class, int.class, category);

        Field listField = options.getClass().getField("keyMappings");
        Object[] keys = (Object[]) listField.get(options);
        List<Object> missing = new ArrayList<>();
        for (KeyBinding binding : ALL) {
            // The name doubles as the text on the Controls screen: Minecraft shows it as-is when it has no translation
            if (binding.mapping() == null) binding.attach(make.newInstance(binding.name(), binding.defaultKey(), misc));
            if (!Arrays.asList(keys).contains(binding.mapping())) missing.add(binding.mapping());
        }
        if (missing.isEmpty()) return;
        Object[] longer = Arrays.copyOf(keys, keys.length + missing.size());
        for (int i = 0; i < missing.size(); i++) longer[keys.length + i] = missing.get(i);
        listField.set(options, longer);
    }
}
