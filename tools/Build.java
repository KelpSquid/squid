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

        // 3. Squid's built-in parts, like the Store. They're built against Minecraft like the examples,
        //    and go in a builtin folder next to squid.jar, where Squid loads them by itself.
        Path builtInFolder = Path.of("builtin");
        List<Path> builtInJars = new ArrayList<>();
        try (Stream<Path> parts = Files.isDirectory(builtInFolder) ? Files.list(builtInFolder) : Stream.empty()) {
            for (Path part : parts.filter(Files::isDirectory).toList()) {
                Path out = BUILD.resolve("builtin-classes").resolve(part.getFileName());
                compile(listJava(part.resolve("src")), classes + ";" + squidClasspath + ";" + game, out, "25");
                Path partJar = BUILD.resolve("builtin").resolve(part.getFileName() + ".jar");
                jar(partJar, out, part.resolve("squid.json"));
                builtInJars.add(partJar);
                System.out.println("Built " + partJar);
            }
        }

        // 4. The store folder: store.json plus the files, ready to upload to the squid-store repo as they are
        storeFolder(examplesFolder);

        // 5. Put Squid where Kelp looks for it
        Path installed = KELP.resolve("squid");
        Files.createDirectories(installed.resolve("builtin"));
        for (Path part : builtInJars) {
            Files.copy(part, installed.resolve("builtin").resolve(part.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.copy(squidJar, installed.resolve("squid.jar"), StandardCopyOption.REPLACE_EXISTING);
        for (Path library : libraries) {
            Files.copy(library, installed.resolve(library.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("Copied Squid into " + installed);

        if (args.length > 0 && args[0].equals("test")) test(classes, squidClasspath, game);
    }

    /** Where the store's files are downloaded from: the squid-store repo on GitHub. */
    static final String STORE_FILES = "https://raw.githubusercontent.com/SamuelArther/squid-store/main/files/";

    /**
     * Writes build/store: a files folder with each first-party mod (named like xray-1.0.0.jar) and a store.json
     * listing them with their fingerprints. Upload both to the squid-store repo and the in-game Store shows them.
     * Every first-party mod starts out Dev-picked; take "devPicked" off any you don't want there.
     */
    static void storeFolder(Path examplesFolder) throws Exception {
        Path store = BUILD.resolve("store");
        Path files = store.resolve("files");
        Files.createDirectories(files);
        StringBuilder json = new StringBuilder("{\n    \"items\": [");
        boolean first = true;
        try (Stream<Path> examples = Files.list(examplesFolder)) {
            for (Path example : examples.filter(Files::isDirectory).sorted().toList()) {
                if (example.getFileName().toString().equals("hello-squid")) continue; // a test mod, not for the store
                String info = Files.readString(example.resolve("squid.json"));
                String id = field(info, "id");
                String version = field(info, "version");
                String fileName = id + "-" + version + ".jar";
                Path jar = files.resolve(fileName);
                Files.copy(BUILD.resolve(example.getFileName() + ".jar"), jar, StandardCopyOption.REPLACE_EXISTING);
                Matcher author = Pattern.compile("\"authors\"\\s*:\\s*\\[\\s*\"([^\"]*)\"").matcher(info);
                json.append(first ? "\n" : ",\n");
                first = false;
                json.append("        {\"id\": ").append(quote(id))
                        .append(", \"type\": \"mod\"")
                        .append(", \"name\": ").append(quote(field(info, "name")))
                        .append(", \"author\": ").append(quote(author.find() ? author.group(1) : ""))
                        .append(",\n         \"description\": ").append(quote(field(info, "description")))
                        .append(",\n         \"minecraft\": ").append(quote(field(info, "minecraft")))
                        .append(", \"devPicked\": true")
                        .append(", \"file\": ").append(quote(fileName))
                        .append(",\n         \"url\": ").append(quote(STORE_FILES + fileName))
                        .append(",\n         \"sha256\": ").append(quote(sha256(jar)))
                        .append(", \"size\": ").append(Files.size(jar)).append("}");
            }
        }
        json.append("\n    ]\n}\n");
        Files.writeString(store.resolve("store.json"), json);
        System.out.println("Made the store folder in " + store);
    }

    /** A text field from a squid.json, like "name". Empty if it isn't there. */
    static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : "";
    }

    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Builds and runs test/, which loads real Minecraft classes through Squid without starting the game. */
    static void test(Path classes, String squidClasspath, String game) throws Exception {
        Path testClasses = BUILD.resolve("test");
        List<Path> sources = listJava(Path.of("test"));
        // The Store's own logic (its list and installer) is tested too, so its classes go on the test's classpath
        String builtIn = BUILD.resolve("builtin-classes").resolve("store") + ";" + BUILD.resolve("builtin-classes").resolve("count");
        compile(sources, classes + ";" + squidClasspath + ";" + builtIn, testClasses, "21");
        // The test needs the example mods in a mods folder of its own
        Path mods = BUILD.resolve("test-mods");
        Files.createDirectories(mods);
        for (String mod : new String[] {"hello-squid.jar", "zoom.jar", "minimap.jar", "compass.jar", "fullbright.jar", "xray.jar", "boost.jar"}) {
            Files.copy(BUILD.resolve(mod), mods.resolve(mod), StandardCopyOption.REPLACE_EXISTING);
        }

        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process run = new ProcessBuilder(java.toString(), "-cp", testClasses + ";" + classes + ";" + squidClasspath + ";" + builtIn,
                "squid.PipelineTest", game, mods.toString(), testClasses.toString(), BUILD.resolve("builtin").resolve("store.jar").toString(),
                BUILD.resolve("builtin").resolve("count.jar").toString())
                .inheritIO().start();
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
