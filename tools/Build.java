import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds Squid. Run it through build.bat, which uses the Java 25 that Kelp downloads for Minecraft 26.3.
 *
 *   build         builds build/squid.jar and the example mods, and copies Squid into Kelp's folder
 *   build test    also runs the tests (they load real Minecraft classes, without opening the game)
 */
public class Build {
    static final String MINECRAFT = "26.3";
    static final String ASM_VERSION = "9.10.1";
    static final String[][] LIBRARIES = {
            {"asm", "ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236"},
            {"asm-tree", "e244332a17564c1d1572449399a842de35881be2"},
    };

    static final Path KELP = Path.of(System.getenv("APPDATA"), "Kelp");
    static final Path BUILD = Path.of("build");

    public static void main(String[] args) throws Exception {
        List<Path> libraries = downloadLibraries();
        String squidClasspath = joinPaths(libraries);

        // 1. Squid itself. It's built for Java 21 so it can run on any Java from 21 up.
        Path classes = BUILD.resolve("classes");
        compile(listJava(Path.of("src")), squidClasspath, classes, "21");
        Path squidJar = BUILD.resolve("squid.jar");
        jar(squidJar, classes, null);
        System.out.println("Built " + squidJar);

        // 2. The example mods, built against Minecraft (which needs Java 25)
        String game = gameClasspath();
        Path examplesFolder = Path.of("examples");
        try (Stream<Path> examples = Files.isDirectory(examplesFolder) ? Files.list(examplesFolder) : Stream.empty()) {
            for (Path example : examples.filter(Files::isDirectory).toList()) {
                Path out = BUILD.resolve("examples").resolve(example.getFileName());
                compile(listJava(example.resolve("src")), classes + ";" + squidClasspath + ";" + game, out, "25");
                Path modJar = BUILD.resolve(example.getFileName() + ".jar");
                jar(modJar, out, example.resolve("squid.json"));
                System.out.println("Built " + modJar);
            }
        }

        // 3. Put Squid where Kelp looks for it
        Path installed = KELP.resolve("squid");
        Files.createDirectories(installed);
        Files.copy(squidJar, installed.resolve("squid.jar"), StandardCopyOption.REPLACE_EXISTING);
        for (Path library : libraries) {
            Files.copy(library, installed.resolve(library.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("Copied Squid into " + installed);

        if (args.length > 0 && args[0].equals("test")) test(classes, squidClasspath, game);
    }

    /** Builds and runs test/, which loads real Minecraft classes through Squid without starting the game. */
    static void test(Path classes, String squidClasspath, String game) throws Exception {
        Path testClasses = BUILD.resolve("test");
        List<Path> sources = listJava(Path.of("test"));
        compile(sources, classes + ";" + squidClasspath, testClasses, "21");
        // The test needs the example mods in a mods folder of its own
        Path mods = BUILD.resolve("test-mods");
        Files.createDirectories(mods);
        for (String mod : new String[] {"hello-squid.jar", "zoom.jar", "minimap.jar", "compass.jar"}) {
            Files.copy(BUILD.resolve(mod), mods.resolve(mod), StandardCopyOption.REPLACE_EXISTING);
        }

        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process run = new ProcessBuilder(java.toString(), "-cp", testClasses + ";" + classes + ";" + squidClasspath,
                "squid.PipelineTest", game, mods.toString(), testClasses.toString()).inheritIO().start();
        if (run.waitFor() != 0) throw new IllegalStateException("Tests failed");
    }

    /** ASM, the bytecode library Squid uses. Downloaded from Maven Central once and checked against its fingerprint. */
    static List<Path> downloadLibraries() throws Exception {
        Path lib = Path.of("lib");
        Files.createDirectories(lib);
        HttpClient http = HttpClient.newHttpClient();
        List<Path> jars = new ArrayList<>();
        for (String[] library : LIBRARIES) {
            String name = library[0] + "-" + ASM_VERSION + ".jar";
            Path file = lib.resolve(name);
            if (!Files.exists(file) || !sha1(file).equals(library[1])) {
                System.out.println("Downloading " + name);
                String url = "https://repo1.maven.org/maven2/org/ow2/asm/" + library[0] + "/" + ASM_VERSION + "/" + name;
                http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(file));
                if (!sha1(file).equals(library[1])) {
                    Files.delete(file);
                    throw new IOException(name + " arrived damaged");
                }
            }
            jars.add(file);
        }
        return jars;
    }

    /** Minecraft and its libraries, read from the version Kelp downloaded. */
    static String gameClasspath() throws IOException {
        Path versionJson = KELP.resolve("versions").resolve(MINECRAFT).resolve(MINECRAFT + ".json");
        if (!Files.exists(versionJson)) {
            throw new IOException("Minecraft " + MINECRAFT + " isn't downloaded yet. Open Kelp and play " + MINECRAFT + " once first.");
        }
        String json = Files.readString(versionJson);
        List<String> paths = new ArrayList<>();
        // Each library entry has a "path"; skip the ones only for Mac or Linux
        Matcher library = Pattern.compile("\"path\":\\s*\"([^\"]+)\"").matcher(json);
        while (library.find()) {
            String path = library.group(1);
            if (path.contains("natives-linux") || path.contains("natives-macos") || path.contains("java-objc-bridge")) continue;
            paths.add(KELP.resolve("libraries").resolve(path).toString());
        }
        paths.add(KELP.resolve("versions").resolve(MINECRAFT).resolve(MINECRAFT + ".jar").toString());
        return String.join(";", paths);
    }

    static void compile(List<Path> sources, String classpath, Path out, String release) throws IOException {
        Files.createDirectories(out);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("--release", release, "-encoding", "UTF-8",
                "-cp", classpath, "-d", out.toString()));
        for (Path source : sources) args.add(source.toString());
        if (javac.run(null, null, null, args.toArray(String[]::new)) != 0) throw new IllegalStateException("Compile failed");
    }

    /** Packs a folder of classes (plus an optional squid.json) into a jar. */
    static void jar(Path jarFile, Path classes, Path squidJson) throws IOException {
        Files.createDirectories(jarFile.getParent());
        try (OutputStream file = Files.newOutputStream(jarFile); JarOutputStream jar = new JarOutputStream(file);
             Stream<Path> walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, jar);
                jar.closeEntry();
            }
            if (squidJson != null) {
                jar.putNextEntry(new JarEntry("squid.json"));
                Files.copy(squidJson, jar);
                jar.closeEntry();
            }
        }
    }

    static List<Path> listJava(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    static String joinPaths(List<Path> paths) {
        return String.join(";", paths.stream().map(Path::toString).toList());
    }

    static String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            digest.update(in.readAllBytes());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
