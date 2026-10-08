package squid;

import squid.api.ModInfo;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mods Squid builds itself from their code, as the game starts, using the compiler that comes with the game's Java.
 * No build tool, no jar to make:
 *
 * - an easy mod is one .java file in the mods folder. Its file name is its name: MyCoolMod.java is "My Cool Mod".
 * - a project is a folder in the mods folder with a squid.json, its code in src (as many files as it needs) and
 *   its pictures and sounds in resources.
 * - a .squid file is a project packed into one small file, for sharing. Its code stays readable, so anyone can check it.
 *
 * Squid adds "import squid.api.*;" to every file by itself, so mods can say "extends EasyMod" without imports.
 * Built mods are kept in mods/.squid-cache, so a mod is only built again after it changes.
 */
final class SourceMods {
    /** A mistake in a mod's code, explained for people who are new to Java. */
    static final class MistakeException extends IOException {
        MistakeException(String message) {
            super(message);
        }
    }

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final String AUTO_IMPORT = "import squid.api.*; ";

    /** A .java file to build: its name (for mistakes, like "src/Sheep.java") and its code. */
    private record Source(String name, String code) {
    }

    private final Path cache;
    private final String classpath;
    /** Which mod each file or folder became, so the reloader knows what changed. */
    final java.util.Map<Path, String> built = new java.util.concurrent.ConcurrentHashMap<>();

    /** classpath is what mods can use: Squid itself and Minecraft with its libraries. */
    SourceMods(Path cache, String classpath) {
        this.cache = cache;
        this.classpath = classpath;
    }

    /**
     * Text from a mod's file. Most editors save UTF-8, but some add a mark at the start (a BOM) and older ones save
     * Windows' own encoding. Both still work, instead of failing with "Input length = 1".
     */
    static String text(byte[] bytes) {
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes, start, bytes.length - start)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, start, bytes.length - start, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    /** An easy mod's details, from its file name (MyCoolMod.java is "My Cool Mod"), without building it. */
    static ModInfo describeEasy(Path source) throws IOException {
        String fileName = source.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".java".length());
        if (!className.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new MistakeException(Lang.t("the file name can only use letters and numbers, with no spaces. Try {0}",
                    className.replaceAll("[^A-Za-z0-9_]", "") + ".java"));
        }
        Matcher pkg = PACKAGE.matcher(text(Files.readAllBytes(source)));
        String main = pkg.find() ? pkg.group(1) + "." + className : className;
        return new ModInfo(Mods.idFor(className), spaced(className), "1.0", Lang.t("Made from {0}", fileName),
                List.of(), List.of(), List.of(), main, source);
    }

    /** Builds any mod made of code: an easy mod, a project folder or a .squid file. mod is what it says about itself. */
    ModInfo build(Path file, ModInfo mod) throws IOException {
        if (Files.isDirectory(file)) return compileProject(file, mod);
        if (file.getFileName().toString().endsWith(".squid")) return compilePacked(file, mod);
        return compile(file);
    }

    /** Builds an easy mod (or reuses last time's build) and describes it as a mod. */
    ModInfo compile(Path source) throws IOException {
        ModInfo mod = describeEasy(source);
        String fileName = source.getFileName().toString();
        Path out = build(mod.id(), List.of(new Source(fileName, text(Files.readAllBytes(source)))), null);
        built.put(source.toAbsolutePath().normalize(), mod.id());
        return new ModInfo(mod.id(), mod.name(), mod.version(), mod.description(), mod.authors(), mod.depends(),
                mod.minecraft(), mod.main(), out);
    }

    /** Builds a project folder. mod is what its squid.json says; the result is the same mod, pointing at the build. */
    ModInfo compileProject(Path folder, ModInfo mod) throws IOException {
        Path out = buildProject(folder, mod);
        built.put(folder.toAbsolutePath().normalize(), mod.id());
        return new ModInfo(mod.id(), mod.name(), mod.version(), mod.description(), mod.authors(), mod.depends(),
                mod.minecraft(), mod.main(), out);
    }

    private Path buildProject(Path folder, ModInfo mod) throws IOException {
        Path src = folder.resolve("src");
        if (!Files.isDirectory(src)) throw new MistakeException(Lang.t("it needs a src folder with its code in it."));
        List<Source> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                sources.add(new Source(folder.relativize(file).toString().replace('\\', '/'), text(Files.readAllBytes(file))));
            }
        }
        if (sources.isEmpty()) throw new MistakeException(Lang.t("its src folder has no .java files yet."));
        return build(mod.id(), sources, folder.resolve("resources"));
    }

    /** The most a .squid file can unpack to. Real mods are a few hundred KB; this stops a "zip bomb" filling the disk. */
    static final long MAX_UNPACKED = 256L * 1024 * 1024;

    /** Builds a .squid file: unpacks its src and resources into the cache, then builds it like a project folder. */
    ModInfo compilePacked(Path packed, ModInfo mod) throws IOException {
        Path unpacked = cache.resolve(mod.id() + "-unpacked");
        deleteFolder(unpacked);
        long total = 0;
        try (ZipFile zip = new ZipFile(packed.toFile())) {
            String root = packedRoot(zip);
            if (root == null) throw new IOException(Lang.t("it has no squid.json inside. Pack it again from Kelp."));
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = name(entry);
                if (entry.isDirectory() || !name.startsWith(root)) continue;
                name = name.substring(root.length());
                if (!(name.startsWith("src/") || name.startsWith("resources/"))) continue;
                Path target = unpacked.resolve(name).normalize();
                if (!target.startsWith(unpacked)) throw new IOException(Lang.t("it has a file that tries to leave its folder, so Squid won't open it."));
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    total += Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                if (total > MAX_UNPACKED) throw new IOException(Lang.t("it's far too big inside, so Squid won't open it."));
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException(Lang.t("it's damaged, so Squid can't open it. Download or pack it again."));
        }
        Path out = buildProject(unpacked, mod);
        built.put(packed.toAbsolutePath().normalize(), mod.id());
        return new ModInfo(mod.id(), mod.name(), mod.version(), mod.description(), mod.authors(), mod.depends(),
                mod.minecraft(), mod.main(), out);
    }

    /**
     * An entry's name with / between folders. Zips made by Windows PowerShell use \ instead, and Squid still reads them.
     */
    static String name(ZipEntry entry) {
        return entry.getName().replace('\\', '/');
    }

    /** The entry with this name, however its folders are written. */
    static ZipEntry entry(ZipFile zip, String name) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (name(entry).equals(name)) return entry;
        }
        return null;
    }

    /**
     * Where a .squid file's squid.json is: "" when it's at the top, as Kelp packs it, or "MegaMod/" when someone zipped
     * the whole folder by hand. Null if it has no squid.json.
     */
    static String packedRoot(ZipFile zip) {
        String nested = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = name(entries.nextElement());
            if (name.equals("squid.json")) return "";
            int slash = name.indexOf('/');
            if (slash > 0 && name.substring(slash + 1).equals("squid.json")) nested = name.substring(0, slash + 1);
        }
        return nested;
    }

    /** Every build made since Squid started, so {@link #cleanUp} knows which ones are still wanted. */
    private final java.util.Set<String> current = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Removes builds that no mod uses any more: older versions of mods that changed, and mods that are gone. Squid
     * does it once mods are found, before any of them run, so it never removes a build that's in use.
     */
    void cleanUp() {
        if (!Files.isDirectory(cache)) return;
        try (Stream<Path> list = Files.list(cache)) {
            for (Path folder : list.toList()) {
                String name = folder.getFileName().toString();
                if (current.contains(name) || !name.matches(".+-([0-9a-f]{12}|unpacked)")) continue;
                if (name.endsWith("-unpacked") && current.stream().anyMatch(c -> c.startsWith(name.substring(0, name.length() - "unpacked".length())))) continue;
                try {
                    deleteFolder(folder);
                } catch (IOException e) {
                    // in use or locked: next time
                }
            }
        } catch (IOException e) {
            // can't look right now: next time
        }
    }

    /** squid.jar's size and time, so mods are built again for a new Squid even if its version number stayed the same. */
    private static final String SQUID_BUILD = squidBuild();

    private static String squidBuild() {
        try {
            Path jar = Path.of(SourceMods.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(jar) ? Files.size(jar) + "/" + Files.getLastModifiedTime(jar).toMillis() : "classes";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Compiles the code into a folder named after the mod and a fingerprint of everything in it, unless that's
     * already there from last time. Resources (if there are any) are copied next to the classes.
     */
    private Path build(String name, List<Source> sources, Path resources) throws IOException {
        MessageDigest sha = sha256();
        for (Source source : sources) sha.update((source.name() + "\n" + source.code() + "\n").getBytes(StandardCharsets.UTF_8));
        List<Path> resourceFiles = new ArrayList<>();
        if (resources != null && Files.isDirectory(resources)) {
            try (Stream<Path> walk = Files.walk(resources)) {
                resourceFiles.addAll(walk.filter(Files::isRegularFile).sorted().toList());
            }
            for (Path file : resourceFiles) {
                sha.update(resources.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                sha.update(Files.readAllBytes(file));
            }
        }
        // The same code builds differently for another Squid, Java or Minecraft, so they count too
        sha.update((Main.VERSION + "\n" + SQUID_BUILD + "\n" + Runtime.version().feature() + "\n"
                + System.getProperty("squid.gameClasspath")).getBytes(StandardCharsets.UTF_8));
        Path out = cache.resolve(name + "-" + HexFormat.of().formatHex(sha.digest()).substring(0, 12));
        current.add(out.getFileName().toString());
        if (Files.exists(out.resolve("ok"))) return out;

        // Older builds stay until the next start (see cleanUp): a running mod may still be using one
        Files.createDirectories(out);
        javac(sources, out);
        for (Path file : resourceFiles) {
            Path target = out.resolve(resources.relativize(file).toString());
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(out.resolve("ok"), name); // marks a finished build, so a half-done one is redone
        return out;
    }

    /** Adds Squid's import on the first line (after the package line, if there is one), so line numbers don't move. */
    static String withImport(String code) {
        Matcher pkg = PACKAGE.matcher(code);
        if (pkg.find()) return code.substring(0, pkg.end()) + " " + AUTO_IMPORT + code.substring(pkg.end());
        return AUTO_IMPORT + code;
    }

    /** A name for a file of code. Names with spaces or other odd letters (src/My Helper.java) work too. */
    private static URI uri(String name) throws IOException {
        try {
            return new URI("string", null, "/" + name, null);
        } catch (java.net.URISyntaxException e) {
            throw new MistakeException(Lang.t("Squid can't use the file name {0}. Rename it with just letters and numbers.", name));
        }
    }

    private void javac(List<Source> sources, Path out) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new MistakeException(Lang.t("this Java can't compile mods. Play it from Kelp, which uses one that can."));
        DiagnosticCollector<JavaFileObject> problems = new DiagnosticCollector<>();
        List<JavaFileObject> files = new ArrayList<>();
        for (Source source : sources) {
            String code = withImport(source.code());
            files.add(new SimpleJavaFileObject(uri(source.name()), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return code;
                }
            });
        }
        List<String> options = List.of("-d", out.toString(), "-classpath", classpath, "-proc:none", "-nowarn",
                "-g", "--release", String.valueOf(Runtime.version().feature()));
        boolean ok;
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(problems, Locale.ENGLISH, StandardCharsets.UTF_8)) {
            ok = compiler.getTask(null, manager, problems, options, null, files).call();
        }
        if (ok) return;
        for (Diagnostic<? extends JavaFileObject> problem : problems.getDiagnostics()) {
            if (problem.getKind() == Diagnostic.Kind.ERROR) {
                String file = problem.getSource() != null ? problem.getSource().toUri().getPath().substring(1) : "";
                String className = Path.of(file.isEmpty() ? "Mod.java" : file).getFileName().toString().replace(".java", "");
                // A mod with more than one file says which file the mistake is in
                String inFile = sources.size() > 1 && !file.isEmpty() ? file : null;
                throw new MistakeException(explain(problem.getCode(), problem.getMessage(Locale.ENGLISH), className, problem.getLineNumber(), inFile));
            }
        }
        throw new MistakeException(Lang.t("Java couldn't compile it, but didn't say why."));
    }

    /**
     * javac's error, in words a beginner can act on. code is javac's own name for the error, like "compiler.err.expected".
     * file is which file the mistake is in, for mods with more than one file (null for just one).
     */
    static String explain(String code, String message, String className, long line, String file) {
        String first = message.lines().findFirst().orElse(message).trim();
        String what;
        if (code.startsWith("compiler.err.expected")) {
            if (first.contains("';'")) what = Lang.t("a ; is missing at the end of the line");
            else if (first.contains("')'")) what = Lang.t("a ) is missing");
            else if (first.contains("'('")) what = Lang.t("a ( is missing");
            else if (first.contains("'{'")) what = Lang.t("a { is missing");
            else what = Lang.t("something is missing here ({0})", first);
        } else if (code.startsWith("compiler.err.cant.resolve")) {
            Matcher symbol = Pattern.compile("symbol:\\s+\\w+\\s+(\\w+)").matcher(message);
            String name = symbol.find() ? symbol.group(1) : Lang.t("that name");
            what = Lang.t("Squid doesn't know \"{0}\". Check the spelling, and that big and small letters match", name);
        } else if (code.equals("compiler.err.premature.eof")) {
            what = Lang.t("a } is missing at the end. Every { needs a }");
        } else if (code.equals("compiler.err.unclosed.str.lit")) {
            what = Lang.t("a \" is missing. Text needs a \" at the start and the end");
        } else if (code.equals("compiler.err.class.public.should.be.in.file")) {
            what = Lang.t("the class name must match the file name. Change it to \"public class {0}\"", className);
        } else if (code.equals("compiler.err.not.stmt")) {
            what = Lang.t("this isn't a complete command. Did you forget the ( ) after a name?");
        } else if (code.startsWith("compiler.err.illegal.start")) {
            what = Lang.t("Java got confused here. Look for a missing ( ) { } or ;");
        } else if (code.equals("compiler.err.prob.found.req")) {
            what = Lang.t("that's the wrong kind of value here ({0})", first.replace("incompatible types: ", ""));
        } else if (code.equals("compiler.err.cant.apply.symbol") || code.equals("compiler.err.cant.apply.symbols")) {
            what = Lang.t("the things inside the ( ) aren't right for that command. Check what goes in them");
        } else {
            what = first;
        }
        if (file != null) {
            return line > 0 ? Lang.t("there's a mistake in {0} on line {1}: {2}", file, line, what)
                    : Lang.t("there's a mistake in {0}: {1}", file, what);
        }
        return line > 0 ? Lang.t("there's a mistake on line {0}: {1}", line, what) : Lang.t("there's a mistake: {0}", what);
    }

    /** "MyCoolMod" becomes "My Cool Mod". */
    static String spaced(String className) {
        return className.replace('_', ' ')
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")  // MyMod -> My Mod
                .replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ") // NotAMod -> Not A Mod, TNTRain -> TNT Rain
                .replaceAll("(?<=[A-Za-z])(?=[0-9])", " ")   // RainbowSheep2 -> Rainbow Sheep 2
                .trim();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteFolder(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(p);
        }
    }
}
