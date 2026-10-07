package squid;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Loads one mod Squid built from its code (an easy mod, a project or a .squid file) on its own, so a new version of it
 * can be loaded while the game runs: the new version gets a new loader, and the old one is simply left behind.
 * The mod's own classes come from its build first; everything else (Minecraft, Squid, other mods) from the game's loader.
 */
final class ModClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    ModClassLoader(Path build, ClassLoader game) {
        super("squid-mod", new URL[] {url(build)}, game);
    }

    private static URL url(Path build) {
        try {
            return build.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null && !name.startsWith("java.") && !name.startsWith("squid.")) {
                try {
                    c = findClass(name); // the mod's own classes, newest version
                } catch (ClassNotFoundException notTheMods) {
                    // Minecraft, Squid or another mod: the game's loader has those
                }
            }
            if (c == null) c = getParent().loadClass(name);
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name); // the mod's own pictures and sounds first, so new ones show up too
        return own != null ? own : super.getResource(name);
    }
}
