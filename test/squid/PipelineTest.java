package squid;

import squid.api.ModInfo;
import squid.api.Squid;
import squid.api.SquidMod;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs Squid's whole pipeline without opening a game window.
 * args: <game classpath> <mods folder> <test-target folder>
 */
public class PipelineTest {
    static int failures = 0;

    static void check(String what, Object got, Object expected) {
        boolean ok = java.util.Objects.equals(got, expected);
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + what + " -> " + got + (ok ? "" : " (expected " + expected + ")"));
    }

    static ModInfo mod(String id, String... depends) {
        return new ModInfo(id, id, "1", "", List.of(), List.of(depends), List.of(), "x", Path.of("."));
    }

    /** Makes a mod jar holding just a squid.json. */
    static void modJar(Path file, String squidJson) throws java.io.IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("squid.json"));
            zip.write(squidJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    static String problem(List<ModInfo> mods) {
        try {
            Mods.inStartOrder(mods);
            return "no problem found";
        } catch (java.io.IOException e) {
            return e.getMessage();
        }
    }

    public static void main(String[] a) throws Throwable {
        List<URL> urls = new ArrayList<>();
        for (String e : a[0].split(File.pathSeparator)) urls.add(Path.of(e).toUri().toURL());
        urls.add(Path.of(a[2]).toUri().toURL());
        List<ModInfo> mods = Mods.find(Path.of(a[1]), "26.3").mods();
        check("mods found", mods.stream().map(ModInfo::id).sorted().toList().toString(), "[compass, fullbright, hello-squid, minimap, xray, zoom]");
        for (ModInfo m : mods) urls.add(m.jar().toUri().toURL());
        SquidClassLoader loader = new SquidClassLoader(urls.toArray(URL[]::new));
        Thread.currentThread().setContextClassLoader(loader);
        Main.registerBuiltInHooks();

        // Some Minecraft objects get made without running their constructors, which need the whole game
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);

        // Hooks on the demo class, registered the way a mod would
        Squid test = new Squid(mod("test"));
        List<Object> seen = new ArrayList<>();
        test.atEnd("demo.Target", "add", c -> c.setReturnValue((Integer) c.returnValue() * 10));
        test.atStart("demo.Target", "greet", c -> { if ("skip".equals(c.args()[0])) c.cancel("skipped"); });
        test.atEnd("demo.Target", "greet", c -> c.setReturnValue(c.returnValue() + "!"));
        test.atStart("demo.Target", "nothing", c -> seen.add(List.of(c.args())));
        test.atStart("demo.Target", "half", c -> c.cancel(1.5));
        test.atEnd("demo.Target", "pair", c -> ((int[]) c.returnValue())[1] = 99);
        test.atStart("demo.Target", "missing", c -> { });
        List<String> order = new ArrayList<>();
        test.atStart("demo.Target", "pair", c -> order.add("first"));
        test.atStart("demo.Target", "pair", c -> order.add("second"));

        // The real mod
        for (ModInfo m : mods) {
            ((SquidMod) loader.loadClass(m.main()).getDeclaredConstructor().newInstance()).init(new Squid(m));
        }

        Class<?> target = loader.loadClass("demo.Target");
        Object t = target.getDeclaredConstructor().newInstance();
        check("end hook changes an int", target.getMethod("add", int.class, int.class).invoke(t, 2, 3), 50);
        check("start hook cancels a static method (and skips end hooks)", target.getMethod("greet", String.class).invoke(null, "skip"), "skipped");
        check("end hook changes a String", target.getMethod("greet", String.class).invoke(null, "Sam"), "hi Sam!");
        check("end hook on the early return", target.getMethod("greet", String.class).invoke(null, (Object) null), "nobody!");
        target.getMethod("nothing", long.class, double.class).invoke(t, 7L, 2.5);
        check("start hook sees long and double args", seen.toString(), "[[7, 2.5]]");
        check("start hook cancels with a double", target.getMethod("half", double.class).invoke(t, 10.0), 1.5);
        target.getMethod("pair", int.class).invoke(t, 1);
        order.subList(2, order.size()).clear();
        check("start hooks run in the order they were added", order.toString(), "[first, second]");
        check("end hook edits an array", java.util.Arrays.toString((int[]) target.getMethod("pair", int.class).invoke(t, 4)), "[4, 99]");

        // Minecraft's real SplashManager, loaded and patched through Squid. Made without running its constructor.
        Class<?> splashManager = loader.loadClass("net.minecraft.client.resources.SplashManager");
        check("Minecraft class came from Squid's loader", splashManager.getClassLoader() == loader, true);
        Object manager = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, splashManager);
        Object splash = splashManager.getMethod("getSplash").invoke(manager);
        Field text = splash.getClass().getDeclaredField("splash");
        text.setAccessible(true);
        Object component = text.get(splash);
        Method getString = component.getClass().getMethod("getString");
        check("Minecraft's splash says", getString.invoke(component), "Squid is working!");

        // Mods' keys go into Minecraft's own key list, the one the Controls screen shows
        Class<?> options = loader.loadClass("net.minecraft.client.Options");
        java.lang.reflect.Field keyList = options.getField("keyMappings");
        check("Squid made the key list changeable", java.lang.reflect.Modifier.isFinal(keyList.getModifiers()), false);
        Object fakeOptions = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, options);
        Class<?> keyMapping = loader.loadClass("net.minecraft.client.KeyMapping");
        keyList.set(fakeOptions, java.lang.reflect.Array.newInstance(keyMapping, 0));
        KeyBindings.addTo(fakeOptions);
        Object[] keys = (Object[]) keyList.get(fakeOptions);
        List<Object> keyNames = new ArrayList<>();
        for (Object key : keys) keyNames.add(keyMapping.getMethod("getName").invoke(key));
        check("the mods' keys are in Minecraft's list", keyNames.stream().map(String::valueOf).sorted().toList().toString(),
                "[Bigger Minimap, Fullbright, World Map, X-Ray, Zoom]");
        KeyBindings.addTo(fakeOptions);
        check("adding again doesn't double them", ((Object[]) keyList.get(fakeOptions)).length, 5);

        // Zoom's hooks go into Minecraft's camera and mouse code, which must still load and pass Java's checks
        for (String name : new String[] {"net.minecraft.client.Camera", "net.minecraft.client.MouseHandler",
                "net.minecraft.client.gui.Hud", "net.minecraft.client.Minecraft",
                "net.minecraft.client.renderer.LightmapRenderStateExtractor", "net.minecraft.client.renderer.block.ModelBlockRenderer",
                "net.minecraft.client.renderer.block.FluidRenderer"}) {
            Class<?> patched = Class.forName(name, true, loader);
            check(name.substring(name.lastIndexOf('.') + 1) + " loads after the mods patched it", patched.getClassLoader() == loader, true);
        }

        // Squid tells Minecraft it's modded
        Object brand = loader.loadClass("net.minecraft.client.ClientBrandRetriever").getMethod("getClientModName").invoke(null);
        check("Minecraft's brand", brand, "squid");

        // Mods start after the mods they depend on, and problems get explained
        List<ModInfo> ordered = Mods.inStartOrder(List.of(mod("addon", "library"), mod("library"), mod("solo")));
        check("dependencies start first", ordered.stream().map(ModInfo::id).toList().toString(), "[library, addon, solo]");
        check("a missing dependency is explained", problem(List.of(mod("addon", "library"))),
                "addon needs the mod \"library\", but it isn't in the mods folder.");
        check("a dependency loop is explained", problem(List.of(mod("a", "b"), mod("b", "a"))),
                "These mods need each other in a loop, so none of them can start first: a -> b -> a");

        // Mods that can't work this time are skipped, with a reason, and the rest still load
        Path folder = java.nio.file.Files.createTempDirectory("squid-mods-test");
        modJar(folder.resolve("a-library.jar"), "{\"id\": \"library\", \"name\": \"Library\", \"version\": \"1\", \"main\": \"x\", \"minecraft\": \"26.2\"}");
        modJar(folder.resolve("b-addon.jar"), "{\"id\": \"addon\", \"name\": \"Addon\", \"version\": \"1\", \"main\": \"x\", \"depends\": [\"library\"]}");
        modJar(folder.resolve("c-needs-ghost.jar"), "{\"id\": \"lonely\", \"name\": \"Lonely\", \"version\": \"1\", \"main\": \"x\", \"depends\": [\"ghost\"]}");
        modJar(folder.resolve("d-zoom.jar"), "{\"id\": \"zoom\", \"name\": \"Zoom\", \"version\": \"1\", \"main\": \"x\", \"minecraft\": [\"26.2\", \"26.3.x\"]}");
        modJar(folder.resolve("e-zoom (1).jar"), "{\"id\": \"zoom\", \"name\": \"Zoom\", \"version\": \"1\", \"main\": \"x\"}");
        Mods.Found found = Mods.find(folder, "26.3.1");
        check("only mods that can work are loaded", found.mods().stream().map(ModInfo::id).toList().toString(), "[zoom]");
        java.util.Map<String, String> why = new java.util.TreeMap<>();
        for (Mods.Skipped s : found.skipped()) why.put(s.name(), s.reason());
        check("wrong Minecraft version", why.get("Library"), "it was made for Minecraft 26.2, not 26.3.1. Look for an update to it.");
        check("needs a mod that was skipped", why.get("Addon"), "it needs Library, which was skipped too.");
        check("needs a missing mod", why.get("Lonely"), "it needs the mod \"ghost\", but it isn't in the mods folder.");
        check("a second copy", why.get("Zoom"), "it's another copy of d-zoom.jar. You can delete e-zoom (1).jar.");
        check("26.3.x means every 26.3 update", mod("any").worksOn("26.3") && found.mods().get(0).worksOn("26.3")
                && found.mods().get(0).worksOn("26.3.9") && !found.mods().get(0).worksOn("26.30"), true);

        // The report Kelp reads
        Path reportFolder = java.nio.file.Files.createTempDirectory("squid-report-test");
        Report report = new Report(reportFolder);
        report.running(List.of(mod("library"), mod("addon", "library")));
        java.util.Map<String, Object> running = Json.object(Json.parse(java.nio.file.Files.readString(reportFolder.resolve("squid-report.json"))));
        check("report while running", running.get("status") + " with " + Json.array(running.get("mods")).size() + " mods", "running with 2 mods");
        report.failed(List.of(), "Hello \"Squid\"", Main.describe(new NullPointerException("oops\nline two")));
        java.util.Map<String, Object> failed = Json.object(Json.parse(java.nio.file.Files.readString(reportFolder.resolve("squid-report.json"))));
        check("report after a crash", failed.get("status") + " | " + failed.get("mod") + " | " + failed.get("error"),
                "failed | Hello \"Squid\" | NullPointerException: oops\nline two");

        java.util.Map<String, Object> clean = Json.object(Json.parse(java.nio.file.Files.readString(reportFolder.resolve("squid-report.json"))));
        check("no problems listed when nothing broke", Json.array(clean.get("problems")).size(), 0);
        report.skipped(found.skipped());
        report.running(List.of(mod("zoom")));
        java.util.Map<String, Object> withSkipped = Json.object(Json.parse(java.nio.file.Files.readString(reportFolder.resolve("squid-report.json"))));
        check("skipped mods are in the report", Json.array(withSkipped.get("skipped")).size(), 4);
        report.running(List.of(mod("library")));
        report.problem("Library", "NoSuchMethodError: Gui.render");
        report.problem("Library", "the same mod again");
        java.util.Map<String, Object> troubled = Json.object(Json.parse(java.nio.file.Files.readString(reportFolder.resolve("squid-report.json"))));
        List<Object> problems = Json.array(troubled.get("problems"));
        check("a turned-off hook is reported once", problems.size(), 1);
        check("the problem names the mod and error", Json.object(problems.get(0)).get("mod") + " | " + Json.object(problems.get(0)).get("error"),
                "Library | NoSuchMethodError: Gui.render");
        check("the game still counts as running", troubled.get("status"), "running");
        check("no .part file is left over", java.nio.file.Files.exists(reportFolder.resolve("squid-report.json.part")), false);

        // A hook that keeps breaking (like a mod made for another Minecraft version) gets turned off
        int[] calls = {0};
        int broken = Hooks.register("broken-mod", call -> {
            calls[0]++;
            throw new NoSuchMethodError("net.minecraft.client.Gui.oldMethod()");
        });
        int fine = Hooks.register("fine-mod", call -> call.setReturnValue("still works"));
        for (int i = 0; i < 6; i++) Hooks.start(broken, null, new Object[0]);
        check("a broken hook is tried 3 times, then turned off", calls[0], 3);
        check("other mods' hooks keep working", Hooks.end(fine, null, new Object[0], "vanilla"), "still works");

        // Mods written as one .java file: Squid compiles them, and explains mistakes in plain words
        Path easy = java.nio.file.Files.createTempDirectory("squid-easy-test");
        String[][] easyMods = {
                {"Hello", "public class Hello extends EasyMod {\n    void start() {\n        say(\"Hi!\");\n"
                        + "        onKey(\"G\", () -> say(\"You pressed G\"));\n        splash(\"Made with Squid!\");\n    }\n}\n"},
                {"NoSemicolon", "public class NoSemicolon extends EasyMod {\n    void start() {\n        say(\"Hi!\")\n    }\n}\n"},
                {"Typo", "public class Typo extends EasyMod {\n    void start() {\n        sya(\"Hi!\");\n    }\n}\n"},
                {"MissingBrace", "public class MissingBrace extends EasyMod {\n    void start() {\n        say(\"Hi!\");\n    }\n"},
                {"DividesByZero", "public class DividesByZero extends EasyMod {\n    void start() {\n        int zero = 0;\n"
                        + "        say(10 / zero);\n    }\n}\n"},
                {"BadKey", "public class BadKey extends EasyMod {\n    void start() {\n        onKey(\"NOPE\", () -> say(\"x\"));\n    }\n}\n"},
                {"NotAMod", "public class NotAMod {\n}\n"},
        };
        for (String[] m : easyMods) java.nio.file.Files.writeString(easy.resolve(m[0] + ".java"), m[1]);
        SourceMods sources = new SourceMods(easy.resolve(".squid-cache"), System.getProperty("java.class.path") + File.pathSeparator + a[0]);
        Mods.Found easyFound = Mods.find(easy, "26.3", sources);
        check("mods that compile are found", easyFound.mods().stream().map(ModInfo::name).sorted().toList().toString(),
                "[Bad Key, Divides By Zero, Hello, Not A Mod]");
        java.util.Map<String, String> mistakes = new java.util.TreeMap<>();
        for (Mods.Skipped s : easyFound.skipped()) mistakes.put(s.name(), s.reason());
        check("a missing ;", mistakes.get("NoSemicolon.java"), "there's a mistake on line 3: a ; is missing at the end of the line");
        check("a misspelled command", mistakes.get("Typo.java"),
                "there's a mistake on line 3: Squid doesn't know \"sya\". Check the spelling, and that big and small letters match");
        check("a missing }", mistakes.get("MissingBrace.java"), "there's a mistake on line 4: a } is missing at the end. Every { needs a }");

        List<URL> easyUrls = new ArrayList<>(urls);
        for (ModInfo m : easyFound.mods()) easyUrls.add(m.jar().toUri().toURL());
        SquidClassLoader easyLoader = new SquidClassLoader(easyUrls.toArray(URL[]::new));
        Main.setGameLoader(easyLoader);
        List<Mods.Skipped> notStarted = new ArrayList<>();
        List<ModInfo> started = Main.start(easyFound.mods(), easyLoader, notStarted);
        check("a good easy mod starts", started.stream().map(ModInfo::name).toList().toString(), "[Hello]");
        for (Mods.Skipped s : notStarted) mistakes.put(s.name(), s.reason());
        check("an error while starting says which line", mistakes.get("Divides By Zero"), "there's a problem on line 4: you divided by zero");
        check("an unknown key is explained", mistakes.get("Bad Key"),
                "there's a problem on line 3: Squid doesn't know the key \"NOPE\". Try a letter like \"G\", a number like \"5\", or a key like \"SPACE\" or \"F6\"");
        check("a class that isn't a mod is explained", mistakes.get("Not A Mod"),
                "it isn't a Squid mod yet. Write \"extends EasyMod\" after its class name");
        Object easyOptions = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, options);
        keyList.set(easyOptions, java.lang.reflect.Array.newInstance(keyMapping, 0));
        KeyBindings.addTo(easyOptions);
        List<Object> easyKeys = new ArrayList<>();
        for (Object key : (Object[]) keyList.get(easyOptions)) {
            easyKeys.add(keyMapping.getMethod("getName").invoke(key) + "=" + keyMapping.getMethod("getDefaultKey").invoke(key));
        }
        check("onKey(\"G\") adds a G key to Controls", easyKeys.stream().map(String::valueOf).filter(k -> k.startsWith("Hello")).toList().toString(),
                "[Hello (G)=key.keyboard.g]");

        long compiledAt = java.nio.file.Files.getLastModifiedTime(easyFound.mods().stream()
                .filter(m -> m.name().equals("Hello")).findFirst().orElseThrow().jar().resolve("ok")).toMillis();
        Thread.sleep(50);
        ModInfo again = sources.compile(easy.resolve("Hello.java"));
        check("an unchanged mod isn't compiled again", java.nio.file.Files.getLastModifiedTime(again.jar().resolve("ok")).toMillis(), compiledAt);
        check("file names become mod names", SourceMods.spaced("MyCoolMod") + " / " + SourceMods.spaced("TNT_Rain"), "My Cool Mod / TNT Rain");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
