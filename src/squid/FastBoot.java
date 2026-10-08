package squid;

import squid.api.ModInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Fast boot: the game starts much faster the second time.
 *
 * Normally Squid loads Minecraft through its own class loader, which patches each class as it loads. That works with
 * any mods, but Java can't remember anything about classes loaded that way, so every start is a cold start. So after
 * a normal start Squid quietly writes, in the instance's .squid-boot folder:
 *
 * - patched.jar: Minecraft's jar with every hook already put in (and without Mojang's signature, which would stop
 *   Java from caching its classes),
 * - boot.properties: the classpath to start from, a fingerprint of every file that could change the hooks (Squid,
 *   its parts, the mods), and a fingerprint of the hooks themselves,
 * - classes.txt: the classes the game loaded, for training.
 *
 * Next time, Kelp sees the fingerprint still matches and starts the game straight from those files, on Java's normal
 * classpath. Then Java's AOT cache works: after the game closes, Kelp runs a quick training run in the background
 * (Squid in "train" mode: the mods start, the classes load, and Minecraft sets up its registries, with no window) and
 * Java saves everything it learned in squid.aot. With it, Minecraft's own startup is about 5 times faster.
 *
 * If the hooks ever don't match on a fast start (a mod that hooks differently than last time), Squid stops before
 * Minecraft loads anything, with exit code {@link #RESTART}, and Kelp starts the game the normal way instead.
 */
public final class FastBoot {
    /** The exit code that tells Kelp to start the game the normal way. */
    public static final int RESTART = 86;
    public static final String FOLDER = ".squid-boot";
    private static final int MOST_CLASSES = 60_000;

    private static final Set<String> loadedClasses = ConcurrentHashMap.newKeySet();
    private static volatile Path recordTo;

    private FastBoot() {
    }

    /** The .squid-boot folder Kelp started a fast boot from, or null for a normal start. */
    static Path folder() {
        String f = System.getProperty("squid.fastBoot");
        return f == null ? null : Path.of(f);
    }

    static boolean active() {
        return folder() != null;
    }

    static boolean training() {
        return active() && Boolean.getBoolean("squid.train");
    }

    // ---- Fingerprints (Kelp works these out the same way) ----

    /**
     * A fingerprint of every file that could change what the hooks are: the classpath, Squid's jars, its built-in
     * parts and everything in the mods folder. Each is its name, size and time, so a changed file changes it.
     */
    static String fingerprint(Path gameFolder, Path squidFolder, List<String> classpath) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String entry : classpath) lines.add("C " + describe(Path.of(entry), entry));
        for (Path p : listFiles(squidFolder, ".jar")) lines.add("S " + describe(p, p.getFileName().toString()));
        for (Path p : listFiles(squidFolder.resolve("builtin"), ".jar")) lines.add("B " + describe(p, p.getFileName().toString()));
        for (Path p : listFiles(gameFolder.resolve("mods"), "")) lines.add("M " + describe(p, p.getFileName().toString()));
        lines.sort(null);
        return sha256(String.join("\n", lines));
    }

    private static String describe(Path p, String name) throws IOException {
        if (!Files.exists(p)) return name + " missing";
        return name + " " + Files.size(p) + " " + Files.getLastModifiedTime(p).toMillis();
    }

    private static List<Path> listFiles(Path folder, String ending) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(ending)).sorted().toList();
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Where squid.jar is (and its libraries, and the builtin folder). */
    static Path squidFolder() {
        try {
            return Path.of(FastBoot.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        } catch (Exception e) {
            return Path.of(".");
        }
    }

    // ---- After a normal start: writing the fast boot files ----

    /** Remembers each class the game loads, for training (only on a normal start). */
    static void classLoaded(String name) {
        if (recordTo != null && loadedClasses.size() < MOST_CLASSES) loadedClasses.add(name);
    }

    /**
     * Writes the fast boot files in the background, after a normal start (the game doesn't wait for it). The class
     * list is saved a while after the game opens, and again when it closes.
     */
    static void prepare(Path gameFolder, String gameClasspath, String mainClass, List<ModInfo> builtIn, List<ModInfo> mods) {
        Path dir = gameFolder.resolve(FOLDER);
        recordTo = dir;
        Thread writer = new Thread(() -> {
            try {
                write(dir, gameFolder, gameClasspath, mainClass, builtIn, mods);
            } catch (Exception | LinkageError e) {
                System.out.println("[Squid] Couldn't get fast boot ready: " + e);
            }
            try {
                Thread.sleep(120_000); // by then the title screen (or a world) is open
                saveClasses();
            } catch (InterruptedException ignored) {
                // the game is closing; the shutdown hook saves the list
            }
        }, "Squid fast boot");
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(FastBoot::saveClasses, "Squid fast boot classes"));
    }

    private static synchronized void saveClasses() {
        Path dir = recordTo;
        if (dir == null || loadedClasses.isEmpty()) return;
        try {
            Files.createDirectories(dir);
            List<String> names = new ArrayList<>(loadedClasses);
            names.sort(null);
            Path part = dir.resolve("classes.txt.part");
            Files.write(part, names);
            Files.move(part, dir.resolve("classes.txt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[Squid] Couldn't save the class list for fast boot: " + e.getMessage());
        }
    }

    static void write(Path dir, Path gameFolder, String gameClasspath, String mainClass, List<ModInfo> builtIn, List<ModInfo> mods) throws IOException {
        List<String> game = List.of(gameClasspath.split(java.io.File.pathSeparator));
        List<Path> modJars = new ArrayList<>();
        for (ModInfo m : builtIn) if (isJar(m.jar())) modJars.add(m.jar());
        for (ModInfo m : mods) if (isJar(m.jar())) modJars.add(m.jar());
        // Which jar is Minecraft's own (client and server jars both have MinecraftServer)
        String mainEntry = "net/minecraft/server/MinecraftServer.class";
        Path gameJar = null;
        for (String entry : game) {
            Path p = Path.of(entry);
            if (!isJar(p)) continue;
            try (JarFile jar = new JarFile(p.toFile(), false)) {
                if (jar.getJarEntry(mainEntry) != null) {
                    gameJar = p;
                    break;
                }
            }
        }
        if (gameJar == null) throw new IOException("Minecraft's jar isn't on the classpath");
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve("boot.properties")); // not usable while it's being rewritten

        // Every hooked class, patched: read from wherever it comes from (Minecraft, a library, a mod)
        List<URL> urls = new ArrayList<>();
        for (String entry : game) urls.add(Path.of(entry).toUri().toURL());
        for (Path p : modJars) urls.add(p.toUri().toURL());
        Map<String, byte[]> patched = new java.util.TreeMap<>();
        try (URLClassLoader sources = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            for (String className : Transformers.patchedClasses()) {
                try (InputStream in = sources.getResourceAsStream(className.replace('.', '/') + ".class")) {
                    if (in == null) continue; // a class from a version this mod wasn't made for
                    patched.put(className.replace('.', '/') + ".class", Transformers.patch(className, in.readAllBytes(), sources));
                }
            }
        }

        // patched.jar: Minecraft's jar, unsigned, with the patched classes in it (and the hooked ones from elsewhere)
        Path part = dir.resolve("patched.jar.part");
        try (JarFile jar = new JarFile(gameJar.toFile(), false); OutputStream file = new java.io.BufferedOutputStream(Files.newOutputStream(part), 1 << 16);
             JarOutputStream out = new JarOutputStream(file)) {
            for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                String name = entry.getName();
                if (isSignature(name) || entry.isDirectory()) continue;
                byte[] bytes;
                if (patched.containsKey(name)) {
                    bytes = patched.remove(name);
                } else {
                    try (InputStream in = jar.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                    if (name.equals("META-INF/MANIFEST.MF")) bytes = mainSectionOnly(bytes);
                }
                put(out, name, bytes);
            }
            for (Map.Entry<String, byte[]> left : patched.entrySet()) put(out, left.getKey(), left.getValue()); // hooked classes from libraries or mods
        }
        Path patchedJar = dir.resolve("patched.jar");
        Files.move(part, patchedJar, StandardCopyOption.REPLACE_EXISTING);

        // The classpath to start from: patched.jar first (in place of Minecraft's), then the libraries, then the mods
        List<String> classpath = new ArrayList<>();
        classpath.add(patchedJar.toAbsolutePath().toString());
        for (String entry : game) if (!Path.of(entry).equals(gameJar) && Files.exists(Path.of(entry))) classpath.add(entry);
        for (Path p : modJars) classpath.add(p.toAbsolutePath().toString());
        Properties boot = new Properties();
        boot.setProperty("squid", Main.VERSION);
        boot.setProperty("classpath", String.join(java.io.File.pathSeparator, classpath));
        boot.setProperty("fingerprint", fingerprint(gameFolder, squidFolder(), classpath));
        boot.setProperty("hooks", Transformers.signature());
        Path bootPart = dir.resolve("boot.properties.part");
        try (Writer w = Files.newBufferedWriter(bootPart, StandardCharsets.UTF_8)) {
            boot.store(w, "Squid fast boot. Made by Squid after a normal start; Kelp uses it to start faster.");
        }
        Files.move(bootPart, dir.resolve("boot.properties"), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[Squid] Fast boot is ready for next time");
    }

    /** Stored, not squeezed: quick to write, and quicker for Java to read than squeezed classes. */
    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setMethod(JarEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    private static boolean isJar(Path p) {
        return p != null && Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar");
    }

    private static boolean isSignature(String name) {
        if (!name.startsWith("META-INF/") || name.indexOf('/', 9) >= 0) return false;
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    /** A manifest without its per-file fingerprints (they belong to the signature). */
    private static byte[] mainSectionOnly(byte[] manifest) {
        String text = new String(manifest, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int end = text.indexOf("\n\n");
        String main = end < 0 ? text : text.substring(0, end);
        return (main.replace("\n", "\r\n") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }

    // ---- On a fast start ----

    /**
     * Checks the hooks the mods just set up are exactly the ones patched.jar has. If not, Squid stops before
     * Minecraft loads anything, and Kelp starts the game the normal way.
     */
    static void check() {
        Path dir = folder();
        Properties boot = new Properties();
        try (Reader r = Files.newBufferedReader(dir.resolve("boot.properties"), StandardCharsets.UTF_8)) {
            boot.load(r);
        } catch (IOException e) {
            restart("its files are missing");
        }
        if (!Transformers.signature().equals(boot.getProperty("hooks"))) {
            try {
                Files.deleteIfExists(dir.resolve("boot.properties"));
            } catch (IOException ignored) {
                // Kelp sees the exit code either way
            }
            restart("the mods hook different things than last time");
        }
    }

    private static void restart(String why) {
        System.out.println("[Squid] Fast boot can't be used (" + why + "), so Kelp will start the game the normal way");
        System.exit(RESTART);
    }

    /**
     * The training run Kelp does in the background: every class the game loaded last time is loaded (not started),
     * and Minecraft sets up its version and registries like it does when it opens, with no window. Java saves what
     * it learned in the AOT cache when this exits.
     */
    static void train(ClassLoader loader) {
        long start = System.nanoTime();
        int count = 0;
        Path list = folder().resolve("classes.txt");
        try {
            if (Files.exists(list)) {
                for (String name : Files.readAllLines(list)) {
                    try {
                        Class.forName(name, false, loader);
                        count++;
                    } catch (Throwable ignored) {
                        // a class that can't load here (one needing the window, say) just isn't cached
                    }
                }
            }
            Class.forName("net.minecraft.SharedConstants", true, loader).getMethod("tryDetectVersion").invoke(null);
            Class.forName("net.minecraft.server.Bootstrap", true, loader).getMethod("bootStrap").invoke(null);
        } catch (Throwable e) {
            System.out.println("[Squid] Training stopped early: " + e);
        }
        System.out.println("[Squid] Trained fast boot with " + count + " classes in " + (System.nanoTime() - start) / 1_000_000 + " ms");
        System.exit(0);
    }
}
