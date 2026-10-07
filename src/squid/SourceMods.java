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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Mods written as one .java file, dropped straight into the mods folder. No jar, no squid.json, no build step:
 * Squid compiles them itself as the game starts, using the compiler that comes with the game's Java.
 *
 * The file's name is the mod's name: MyCoolMod.java becomes "My Cool Mod". Squid adds "import squid.api.*;"
 * by itself, so a mod can say "extends EasyMod" without any imports.
 * Compiled mods are kept in mods/.squid-cache, so a mod is only compiled again after it changes.
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

    private final Path cache;
    private final String classpath;

    /** classpath is what mods can use: Squid itself and Minecraft with its libraries. */
    SourceMods(Path cache, String classpath) {
        this.cache = cache;
        this.classpath = classpath;
    }

    /** Compiles the file (or reuses last time's result) and describes it as a mod. */
    ModInfo compile(Path source) throws IOException {
        String fileName = source.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".java".length());
        if (!className.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new MistakeException("the file name can only use letters and numbers, with no spaces. Try "
                    + className.replaceAll("[^A-Za-z0-9_]", "") + ".java");
        }
        String code = Files.readString(source, StandardCharsets.UTF_8);
        Matcher pkg = PACKAGE.matcher(code);
        String main = pkg.find() ? pkg.group(1) + "." + className : className;

        Path out = cache.resolve(className + "-" + fingerprint(code));
        if (!Files.exists(out.resolve("ok"))) {
            deleteOld(className);
            Files.createDirectories(out);
            build(fileName, className, withImport(code), out);
            Files.writeString(out.resolve("ok"), fileName); // marks a finished compile, so a half-done one is redone
        }
        String id = className.toLowerCase(Locale.ROOT).replace('_', '-');
        return new ModInfo(id, spaced(className), "1.0", "Made from " + fileName, List.of(), List.of(), List.of(), main, out);
    }

    /** Adds Squid's import on the first line (after the package line, if there is one), so line numbers don't move. */
    static String withImport(String code) {
        Matcher pkg = PACKAGE.matcher(code);
        if (pkg.find()) return code.substring(0, pkg.end()) + " " + AUTO_IMPORT + code.substring(pkg.end());
        return AUTO_IMPORT + code;
    }

    private void build(String fileName, String className, String code, Path out) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new MistakeException("this Java can't compile mods. Play it from Kelp, which uses one that can.");
        DiagnosticCollector<JavaFileObject> problems = new DiagnosticCollector<>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + fileName), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
        List<String> options = List.of("-d", out.toString(), "-classpath", classpath, "-proc:none", "-nowarn",
                "-g", "--release", String.valueOf(Runtime.version().feature()));
        boolean ok;
        try (StandardJavaFileManager files = compiler.getStandardFileManager(problems, Locale.ENGLISH, StandardCharsets.UTF_8)) {
            ok = compiler.getTask(null, files, problems, options, null, List.of(file)).call();
        }
        if (ok) return;
        for (Diagnostic<? extends JavaFileObject> problem : problems.getDiagnostics()) {
            if (problem.getKind() == Diagnostic.Kind.ERROR) {
                throw new MistakeException(explain(problem.getCode(), problem.getMessage(Locale.ENGLISH), className,
                        problem.getLineNumber()));
            }
        }
        throw new MistakeException("Java couldn't compile it, but didn't say why.");
    }

    /** javac's error, in words a beginner can act on. code is javac's own name for the error, like "compiler.err.expected". */
    static String explain(String code, String message, String className, long line) {
        String where = line > 0 ? "there's a mistake on line " + line + ": " : "there's a mistake: ";
        String first = message.lines().findFirst().orElse(message).trim();
        String what;
        if (code.startsWith("compiler.err.expected")) {
            if (first.contains("';'")) what = "a ; is missing at the end of the line";
            else if (first.contains("')'")) what = "a ) is missing";
            else if (first.contains("'('")) what = "a ( is missing";
            else if (first.contains("'{'")) what = "a { is missing";
            else what = "something is missing here (" + first + ")";
        } else if (code.startsWith("compiler.err.cant.resolve")) {
            Matcher symbol = Pattern.compile("symbol:\\s+\\w+\\s+(\\w+)").matcher(message);
            String name = symbol.find() ? symbol.group(1) : "that name";
            what = "Squid doesn't know \"" + name + "\". Check the spelling, and that big and small letters match";
        } else if (code.equals("compiler.err.premature.eof")) {
            what = "a } is missing at the end. Every { needs a }";
        } else if (code.equals("compiler.err.unclosed.str.lit")) {
            what = "a \" is missing. Text needs a \" at the start and the end";
        } else if (code.equals("compiler.err.class.public.should.be.in.file")) {
            what = "the class name must match the file name. Change it to \"public class " + className + "\"";
        } else if (code.equals("compiler.err.not.stmt")) {
            what = "this isn't a complete command. Did you forget the ( ) after a name?";
        } else if (code.startsWith("compiler.err.illegal.start")) {
            what = "Java got confused here. Look for a missing ( ) { } or ;";
        } else if (code.equals("compiler.err.prob.found.req")) {
            what = "that's the wrong kind of value here (" + first.replace("incompatible types: ", "") + ")";
        } else if (code.equals("compiler.err.cant.apply.symbol") || code.equals("compiler.err.cant.apply.symbols")) {
            what = "the things inside the ( ) aren't right for that command. Check what goes in them";
        } else {
            what = first;
        }
        return where + what;
    }

    /** "MyCoolMod" becomes "My Cool Mod". */
    static String spaced(String className) {
        return className.replace('_', ' ')
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")  // MyMod -> My Mod
                .replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ") // NotAMod -> Not A Mod, TNTRain -> TNT Rain
                .trim();
    }

    private static String fingerprint(String code) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            // The same code compiles differently for another Squid, Java or Minecraft, so they count too
            String all = code + "\n" + Main.VERSION + "\n" + Runtime.version().feature() + "\n" + System.getProperty("squid.gameClasspath");
            return HexFormat.of().formatHex(sha.digest(all.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Removes older compiles of this mod, so the cache doesn't keep growing. */
    private void deleteOld(String className) throws IOException {
        if (!Files.isDirectory(cache)) return;
        try (Stream<Path> old = Files.list(cache)) {
            for (Path folder : old.filter(p -> p.getFileName().toString().startsWith(className + "-")).toList()) {
                try (Stream<Path> walk = Files.walk(folder)) {
                    for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(p);
                }
            }
        }
    }
}
