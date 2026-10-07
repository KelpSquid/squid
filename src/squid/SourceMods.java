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

    /** classpath is what mods can use: Squid itself and Minecraft with its libraries. */
    SourceMods(Path cache, String classpath) {
        this.cache = cache;
        this.classpath = classpath;
    }

    /** Builds an easy mod (or reuses last time's build) and describes it as a mod. */
    ModInfo compile(Path source) throws IOException {
        String fileName = source.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".java".length());
        if (!className.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new MistakeException(Lang.t("the file name can only use letters and numbers, with no spaces. Try {0}",
                    className.replaceAll("[^A-Za-z0-9_]", "") + ".java"));
        }
        String code = Files.readString(source, StandardCharsets.UTF_8);
        Matcher pkg = PACKAGE.matcher(code);
        String main = pkg.find() ? pkg.group(1) + "." + className : className;

        Path out = build(className, List.of(new Source(fileName, code)), null);
        String id = className.toLowerCase(Locale.ROOT).replace('_', '-');
        return new ModInfo(id, spaced(className), "1.0", Lang.t("Made from {0}", fileName), List.of(), List.of(), List.of(), main, out);
    }

    /** Builds a project folder. mod is what its squid.json says; the result is the same mod, pointing at the build. */
    ModInfo compileProject(Path folder, ModInfo mod) throws IOException {
        Path src = folder.resolve("src");
        if (!Files.isDirectory(src)) throw new MistakeException(Lang.t("it needs a src folder with its code in it."));
        List<Source> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                sources.add(new Source(folder.relativize(file).toString().replace('\\', '/'), Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        if (sources.isEmpty()) throw new MistakeException(Lang.t("its src folder has no .java files yet."));
        Path out = build(mod.id(), sources, folder.resolve("resources"));
        return new ModInfo(mod.id(), mod.name(), mod.version(), mod.description(), mod.authors(), mod.depends(),
                mod.minecraft(), mod.main(), out);
    }

    /** Builds a .squid file: unpacks its src and resources into the cache, then builds it like a project folder. */
    ModInfo compilePacked(Path packed, ModInfo mod) throws IOException {
        Path unpacked = cache.resolve(mod.id() + "-unpacked");
        deleteFolder(unpacked);
        try (ZipFile zip = new ZipFile(packed.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !(name.startsWith("src/") || name.startsWith("resources/"))) continue;
                Path target = unpacked.resolve(name).normalize();
                if (!target.startsWith(unpacked)) throw new IOException(Lang.t("it has a file that tries to leave its folder, so Squid won't open it."));
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return compileProject(unpacked, mod);
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
        sha.update((Main.VERSION + "\n" + Runtime.version().feature() + "\n" + System.getProperty("squid.gameClasspath"))
                .getBytes(StandardCharsets.UTF_8));
        Path out = cache.resolve(name + "-" + HexFormat.of().formatHex(sha.digest()).substring(0, 12));
        if (Files.exists(out.resolve("ok"))) return out;

        deleteOld(name);
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

    private void javac(List<Source> sources, Path out) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new MistakeException(Lang.t("this Java can't compile mods. Play it from Kelp, which uses one that can."));
        DiagnosticCollector<JavaFileObject> problems = new DiagnosticCollector<>();
        List<JavaFileObject> files = new ArrayList<>();
        for (Source source : sources) {
            String code = withImport(source.code());
            files.add(new SimpleJavaFileObject(URI.create("string:///" + source.name()), JavaFileObject.Kind.SOURCE) {
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

    /** Removes older builds of this mod, so the cache doesn't keep growing. */
    private void deleteOld(String name) throws IOException {
        if (!Files.isDirectory(cache)) return;
        try (Stream<Path> old = Files.list(cache)) {
            // Only this mod's builds: "mega-1a2b3c4d5e6f" belongs to mega, not to mega-mod
            Pattern mine = Pattern.compile(Pattern.quote(name) + "-[0-9a-f]{12}");
            for (Path folder : old.filter(p -> mine.matcher(p.getFileName().toString()).matches()).toList()) {
                deleteFolder(folder);
            }
        }
    }

    private static void deleteFolder(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(p);
        }
    }
}
