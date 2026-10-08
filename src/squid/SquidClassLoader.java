package squid;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Loads Minecraft, its libraries and every mod, and lets mods patch each class as it loads.
 * Squid's own classes and ASM always come from the normal loader, so mods and Squid share one copy of them.
 */
final class SquidClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private final ClassLoader squidLoader = SquidClassLoader.class.getClassLoader();

    /**
     * The packages Java's own classes are in. Asking Java first for every class meant Java looked for each of
     * Minecraft's thousands of classes, failed, and made an exception (with its whole stack) for each before Squid
     * found it, which slowed the start. Only classes in these packages can be Java's.
     */
    private static final java.util.Set<String> JAVA_PACKAGES = javaPackages();

    private static java.util.Set<String> javaPackages() {
        java.util.Set<String> packages = new java.util.HashSet<>();
        for (Module module : ModuleLayer.boot().modules()) packages.addAll(module.getPackages());
        return packages;
    }

    private static boolean couldBeJava(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 || JAVA_PACKAGES.contains(className.substring(0, lastDot));
    }

    SquidClassLoader(URL[] urls) {
        super("squid", urls, ClassLoader.getPlatformClassLoader()); // the platform loader has Java's own classes
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("squid.") || name.startsWith("org.objectweb.asm.")) {
            return squidLoader.loadClass(name);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null && couldBeJava(name)) {
                try {
                    c = getParent().loadClass(name); // Java's own classes, like java.lang.String
                } catch (ClassNotFoundException notJava) {
                    // a library class in a package named like one of Java's
                }
            }
            if (c == null) c = findClass(name); // Minecraft, a library, or a mod
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        URL url = findResource(name.replace('.', '/') + ".class");
        if (url == null) throw new ClassNotFoundException(name);
        byte[] bytes;
        try (InputStream in = url.openStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }

        try {
            bytes = Transformers.transform(name, bytes, this);
        } catch (RuntimeException e) {
            throw new ClassNotFoundException("Squid couldn't patch " + name, e);
        }

        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            String pkg = name.substring(0, lastDot);
            if (getDefinedPackage(pkg) == null) {
                try {
                    definePackage(pkg, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException alreadyDefined) {
                    // another thread got there first
                }
            }
        }
        // Remember which jar each class came from, like the normal loader does. Some code asks.
        CodeSource source = new CodeSource(jarOf(url), (CodeSigner[]) null);
        return defineClass(name, bytes, 0, bytes.length, new ProtectionDomain(source, null, this, null));
    }

    /** For a class inside a jar, the jar itself. */
    private static URL jarOf(URL classUrl) {
        try {
            if (classUrl.openConnection() instanceof JarURLConnection jar) return jar.getJarFileURL();
        } catch (IOException e) {
            // fall through
        }
        return classUrl;
    }

    /** Whether this loader has loaded a class already (for tests that check nothing loads too early). */
    boolean hasLoaded(String name) {
        return findLoadedClass(name) != null;
    }
}
