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
        check("mods found", mods.stream().map(ModInfo::id).sorted().toList().toString(), "[boost, compass, fullbright, hello-squid, minimap, xray, zoom]");
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
                "net.minecraft.client.renderer.block.FluidRenderer", "net.minecraft.client.renderer.entity.EntityRenderDispatcher",
                "net.minecraft.client.renderer.blockentity.BlockEntityRenderer", "net.minecraft.client.renderer.blockentity.ChestRenderer"}) {
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

        // Projects: a folder with many files and resources, and the same thing packed into one .squid file
        Path projects = java.nio.file.Files.createTempDirectory("squid-project-test");
        Path mega = projects.resolve("MegaMod");
        java.nio.file.Files.createDirectories(mega.resolve("src/parts"));
        java.nio.file.Files.createDirectories(mega.resolve("resources/megamod"));
        java.nio.file.Files.writeString(mega.resolve("squid.json"), "{\"name\": \"Mega Mod\"}");
        java.nio.file.Files.writeString(mega.resolve("src/MegaMod.java"), "import parts.Greeting;\n\npublic class MegaMod extends EasyMod {\n"
                + "    void start() {\n        say(Greeting.text());\n    }\n}\n");
        java.nio.file.Files.writeString(mega.resolve("src/parts/Greeting.java"), "package parts;\n\npublic class Greeting {\n"
                + "    public static String text() {\n        return \"Hi from a project!\";\n    }\n}\n");
        java.nio.file.Files.writeString(mega.resolve("resources/megamod/hello.txt"), "a picture would go here");
        Path brokenProject = projects.resolve("Broken");
        java.nio.file.Files.createDirectories(brokenProject.resolve("src"));
        java.nio.file.Files.writeString(brokenProject.resolve("squid.json"), "{}");
        java.nio.file.Files.writeString(brokenProject.resolve("src/Broken.java"), "public class Broken extends EasyMod {\n    void start() {\n    }\n}\n");
        java.nio.file.Files.writeString(brokenProject.resolve("src/Second.java"), "public class Second {\n    int x = 1\n}\n");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(projects.resolve("Tiny.squid")))) {
            String[][] packed = {
                    {"squid.json", "{\"id\": \"tiny\", \"name\": \"Tiny\", \"version\": \"2.0\", \"main\": \"MegaMod\"}"},
                    {"src/MegaMod.java", java.nio.file.Files.readString(mega.resolve("src/MegaMod.java"))},
                    {"src/parts/Greeting.java", java.nio.file.Files.readString(mega.resolve("src/parts/Greeting.java"))},
                    {"resources/megamod/hello.txt", "a picture would go here"},
            };
            for (String[] entry : packed) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry[0]));
                zip.write(entry[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        SourceMods projectSources = new SourceMods(projects.resolve(".squid-cache"), System.getProperty("java.class.path") + File.pathSeparator + a[0]);
        Mods.Found projectFound = Mods.find(projects, "26.3", projectSources);
        check("a project folder and a .squid file are mods", projectFound.mods().stream()
                .map(m -> m.id() + " " + m.name() + " " + m.version()).sorted().toList().toString(), "[mega-mod Mega Mod 1.0, tiny Tiny 2.0]");
        check("a project's resources come with it", projectFound.mods().stream()
                .allMatch(m -> java.nio.file.Files.exists(m.jar().resolve("megamod/hello.txt"))), true);
        check("a mistake in a project says which file", projectFound.skipped().stream().map(Mods.Skipped::reason).toList().toString(),
                "[there's a mistake in src/Second.java on line 2: a ; is missing at the end of the line]");
        List<URL> projectUrls = new ArrayList<>(urls);
        for (ModInfo m : projectFound.mods()) projectUrls.add(m.jar().toUri().toURL());
        SquidClassLoader projectLoader = new SquidClassLoader(projectUrls.toArray(URL[]::new));
        Main.setGameLoader(projectLoader);
        List<ModInfo> projectsStarted = Main.start(projectFound.mods(), projectLoader, new ArrayList<>());
        check("projects start", projectsStarted.stream().map(ModInfo::name).toList().toString(), "[Mega Mod, Tiny]");
        Path evil = projects.resolve("Evil.squid");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(evil))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("squid.json"));
            zip.write("{}".getBytes());
            zip.putNextEntry(new java.util.zip.ZipEntry("src/../../escape.java"));
            zip.write("x".getBytes());
            zip.closeEntry();
        }
        String escaped;
        try {
            projectSources.compilePacked(evil, Mods.readPacked(evil));
            escaped = "it opened";
        } catch (java.io.IOException e) {
            escaped = e.getMessage();
        }
        check("a .squid file can't put files outside its folder", escaped, "it has a file that tries to leave its folder, so Squid won't open it.");

        // The Store: its list, safe file names, installing with a fingerprint check, and its Minecraft parts loading
        String storeList = "{\"items\": ["
                + "{\"id\": \"xray\", \"type\": \"mod\", \"name\": \"X-Ray\", \"author\": \"Samuel\", \"minecraft\": \"26.3.x\", \"devPicked\": true,"
                + " \"file\": \"xray-1.0.0.jar\", \"url\": \"https://example.com/x.jar\", \"sha256\": \"ABC\", \"size\": 5},"
                + "{\"id\": \"sneaky\", \"type\": \"mod\", \"name\": \"Sneaky\", \"file\": \"../../evil.jar\", \"url\": \"u\", \"sha256\": \"a\"},"
                + "{\"id\": \"packed\", \"type\": \"resourcepack\", \"name\": \"Packed\", \"file\": \"wrong.jar\", \"url\": \"u\", \"sha256\": \"a\"},"
                + "{\"id\": \"old\", \"type\": \"resourcepack\", \"name\": \"Old Pack\", \"minecraft\": [\"1.20.x\"], \"file\": \"old.zip\", \"url\": \"u\", \"sha256\": \"a\"}]}";
        List<squidstore.Catalog.Item> storeItems = squidstore.Catalog.parse(storeList);
        check("the store list keeps safe items only", storeItems.stream().map(squidstore.Catalog.Item::id).toList().toString(), "[xray, old]");
        List<squidstore.Catalog.Item> capeItems = squidstore.Catalog.parse("{\"items\": ["
                + "{\"id\": \"wave\", \"type\": \"cape\", \"name\": \"Wave\", \"file\": \"wave.png\", \"url\": \"u\", \"sha256\": \"a\"},"
                + "{\"id\": \"notpng\", \"type\": \"cape\", \"name\": \"Bad\", \"file\": \"cape.jar\", \"url\": \"u\", \"sha256\": \"a\"}]}");
        check("a .squid file can be a store mod", squidstore.Catalog.parse("{\"items\": [{\"id\": \"tiny\", \"type\": \"mod\", \"name\": \"Tiny\","
                + " \"file\": \"tiny-2.0.squid\", \"url\": \"u\", \"sha256\": \"a\"}]}").size(), 1);
        check("capes are store items too (pictures only)", capeItems.stream().map(squidstore.Catalog.Item::id).toList().toString(), "[wave]");
        System.setProperty("squid.home", "/kelp-home");
        check("a store cape goes in Kelp's capes folder, for every instance",
                squidstore.Installer.target(capeItems.get(0), Path.of("/kelp-home/instances/Survival")).toString().replace('\\', '/'),
                "/kelp-home/capes/wave.png");
        System.clearProperty("squid.home");
        check("store items know their Minecraft versions and Dev-picked", storeItems.get(0).worksOn("26.3.1") + " "
                + storeItems.get(1).worksOn("26.3") + " " + storeItems.get(0).devPicked() + " " + storeItems.get(0).sha256(), "true false true abc");

        Path storeGame = java.nio.file.Files.createTempDirectory("squid-store-test");
        Path hosted = java.nio.file.Files.createTempDirectory("squid-store-files");
        byte[] modBytes = "pretend mod".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(hosted.resolve("fun.jar"), modBytes);
        String goodHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(modBytes));
        squidstore.Catalog.Item fun = new squidstore.Catalog.Item("fun", "mod", "Fun", "Sam", "", List.of(), false, "fun-1.0.jar",
                hosted.resolve("fun.jar").toUri().toString(), goodHash, modBytes.length, "1.0");
        check("not installed yet", squidstore.Installer.installed(fun, storeGame), false);
        java.nio.file.Files.createDirectories(storeGame.resolve("mods"));
        modJar(storeGame.resolve("mods/my-old-fun.jar"), "{\"id\": \"fun\", \"name\": \"Fun\", \"version\": \"0.9\", \"main\": \"x\"}");
        check("a mod is installed even under another file name (found by its id)", squidstore.Installer.installed(fun, storeGame), true);
        check("an older installed version means Update", squidstore.Installer.updateAvailable(fun, storeGame), true);
        check("versions compare by number", squidstore.Installer.compare("1.10", "1.9") + " " + squidstore.Installer.compare("1.0", "1.0.0")
                + " " + squidstore.Installer.compare("1.0-beta", "1.0"), "1 0 -1");
        squidstore.Installer.install(fun, storeGame);
        check("reinstalling replaces the old copy of the same mod", java.nio.file.Files.exists(storeGame.resolve("mods/my-old-fun.jar")), false);
        modJar(storeGame.resolve("mods/fun-1.0.jar"), "{\"id\": \"fun\", \"name\": \"Fun\", \"version\": \"1.0\", \"main\": \"x\"}");
        check("the same version means no update", squidstore.Installer.updateAvailable(fun, storeGame), false);
        java.nio.file.Files.write(storeGame.resolve("mods/fun-1.0.jar"), modBytes); // back to the store's real file
        check("installing puts a mod in mods/", java.nio.file.Files.readString(storeGame.resolve("mods/fun-1.0.jar")) + " "
                + squidstore.Installer.installed(fun, storeGame), "pretend mod true");
        squidstore.Catalog.Item swapped = new squidstore.Catalog.Item("swapped", "resourcepack", "Swapped", "", "", List.of(), false,
                "swapped.zip", hosted.resolve("fun.jar").toUri().toString(), "0".repeat(64), -1, "");
        String swapProblem;
        try {
            squidstore.Installer.install(swapped, storeGame);
            swapProblem = "installed anyway";
        } catch (java.io.IOException e) {
            swapProblem = e.getMessage();
        }
        check("a file that doesn't match its fingerprint is refused", swapProblem + " | left behind: "
                + java.nio.file.Files.exists(storeGame.resolve("resourcepacks/swapped.zip")), "it arrived damaged, so it wasn't installed | left behind: false");

        List<URL> storeUrls = new ArrayList<>(urls);
        storeUrls.add(Path.of(a[3]).toUri().toURL());
        SquidClassLoader storeLoader = new SquidClassLoader(storeUrls.toArray(URL[]::new));
        Main.registerBuiltInHooks(); // the title screen hook needs a fresh TitleScreen in the new loader
        Squid storeSquid = new Squid(mod("squid-store"));
        ((SquidMod) storeLoader.loadClass("squidstore.Store").getDeclaredConstructor().newInstance()).init(storeSquid);
        for (String name : new String[] {"net.minecraft.client.gui.screens.TitleScreen", "squidstore.StoreScreen"}) {
            Class<?> loaded = Class.forName(name, true, storeLoader);
            check(name.substring(name.lastIndexOf('.') + 1) + " loads with the Store", loaded.getClassLoader() == storeLoader, true);
        }

        // The Squid Count: points like gamerscore, each advancement only once, kept in a file
        Path countPath = java.nio.file.Files.createTempDirectory("squid-count-test").resolve("squid-count.json");
        squidcount.CountFile counts = squidcount.CountFile.load(countPath);
        check("points: task, goal, challenge", squidcount.CountFile.points("task") + " " + squidcount.CountFile.points("goal") + " "
                + squidcount.CountFile.points("challenge"), "10 25 50");
        int first = counts.earn("abc", "Sam", "minecraft:story/mine_stone", 10);
        int twice = counts.earn("abc", "Sam", "minecraft:story/mine_stone", 10);
        counts.earn("abc", "Sam", "minecraft:adventure/kill_all_mobs", 50);
        counts.earn("def", "Bob \"B\"", "minecraft:story/mine_stone", 10);
        counts.save();
        squidcount.CountFile reread = squidcount.CountFile.load(countPath);
        check("an advancement counts once, and counts are kept per player", first + " " + twice + " | " + reread.player("abc").points + " "
                + reread.player("abc").earned.size() + " | " + reread.player("def").points + " " + reread.player("def").name, "10 0 | 60 2 | 10 Bob \"B\"");
        java.nio.file.Files.writeString(countPath, "{\"players\": {\"abc\": {\"points\": 5");
        check("a broken count file starts fresh instead of crashing", squidcount.CountFile.load(countPath).player("abc").points, 0);

        List<URL> countUrls = new ArrayList<>(urls);
        countUrls.add(Path.of(a[4]).toUri().toURL());
        SquidClassLoader countLoader = new SquidClassLoader(countUrls.toArray(URL[]::new));
        ((SquidMod) countLoader.loadClass("squidcount.SquidCount").getDeclaredConstructor().newInstance()).init(new Squid(mod("squid-count")));
        Class<?> clientAdvancements = Class.forName("net.minecraft.client.multiplayer.ClientAdvancements", true, countLoader);
        check("ClientAdvancements loads with the Squid Count's hook", clientAdvancements.getClassLoader() == countLoader, true);

        // Skins and capes: telling them apart, bringing them in, remembering picks, and getting a skin by name
        Path wardrobeHome = java.nio.file.Files.createTempDirectory("squid-wardrobe-test");
        squidskins.Wardrobe wardrobe = new squidskins.Wardrobe(wardrobeHome);
        Path pictures = java.nio.file.Files.createTempDirectory("squid-pictures");
        Path skinPicture = pictures.resolve("cool skin.png");
        Path capePicture = pictures.resolve("my cape.png");
        Path oddPicture = pictures.resolve("photo.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", skinPicture.toFile());
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", capePicture.toFile());
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(100, 75, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", oddPicture.toFile());
        squidskins.Wardrobe.Kind[] kind = new squidskins.Wardrobe.Kind[1];
        String skinName = wardrobe.bringIn(skinPicture, kind);
        String skinKind = String.valueOf(kind[0]);
        String capeName = wardrobe.bringIn(capePicture, kind);
        check("square pictures are skins, wide ones are capes", skinName + " " + skinKind + " | " + capeName + " " + kind[0],
                "cool skin.png SKIN | my cape.png CAPE");
        check("bringing the same picture in again keeps both", wardrobe.bringIn(skinPicture, kind), "cool skin (2).png");
        String oddProblem;
        try {
            wardrobe.bringIn(oddPicture, kind);
            oddProblem = "brought in";
        } catch (java.io.IOException e) {
            oddProblem = e.getMessage();
        }
        check("a picture that's neither is explained", oddProblem, "That picture is 100x75. Skins are 64x64, capes are 64x32 (animated ones stack their frames: 64x96, 64x128...).");
        check("the wardrobe lists them", squidskins.Wardrobe.pictures(wardrobe.skins()) + " " + squidskins.Wardrobe.pictures(wardrobe.capes()),
                "[cool skin (2).png, cool skin.png] [my cape.png]");
        wardrobe.choose("abc", new squidskins.Wardrobe.Choice("cool skin.png", true, "kelp"));
        squidskins.Wardrobe reopened = new squidskins.Wardrobe(wardrobeHome);
        check("picks are remembered per player", reopened.choice("abc") + " | " + reopened.choice("someone-else"),
                "Choice[skin=cool skin.png, slim=true, cape=kelp, effects=[]] | Choice[skin=, slim=false, cape=, effects=[]]");
        wardrobe.choose("abc", reopened.choice("abc").toggled(squidskins.CapeEffects.Effect.ENCHANTED).toggled(squidskins.CapeEffects.Effect.BUBBLES)
                .withCape("squid"));
        squidskins.Wardrobe.Choice withEffects = new squidskins.Wardrobe(wardrobeHome).choice("abc");
        check("cape effects are remembered", withEffects.cape() + " " + withEffects.effects(), "squid [enchanted, bubbles]");
        check("an effect switches off again", withEffects.toggled(squidskins.CapeEffects.Effect.ENCHANTED).effects().toString(), "[bubbles]");
        check("effects Squid doesn't know are left out", squidskins.CapeEffects.parse(List.of("snow", "lasers", "glow")).toString(), "[GLOW, SNOW]");

        // Official capes: only links to Mojang's texture server are kept, and pictures come from there
        List<squidskins.OfficialCapes.Cape> officialCapes = squidskins.OfficialCapes.parse("{\"capes\": ["
                + "{\"id\": \"migrator\", \"name\": \"Migrator\", \"texture\": \"https://textures.minecraft.net/texture/2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933\"},"
                + "{\"id\": \"sneaky\", \"name\": \"Sneaky\", \"texture\": \"https://example.com/texture/2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933\"},"
                + "{\"id\": \"odd\", \"name\": \"Odd\", \"texture\": \"https://textures.minecraft.net/texture/../../x\"}]}");
        check("only links to Mojang's texture server are official capes", officialCapes.stream().map(c -> c.name() + " " + c.choice().substring(0, 16)).toList().toString(),
                "[Migrator official:2340c0e]");
        com.sun.net.httpserver.HttpServer textureServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        byte[] capePng;
        try (java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", png);
            capePng = png.toByteArray();
        }
        java.util.concurrent.atomic.AtomicInteger textureAsks = new java.util.concurrent.atomic.AtomicInteger();
        textureServer.createContext("/texture/", exchange -> {
            textureAsks.incrementAndGet();
            byte[] body = exchange.getRequestURI().getPath().endsWith("aaaa") ? "not a picture".getBytes() : capePng;
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        textureServer.start();
        String realTextures = squidskins.OfficialCapes.mojangTextures;
        squidskins.OfficialCapes.mojangTextures = "http://127.0.0.1:" + textureServer.getAddress().getPort() + "/texture/";
        try {
            String hash = "2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933";
            Path got = squidskins.OfficialCapes.fetch(wardrobe.officialCapes(), hash);
            squidskins.OfficialCapes.fetch(wardrobe.officialCapes(), hash);
            check("an official cape is loaded from Mojang once, then kept", got.getFileName() + " " + textureAsks.get(), hash + ".png 1");
            String notCape;
            try {
                squidskins.OfficialCapes.fetch(wardrobe.officialCapes(), "1111111111111111111111111111111111111111aaaa");
                notCape = "kept";
            } catch (java.io.IOException e) {
                notCape = e.getMessage() + " " + java.nio.file.Files.exists(squidskins.OfficialCapes.file(wardrobe.officialCapes(), "1111111111111111111111111111111111111111aaaa"));
            }
            check("something that isn't a cape picture is thrown away", notCape, "That isn't a cape picture. false");
        } finally {
            squidskins.OfficialCapes.mojangTextures = realTextures;
            textureServer.stop(0);
        }

        // Animated capes: frames stacked top to bottom, played at 10 a second
        check("cape shapes", squidskins.CapeEffects.frames(64, 32) + " " + squidskins.CapeEffects.frames(64, 64) + " "
                + squidskins.CapeEffects.frames(64, 96) + " " + squidskins.CapeEffects.frames(128, 640) + " " + squidskins.CapeEffects.frames(64, 50), "1 0 3 10 0");
        java.awt.image.BufferedImage animated = new java.awt.image.BufferedImage(64, 96, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 96; y++) for (int x = 0; x < 64; x++) animated.setRGB(x, y, 0xFF000000 | (y / 32 + 1));
        Path animatedPicture = pictures.resolve("waves.png");
        javax.imageio.ImageIO.write(animated, "png", animatedPicture.toFile());
        wardrobe.bringIn(animatedPicture, kind);
        check("an animated cape is a cape", kind[0], squidskins.Wardrobe.Kind.CAPE);
        int[] strip = animated.getRGB(0, 0, 64, 96, null, 0, 64);
        check("each frame is cut out in turn", squidskins.CapeEffects.frame(strip, 64, squidskins.CapeEffects.frameAt(3, 0))[5] + " "
                + squidskins.CapeEffects.frame(strip, 64, squidskins.CapeEffects.frameAt(3, 150))[5] + " "
                + squidskins.CapeEffects.frame(strip, 64, squidskins.CapeEffects.frameAt(3, 250))[5] + " "
                + squidskins.CapeEffects.frame(strip, 64, squidskins.CapeEffects.frameAt(3, 300))[5],
                (0xFF000001) + " " + (0xFF000002) + " " + (0xFF000003) + " " + (0xFF000001));
        int[] gray = {0xFF808080, 0x00000000, 0xFF808080, 0xFF808080};
        squidskins.CapeEffects.paint(gray, 2, List.of(squidskins.CapeEffects.Effect.RAINBOW), 0);
        check("rainbow colors a cape and keeps see-through parts", (gray[0] != 0xFF808080) + " " + gray[1] + " " + (gray[0] != gray[2]) + " " + (gray[0] >>> 24),
                "true 0 true 255");

        byte[] fakeSkin;
        try (java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", png);
            fakeSkin = png.toByteArray();
        }
        com.sun.net.httpserver.HttpServer mojang = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String mojangBase = "http://127.0.0.1:" + mojang.getAddress().getPort();
        mojang.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status = 200;
            if (path.equals("/profiles/samUEL")) {
                body = "{\"id\": \"0123456789abcdef0123456789abcdef\", \"name\": \"Samuel\"}".getBytes();
            } else if (path.equals("/sessions/0123456789abcdef0123456789abcdef")) {
                String textures = "{\"textures\": {\"SKIN\": {\"url\": \"" + mojangBase + "/texture/abc\", \"metadata\": {\"model\": \"slim\"}},"
                        + " \"CAPE\": {\"url\": \"" + mojangBase + "/texture/cape\"}}}";
                body = ("{\"id\": \"0123456789abcdef0123456789abcdef\", \"name\": \"Samuel\", \"properties\": [{\"name\": \"textures\", \"value\": \""
                        + java.util.Base64.getEncoder().encodeToString(textures.getBytes()) + "\"}]}").getBytes();
            } else if (path.equals("/texture/abc")) {
                body = fakeSkin;
            } else {
                status = 404;
                body = new byte[0];
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        mojang.start();
        String[] realMojang = {squidskins.Wardrobe.mojangProfiles, squidskins.Wardrobe.mojangSessions, squidskins.Wardrobe.skinServer};
        squidskins.Wardrobe.mojangProfiles = mojangBase + "/profiles/";
        squidskins.Wardrobe.mojangSessions = mojangBase + "/sessions/";
        squidskins.Wardrobe.skinServer = mojangBase + "/texture/";
        try {
            squidskins.Wardrobe.Fetched fetched = wardrobe.fetchSkin("samUEL");
            check("a player's skin by name, with their arm size", fetched.file() + " slim=" + fetched.slim() + " "
                    + java.nio.file.Files.exists(wardrobe.skins().resolve("Samuel.png")), "Samuel.png slim=true true");
            String nobody;
            try {
                wardrobe.fetchSkin("nobody_here");
                nobody = "found";
            } catch (java.io.IOException e) {
                nobody = e.getMessage();
            }
            check("a name nobody has is explained", nobody, "Nobody is called nobody_here.");
            String badName;
            try {
                wardrobe.fetchSkin("no spaces!");
                badName = "looked up";
            } catch (java.io.IOException e) {
                badName = e.getMessage();
            }
            check("a name that can't be a Minecraft name isn't looked up", badName, "Minecraft names are 3-16 letters, numbers or _.");
        } finally {
            squidskins.Wardrobe.mojangProfiles = realMojang[0];
            squidskins.Wardrobe.mojangSessions = realMojang[1];
            squidskins.Wardrobe.skinServer = realMojang[2];
            mojang.stop(0);
        }

        List<URL> skinUrls = new ArrayList<>(urls);
        skinUrls.add(Path.of(a[5]).toUri().toURL());
        skinUrls.add(Path.of(a[3]).toUri().toURL()); // the wardrobe uses the Store for community capes
        SquidClassLoader skinLoader = new SquidClassLoader(skinUrls.toArray(URL[]::new));
        Main.setGameLoader(skinLoader);
        ((SquidMod) skinLoader.loadClass("squidskins.Skins").getDeclaredConstructor().newInstance()).init(new Squid(mod("squid-skins")));
        for (String name : new String[] {"net.minecraft.client.player.AbstractClientPlayer", "net.minecraft.client.gui.screens.options.SkinCustomizationScreen",
                "squidskins.WardrobeScreen", "squidskins.PaintScreen", "squidskins.NameScreen", "squidskins.FilePicker", "squidskins.EffectsScreen", "squidskins.CapeBrowserScreen",
                "net.minecraft.client.renderer.entity.layers.CapeLayer"}) {
            // Minecraft's player class can't be started without the whole game, so it's only loaded (which applies the patch)
            Class<?> loaded = Class.forName(name, !name.startsWith("net.minecraft.client.player"), skinLoader);
            check(name.substring(name.lastIndexOf('.') + 1) + " loads with the wardrobe", loaded.getClassLoader() == skinLoader, true);
        }
        check("the Kelp and Squid capes are in the jar", skinLoader.getResource("squidskins/capes/kelp.png") != null
                && skinLoader.getResource("squidskins/capes/squid.png") != null, true);

        // Live reload: saving a mod built from code swaps the new version in while the game runs
        Path live = java.nio.file.Files.createTempDirectory("squid-reload-test");
        Path ticker = live.resolve("Ticker.java");
        java.nio.file.Files.writeString(ticker, "public class Ticker implements SquidMod {\n    public void init(Squid s) {\n        s.onTick(() -> squid.ReloadProbe.value = 1);\n    }\n}\n");
        SourceMods liveSources = new SourceMods(live.resolve(".squid-cache"), System.getProperty("java.class.path") + File.pathSeparator + a[0]);
        Mods.Found liveFound = Mods.find(live, "26.3", liveSources);
        SquidClassLoader liveLoader = new SquidClassLoader(urls.toArray(URL[]::new));
        Main.setGameLoader(liveLoader);
        Main.start(liveFound.mods(), liveLoader, new ArrayList<>());
        for (ModInfo m : liveFound.mods()) Main.updateMod(m);
        Reloader reloader = new Reloader(live, liveSources, liveLoader, "26.3");
        reloader.check(); // learns what's there now
        ReloadProbe.value = 0;
        Events.runTicks();
        check("a mod built from code runs its onTick", ReloadProbe.value, 1);

        java.nio.file.Files.writeString(ticker, "public class Ticker implements SquidMod {\n    public void init(Squid s) {\n        s.onTick(() -> squid.ReloadProbe.value = 2);\n    }\n}\n");
        java.nio.file.Files.setLastModifiedTime(ticker, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        reloader.check();
        ReloadProbe.value = 0;
        Events.runTicks();
        check("saving it swaps the new version in, and the old one stops", ReloadProbe.value, 2);

        java.nio.file.Files.writeString(ticker, "public class Ticker implements SquidMod {\n    public void init(Squid s) {\n        s.onTick(() -> squid.ReloadProbe.value = 3)\n    }\n}\n");
        java.nio.file.Files.setLastModifiedTime(ticker, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
        reloader.check();
        ReloadProbe.value = 0;
        Events.runTicks();
        check("a mistake keeps the old version running", ReloadProbe.value, 2);

        java.nio.file.Files.writeString(live.resolve("Newcomer.java"), "public class Newcomer implements SquidMod {\n    public void init(Squid s) {\n        s.onTick(() -> squid.ReloadProbe.hookCalls++);\n    }\n}\n");
        reloader.check();
        ReloadProbe.hookCalls = 0;
        Events.runTicks();
        check("a mod added while playing starts right away", ReloadProbe.hookCalls + " " + Main.mods().stream().anyMatch(m -> m.id().equals("newcomer")), "1 true");

        java.nio.file.Files.delete(ticker);
        reloader.check();
        ReloadProbe.value = 0;
        Events.runTicks();
        check("a removed mod stops", ReloadProbe.value + " " + Main.mods().stream().anyMatch(m -> m.id().equals("ticker")), "0 false");

        // A reloaded mod takes over its old hooks in classes that can't be patched again
        ModInfo hooker = new ModInfo("hooker", "Hooker", "1.0", "", List.of(), List.of(), List.of(), "x", live);
        new Squid(hooker).atStart("test.NeverLoaded", "run", call -> ReloadProbe.hookCalls = 1);
        Slots.beginReload("hooker");
        new Squid(hooker).atStart("test.NeverLoaded", "run", call -> ReloadProbe.hookCalls = 2);
        check("nothing needs a restart when every hook is taken over", Slots.endReload("hooker").toString(), "[]");
        ReloadProbe.hookCalls = 0;
        for (int id : Slots.hookIds("hooker")) Hooks.start(id, null, new Object[0]);
        check("the hook runs the new version", ReloadProbe.hookCalls, 2);
        Slots.beginReload("hooker");
        new Squid(hooker).atStart("net.minecraft.client.resources.SplashManager", "brandNewHook", call -> { });
        check("a new hook in a loaded class needs a restart", Slots.endReload("hooker").toString(), "[net.minecraft.client.resources.SplashManager]");

        // The Mods screen's list, and mod settings
        Path modsGame = java.nio.file.Files.createTempDirectory("squid-mods-screen");
        Path modsFolder = java.nio.file.Files.createDirectories(modsGame.resolve("mods"));
        java.nio.file.Files.writeString(modsFolder.resolve("RainbowSheep.java"), "x");
        java.nio.file.Files.createDirectories(modsFolder.resolve("MegaMod/src"));
        java.nio.file.Files.writeString(modsFolder.resolve("MegaMod/squid.json"), "{\"name\": \"Mega Mod\", \"version\": \"2.0\"}");
        jar(modsFolder.resolve("xray.jar.disabled"), "squid.json", "{\"id\": \"xray\", \"name\": \"X-Ray\", \"version\": \"1.0\", \"main\": \"x\"}");
        jar(modsFolder.resolve("sodium.jar"), "fabric.mod.json", "{}");
        List<squidmods.ModFiles.ModFile> listed = squidmods.ModFiles.list(modsFolder);
        check("the Mods screen lists every mod, on or off", listed.stream().map(m -> m.id() + ":" + m.name() + ":" + m.enabled() + ":" + m.fromCode() + ":" + m.squid())
                .toList().toString(), "[mega-mod:Mega Mod:true:true:true, rainbowsheep:Rainbow Sheep:true:true:true, sodium:sodium:true:false:false, xray:X-Ray:false:false:true]");
        squidmods.ModFiles.ModFile xray = listed.get(3);
        squidmods.ModFiles.toggle(xray);
        check("a mod can be turned on from the Mods screen", java.nio.file.Files.exists(modsFolder.resolve("xray.jar")), true);

        Main.setGameFolder(modsGame);
        squid.api.ModSettings settings = squid.api.ModSettings.of("settings-test");
        check("settings start at their defaults", settings.toggle("Show map", true) + " " + settings.number("Zoom", 4, 1, 10) + " "
                + settings.choice("Corner", "Top left", "Top left", "Top right"), "true 4 Top left");
        settings.set("Zoom", 9);
        settings.set("Show map", false);
        settings.set("Corner", "Top right");
        check("changed settings are used right away", settings.toggle("Show map", true) + " " + settings.number("Zoom", 4, 1, 10) + " "
                + settings.choice("Corner", "Top left", "Top left", "Top right"), "false 9 Top right");
        settings.set("Zoom", 99);
        check("numbers stay between their limits", settings.number("Zoom", 4, 1, 10), 10);
        check("settings are saved in the instance's config folder", java.nio.file.Files.exists(modsGame.resolve("config/squid/settings-test.properties")), true);
        check("the Mods screen knows which mods have settings", squid.api.ModSettings.has("settings-test") + " " + squid.api.ModSettings.has("nothing"), "true false");
        Main.setGameFolder(Path.of("."));

        // Panoramas: captured ones are kept with their six pictures, and the one in use and its spin are remembered
        Path kelpHome = java.nio.file.Files.createTempDirectory("squid-pano");
        squidpano.PanoStore panos = new squidpano.PanoStore(kelpHome.resolve("panoramas"));
        Path captured = java.nio.file.Files.createDirectories(kelpHome.resolve("capture/screenshots"));
        for (int side = 0; side < 6; side++) {
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png",
                    captured.resolve("panorama_" + side + ".png").toFile());
        }
        String pano = panos.keep(captured.getParent());
        check("a captured panorama is kept", panos.list().size() + " " + java.nio.file.Files.exists(panos.picture(pano, 5)), "1 true");
        check("Minecraft's own is used until one is picked", String.valueOf(panos.active()), "null");
        panos.setActive(pano);
        panos.setSpeed(9);
        panos.setReversed(true);
        squidpano.PanoStore reopenedPanos = new squidpano.PanoStore(kelpHome.resolve("panoramas"));
        check("the picked one and its spin are remembered", (pano.equals(reopenedPanos.active())) + " " + reopenedPanos.speed() + " " + reopenedPanos.reversed(),
                "true 3.0 true");
        Path kelpTheme = panos.makeKelpTheme(pano);
        check("a panorama can become a Kelp theme", java.nio.file.Files.exists(kelpTheme.resolve("background.png")) + " "
                + java.nio.file.Files.readString(kelpTheme.resolve("theme.properties")).contains("scene=plain"), "true true");
        panos.delete(pano);
        check("deleting the one in use goes back to Minecraft's own", panos.list().size() + " " + panos.active(), "0 null");
        check("spin angles wrap around like Minecraft's", squidpano.PanoStore.wrap(190f) + " " + squidpano.PanoStore.wrap(-200f) + " " + squidpano.PanoStore.wrap(45f),
                "-170.0 160.0 45.0");

        // Clips: the last seconds kept as JPEGs, and written as a Motion JPEG .avi
        squidclips.ClipBuffer clipBuffer = new squidclips.ClipBuffer();
        java.awt.image.BufferedImage clipFrame = squidclips.ClipBuffer.fit(new java.awt.image.BufferedImage(1920, 1080, java.awt.image.BufferedImage.TYPE_INT_RGB), 640, 360);
        byte[] clipJpeg = squidclips.ClipBuffer.jpeg(clipFrame, 0.75f);
        for (int i = 0; i < 100; i++) clipBuffer.add(clipJpeg, i * 50L, 2); // 20 a second, keeping 2 seconds
        check("only the last seconds are kept, at their speed", clipBuffer.pictures().size() + " " + clipBuffer.fps(), "41 20");
        Path clipFile = java.nio.file.Files.createTempDirectory("squid-clip").resolve("clip.avi");
        squidclips.AviWriter.write(clipBuffer.pictures(), 640, 360, clipBuffer.fps(), clipFile);
        byte[] avi = java.nio.file.Files.readAllBytes(clipFile);
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(avi).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        check("a clip is a Motion JPEG .avi with every picture", new String(avi, 0, 4) + " " + new String(avi, 8, 4) + " " + (header.getInt(4) + 8 == avi.length)
                + " " + new String(avi, 0, avi.length, java.nio.charset.StandardCharsets.ISO_8859_1).contains("MJPG")
                + " " + (new String(avi, 0, avi.length, java.nio.charset.StandardCharsets.ISO_8859_1).split("00dc", -1).length - 1), "RIFF AVI  true true 82");

        // Replays: the last few minutes, smooth in slow motion, with blocks put back in order
        squidreplay.Timeline replayTimeline = new squidreplay.Timeline();
        for (int tick = 1; tick <= 30; tick++) {
            squidreplay.Timeline.Thing pig = new squidreplay.Timeline.Thing(7, "pig", null, null, tick, 64, 0, tick == 30 ? 10 : 350, 0, 0, 0,
                    0, 0, 0, 0, 0, null, null);
            replayTimeline.add(new squidreplay.Timeline.Frame(tick, new squidreplay.Timeline.Thing[] {pig}), 20);
            if (tick == 15 || tick == 25) replayTimeline.add(new squidreplay.Timeline.Change(tick, "block at " + tick, "air", "stone"));
            if (tick == 5) replayTimeline.add(new squidreplay.Timeline.Change(tick, "too old", "air", "dirt"));
        }
        replayTimeline.add(new squidreplay.Timeline.Noise(26, "entity.pig.ambient", "NEUTRAL", 1, 1, 0, 64, 0, "LINEAR", false));
        for (int i = 0; i < 500; i++) replayTimeline.add(new squidreplay.Timeline.Spark(27, "smoke", 0, 64, 0, 0, 0.1, 0));
        replayTimeline.add(new squidreplay.Timeline.Noise(28, "block.stone.break", "BLOCKS", 1, 1, 0, 64, 0, "LINEAR", false));
        squidreplay.Timeline.Recording replayRecording = replayTimeline.freeze();
        check("a replay keeps only the last ticks, and the block changes in them", replayRecording.length() + " " + replayRecording.changesBy(Long.MAX_VALUE)
                + " " + replayRecording.tickAt(0), "20 2 11");
        squidreplay.Timeline.Thing halfway = replayRecording.at(18.5).get(0);
        check("between two ticks, things are halfway, turning the short way round", halfway.x + " " + halfway.yRot, "29.5 360.0"); // from 350 to 10 through 360, not back through 180
        check("sounds play once, as the replay moves past them", replayRecording.noisesBetween(25, 26).size() + " " + replayRecording.noisesBetween(26, 30).size()
                + " " + replayRecording.noisesBetween(28, 30).size(), "1 1 0");
        check("a huge burst of particles is capped, so memory stays small", replayRecording.sparksBetween(26, 27).size() + "", "300");
        check("blocks changed by a tick are counted in order", replayRecording.changesBy(14) + " " + replayRecording.changesBy(15) + " "
                + replayRecording.changesBy(24) + " " + replayRecording.changesBy(25), "0 1 1 2");
        check("angles turn the short way", squidreplay.Timeline.angle(10, 350, 0.5f) + " " + squidreplay.Timeline.angle(90, 180, 0.5f), "0.0 135.0");

        // Replay camera paths: smooth through every keyframe, still before the first and after the last
        squidreplay.CameraPath cameraPath = new squidreplay.CameraPath();
        cameraPath.add(new squidreplay.CameraPath.Key(0, 0, 70, 0, 0, 0, 70));
        cameraPath.add(new squidreplay.CameraPath.Key(20, 10, 70, 0, 90, 10, 50));
        cameraPath.add(new squidreplay.CameraPath.Key(40, 10, 80, 10, 180, 0, 70));
        cameraPath.add(new squidreplay.CameraPath.Key(20.2, 10, 70, 0, 90, 10, 50)); // replaces the one at 20
        squidreplay.CameraPath.Key atKey = cameraPath.at(20.2);
        squidreplay.CameraPath.Key between = cameraPath.at(10);
        check("a camera path passes through its keyframes", cameraPath.size() + " " + atKey.x() + " " + atKey.yaw() + " " + cameraPath.at(-5).x() + " " + cameraPath.at(99).z(),
                "3 10.0 90.0 0.0 10.0");
        check("between keyframes the camera is on its way", (between.x() > 0 && between.x() < 10) + " " + (between.fov() > 50 && between.fov() < 70), "true true");
        float[] look = squidreplay.CameraPath.lookAt(0, 0, 0, 0, 0, 5);
        float[] lookDown = squidreplay.CameraPath.lookAt(0, 10, 0, 10, 0, 0);
        check("the camera can look at someone", look[0] + " " + look[1] + " " + lookDown[0] + " " + lookDown[1], "0.0 0.0 -90.0 45.0");

        // Video sound: sounds land at their moment, fade with distance, and come from the side they were on
        short[] beep = new short[4410];
        for (int i = 0; i < beep.length; i++) beep[i] = (short) (8000 * Math.sin(i * 2 * Math.PI * 440 / 44100.0));
        squidclips.AudioMix.Listener ear = seconds -> new double[] {0, 64, 0, 0}; // facing south (+z), so +x is on the left
        short[] mixed = squidclips.AudioMix.mix(java.util.List.of(
                new squidclips.AudioMix.Hit(0.5, beep, 1, 44100, 1, 1, -4, 64, 0, false, true, 16),
                new squidclips.AudioMix.Hit(1.0, beep, 1, 44100, 1, 1, 4, 64, 0, false, true, 16),
                new squidclips.AudioMix.Hit(1.5, beep, 1, 44100, 1, 1, 40, 64, 0, false, true, 16)), ear, 2);
        long[] loud = new long[8];
        for (int part = 0; part < 4; part++) {
            for (int i = part * 22050 + 1000; i < part * 22050 + 3000 && i < mixed.length / 2; i++) { // just after each half second
                loud[part * 2] += Math.abs(mixed[i * 2]);
                loud[part * 2 + 1] += Math.abs(mixed[i * 2 + 1]);
            }
        }
        check("before the first sound it's quiet; a sound on the right is in the right ear; on the left, the left ear; too far away, silent",
                (loud[0] + loud[1] == 0) + " " + (loud[3] > 0 && loud[2] == 0) + " " + (loud[4] > 0 && loud[5] == 0) + " " + (loud[6] + loud[7] == 0),
                "true true true true");
        java.nio.file.Path soundClip = java.nio.file.Files.createTempDirectory("squid-sound").resolve("clip.avi");
        squidclips.AviWriter.write(java.util.List.of(clipJpeg, clipJpeg, clipJpeg), 640, 360, 30, mixed, squidclips.AudioMix.RATE, soundClip);
        String soundAvi = new String(java.nio.file.Files.readAllBytes(soundClip), java.nio.charset.StandardCharsets.ISO_8859_1);
        check("a clip with sound has a sound stream, with the sound split between the pictures",
                soundAvi.contains("auds") + " " + (soundAvi.split("01wb", -1).length - 1), "true 6");

        // Squid's own sound decoders: FLAC comes out exactly like the WAV it was made from
        squid.audio.Pcm toneWav = squid.audio.Audio.decode(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("test/audio/tone.wav")));
        squid.audio.Pcm toneFlac = squid.audio.Audio.decode(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("test/audio/tone.flac")));
        check("a FLAC file decodes to exactly the sound it was made from", toneFlac.channels() + " " + toneFlac.rate() + " "
                + java.util.Arrays.equals(toneWav.samples(), toneFlac.samples()) + " " + toneWav.samples().length, "2 44100 true 22050");
        squid.audio.Pcm toneMp3 = squid.audio.Audio.decode(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("test/audio/tone.mp3")));
        long mp3Error = 0;
        long mp3Signal = 0;
        for (int i = 0; i < toneWav.samples().length && i < toneMp3.samples().length; i++) {
            long d = toneMp3.samples()[i] - toneWav.samples()[i];
            mp3Error += d * d;
            mp3Signal += (long) toneWav.samples()[i] * toneWav.samples()[i];
        }
        check("an MP3 decodes to the sound it was made from (the padding trimmed, so it lines up), close enough to hear no difference",
                toneMp3.channels() + " " + toneMp3.rate() + " " + toneMp3.samples().length + " " + (10 * Math.log10((double) mp3Signal / mp3Error) > 20),
                "2 44100 22050 true");
        check("something that isn't sound is turned away", squid.audio.Audio.canDecode("hello".getBytes()) + "", "false");

        // Languages: Squid follows Minecraft's language, and every file has every text with the same {0}s
        check("Minecraft's language variants share files", Lang.fileFor("en_gb") + " " + Lang.fileFor("es_ar") + " " + Lang.fileFor("fr_ca")
                + " " + Lang.fileFor("en_pt") + " " + Lang.fileFor("ja_jp"), "en_us es_mx fr_fr en_pt ja_jp");
        Lang.force("es_es");
        check("Squid speaks Minecraft's language", Lang.t("Store") + " | " + Lang.t("Restart the game to start {0}.", "X-Ray"),
                "Tienda | Reinicia el juego para iniciar X-Ray.");
        check("a text no file has stays English", Lang.t("Not a Squid text"), "Not a Squid text");
        Lang.force("en_us");
        check("English is English", Lang.t("Store"), "Store");
        Path langFolder = Path.of("lang");
        java.util.Set<String> englishTexts = Lang.parse(java.nio.file.Files.readString(langFolder.resolve("es_es.txt"))).keySet();
        List<String> languageProblems = new ArrayList<>();
        int languages = 0;
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(langFolder)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".txt")).sorted().toList()) {
                languages++;
                String fileText = java.nio.file.Files.readString(file);
                if (!fileText.contains("BETA, not checked")) languageProblems.add(file.getFileName() + " isn't marked BETA");
                java.util.Map<String, String> table = Lang.parse(fileText);
                for (String english : englishTexts) {
                    String translated = table.get(english);
                    if (translated == null) languageProblems.add(file.getFileName() + " is missing: " + english);
                    else if (!placeholders(english).equals(placeholders(translated))) languageProblems.add(file.getFileName() + " changes the {0}s in: " + english);
                }
            }
        }
        check("all " + languages + " languages have every text, marked BETA", languageProblems, List.of());

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The {0}, {1}... in a text, sorted. */
    static List<String> placeholders(String text) {
        return java.util.regex.Pattern.compile("\\{\\d}").matcher(text).results().map(r -> r.group()).sorted().toList();
    }

    /** A jar with one text file in it. */
    static void jar(Path file, String entry, String text) throws java.io.IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entry));
            zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
