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
        // Every EasyMod command compiles in a mod, and the ones that look at the world are safe before you're in one
        Path everything = java.nio.file.Files.createTempDirectory("squid-all-commands").resolve("AllCommands.java");
        java.nio.file.Files.writeString(everything, String.join("\n",
                "public class AllCommands extends EasyMod {",
                "    void start() {",
                "        onKey(\"R\", () -> boost(1.2));",
                "        onKey(\"F\", () -> dash(2));",
                "        onHurt(() -> particles(\"angry_villager\", 5));",
                "        onDeath(() -> title(\"Oops!\", \"Try again\"));",
                "        onBeat(() -> particles(\"note\", 2));",
                "        onCommand(\"dance\", () -> particles(\"note\", 10));",
                "        onCommand(\"shout\", words -> title(words));",
                "        onChat(text -> { if (text.contains(\"hello\")) say(\"Hi back!\"); });",
                "        onBreak(block -> { if (block.contains(\"diamond\")) remember(\"diamonds\", remembered(\"diamonds\", 0) + 1); });",
                "        onAttack(mob -> { if (mob.equals(\"zombie\")) particles(\"crit\", 5); });",
                "        onKey(\"G\", () -> after(1.5, () -> title(\"Boom!\")));",
                "        onPickup(item -> { if (item.equals(\"diamond\")) say(\"Shiny!\"); });",
                "        onLevelUp(level -> title(\"Level \" + level + \"!\"));",
                "        onNight(() -> say(\"Night!\"));",
                "        onDay(() -> say(\"Morning! You're level \" + level()));",
                "        onKey(\"K\", () -> { glow(\"creeper\"); after(10, () -> stopGlowing(\"creeper\")); });",
                "        keepShowing(() -> \"Diamonds: \" + remembered(\"diamonds\", 0));",
                "        every(1, () -> {",
                "            if (nearby(\"creeper\", 16) > 0) title(\"Creeper!\");",
                "            showText(holding() + \" / \" + lookingAt() + \" / \" + biome() + \" / \" + (isNight() ? \"night\" : \"day\"));",
                "            particles(\"heart\", 3);",
                "            if (musicLevel() > 0.5) showText(nowPlaying());",
                "        });",
                "    }",
                "}", ""));
        ModInfo allCommands = sources.compile(everything);
        check("every EasyMod command compiles in a mod", allCommands.id(), "all-commands");
        // ...and starts, with Squid's shared hooks (chat, commands, breaking...) in Minecraft's classes, which must still load
        squid.api.GameEvents.install();
        SquidClassLoader chatLoader = new SquidClassLoader(urls.toArray(URL[]::new));
        Main.setGameLoader(chatLoader);
        ((SquidMod) new ModClassLoader(allCommands.jar(), chatLoader).loadClass("AllCommands").getDeclaredConstructor().newInstance())
                .init(new Squid(allCommands));
        check("chat commands and onChat hook into Minecraft's chat classes", Class.forName("net.minecraft.client.multiplayer.ClientPacketListener", false, chatLoader).getClassLoader() == chatLoader
                && Class.forName("net.minecraft.client.gui.components.ChatComponent", false, chatLoader).getClassLoader() == chatLoader, true);
        check("onBreak hooks into breaking blocks", Class.forName("net.minecraft.client.multiplayer.MultiPlayerGameMode", false, chatLoader).getClassLoader() == chatLoader, true);
        // The Store's easy mods (Coords, Where I Died) start like any mod
        List<String> storeStarted = new ArrayList<>();
        for (String storeMod : new String[] {"coords", "whereidied"}) {
            Path storeJar = Path.of("build", storeMod + ".jar");
            ModInfo storeInfo = Mods.describe(storeJar);
            ((SquidMod) new ModClassLoader(storeJar, chatLoader).loadClass(storeInfo.main()).getDeclaredConstructor().newInstance()).init(new Squid(storeInfo));
            storeStarted.add(storeInfo.id());
        }
        check("the Store's easy mods start", storeStarted.toString(), "[coords, whereidied]");
        // Shared events reach every mod listening, and every mod with a command runs, even two with the same one
        List<String> eventsHeard = new ArrayList<>();
        Events.on("attack", "event-test-a", mob -> eventsHeard.add("a:" + mob));
        Events.on("attack", "event-test-b", mob -> eventsHeard.add("b:" + mob));
        Events.onCommand("event-test-a", "Jig", words -> eventsHeard.add("a!" + words));
        Events.onCommand("event-test-b", "!jig", words -> eventsHeard.add("b!" + words));
        Events.fire("attack", "zombie");
        boolean danced = Events.command("  !JIG  all night ");
        boolean said = Events.command("hello !jig");
        check("shared events reach every mod, and both mods' !jig run", eventsHeard + " " + danced + " " + said,
                "[a:zombie, b:zombie, a!all night, b!all night] true false");
        Events.glow("event-test-a", "creeper", true);
        check("a mod can outline a kind of mob", Events.anyGlowing() + " " + Events.glowing("creeper") + " " + Events.glowing("pig"), "true true false");
        Events.remove("event-test-a");
        Events.remove("event-test-b");
        Events.fire("attack", "skeleton");
        check("a mod that's turned off stops hearing them, and its outlines go", eventsHeard.size() + " " + Events.command("!jig") + " " + Events.glowing("creeper"), "4 false false");


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
        // What a Mac leaves next to each file on a USB stick: not code, and it mustn't stop the mod
        java.nio.file.Files.write(mega.resolve("src/._MegaMod.java"), new byte[] {0, 5, 22, 7, 0, 2, 0, 0, 'M', 'a', 'c'});
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
                    {"src/parts/._Greeting.java", " Mac"},
            };
            for (String[] entry : packed) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry[0]));
                zip.write(entry[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        SourceMods projectSources = new SourceMods(projects.resolve(".squid-cache"), System.getProperty("java.class.path") + File.pathSeparator + a[0]);
        Mods.Found projectFound = Mods.find(projects, "26.3", projectSources);
        check("a project folder and a .squid file are mods (a Mac's ._ files beside the code are skipped)", projectFound.mods().stream()
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

        // Mod files that used to stop the whole game: each one only skips itself now
        Path odd = java.nio.file.Files.createTempDirectory("squid-odd-test");
        String hi = "public class %s extends EasyMod {\n    void start() {\n    }\n}\n";
        String[][] oddProjects = {
                {"OneAuthor", "{\"authors\": \"Sam\", \"depends\": \"\", \"minecraft\": 26.3, \"version\": 2}"},
                {"JustAList", "[]"},
                {"Nothing", "null"},
                {"NeedsItself", "{\"depends\": [\"needs-itself\"]}"},
                {"LoopA", "{\"depends\": [\"loop-b\"]}"},
                {"LoopB", "{\"depends\": [\"loop-a\"]}"},
                {"AfterLoop", "{\"depends\": [\"loop-a\"]}"},
                {"Spaced", "{}"},
                {"Squid", "{}"},
                {"WithBom", "﻿{\"name\": \"With Bom\", \"author\": \"x\"}"},
                {"OldVersion", "{\"id\": \"twin\", \"version\": \"1.0\", \"main\": \"OldVersion\"}"},
                {"NewVersion", "{\"id\": \"twin\", \"version\": \"1.10\", \"main\": \"NewVersion\"}"},
                {"FutureMinecraft", "{\"minecraft\": \">=26.2\"}"},
        };
        for (String[] p : oddProjects) {
            Path oddFolder = odd.resolve(p[0]);
            java.nio.file.Files.createDirectories(oddFolder.resolve("src"));
            java.nio.file.Files.writeString(oddFolder.resolve("squid.json"), p[1]);
            java.nio.file.Files.writeString(oddFolder.resolve("src/" + p[0] + ".java"), hi.formatted(p[0]));
        }
        java.nio.file.Files.writeString(odd.resolve("Spaced/src/My Helper.java"), "class MyHelper {\n}\n");
        // Windows-1252 text (é saved by an old editor), not UTF-8
        java.nio.file.Files.write(odd.resolve("Accent.java"), ("public class Accent extends EasyMod {\n    void start() {\n        say(\"café\");\n    }\n}\n")
                .getBytes(java.nio.charset.Charset.forName("windows-1252")));
        // Zipped by hand: the whole folder inside, with Windows' \ between folders
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(odd.resolve("HandMade.squid")))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("HandMade\\squid.json"));
            zip.write("{\"name\": \"Hand Made\"}".getBytes());
            zip.putNextEntry(new java.util.zip.ZipEntry("HandMade\\src\\HandMade.java"));
            zip.write(hi.formatted("HandMade").getBytes());
            zip.closeEntry();
        }
        SourceMods oddSources = new SourceMods(odd.resolve(".squid-cache"), System.getProperty("java.class.path") + File.pathSeparator + a[0]);
        Mods.Found oddFound = Mods.find(odd, "26.3", oddSources);
        check("odd but fine squid.json values and files still load", oddFound.mods().stream()
                .map(m -> m.id() + " " + m.version() + " " + m.authors()).sorted().toList().toString(),
                "[accent 1.0 [], future-minecraft 1.0 [], hand-made 1.0 [], needs-itself 1.0 [], one-author 2 [Sam], spaced 1.0 [], twin 1.10 [], with-bom 1.0 []]");
        java.util.Map<String, String> oddSkipped = new java.util.TreeMap<>();
        for (Mods.Skipped s : oddFound.skipped()) oddSkipped.put(s.name(), s.reason());
        check("a squid.json that isn't { } only skips that mod", oddSkipped.get("JustAList") + " | " + oddSkipped.get("Nothing"),
                "its squid.json is broken: it has to start with { and end with } | its squid.json is broken: it has to start with { and end with }");
        check("mods that need each other in a loop are skipped, and so is a mod that needs them",
                oddSkipped.get("Loop A") + " | " + oddSkipped.get("After Loop"),
                "These mods need each other in a loop, so none of them can start first: loop-a -> loop-b -> loop-a | it needs Loop A, which was skipped too.");
        check("a mod can't take Squid's own id", oddSkipped.get("Squid"), "its id \"squid\" belongs to Squid itself. Give the mod another name.");
        // Every built-in part's id is kept for Squid, so a new part can't be forgotten in the list (a mod could take its id)
        List<String> unreserved = new ArrayList<>();
        try (java.util.stream.Stream<Path> parts = java.nio.file.Files.list(Path.of("builtin"))) {
            for (Path part : parts.filter(p -> java.nio.file.Files.exists(p.resolve("squid.json"))).sorted().toList()) {
                String partId = String.valueOf(Json.object(Json.parse(SourceMods.text(java.nio.file.Files.readAllBytes(part.resolve("squid.json"))))).get("id"));
                if (!Mods.reserved(partId)) unreserved.add(partId);
            }
        }
        check("every built-in part's id is kept for Squid", unreserved.toString(), "[]");
        check("of two copies, the newer version wins", oddSkipped.get("Old Version"), "it's another copy of NewVersion. You can delete OldVersion.");
        check("versions compare part by part: 1.10 after 1.9, 1.0 the same as 1.0.0, a beta before its release",
                Mods.compareVersions("1.10", "1.9") + " " + Mods.compareVersions("1.0", "1.0.0") + " " + Mods.compareVersions("1.0", "1.0.0-beta")
                        + " " + Mods.compareVersions("2.0-beta", "2.0"), "1 0 1 -1");
        check("squid.json keys that look like typos get a \"did you mean\"", Mods.unknownKeys(Json.object(Json.parse("{\"author\": 1, \"Name\": 2, \"color\": 3}"))).toString(),
                "[squid.json has \"author\". Did you mean \"authors\"?, squid.json has \"Name\". Did you mean \"name\"?, squid.json has \"color\", which Squid doesn't use.]");
        check("\">=26.2\" works on 26.3 and 26.10 but not 26.1", new ModInfo("x", "x", "1", "", List.of(), List.of(), List.of(">=26.2"), "x", odd).worksOn("26.3")
                + " " + new ModInfo("x", "x", "1", "", List.of(), List.of(), List.of(">=26.2"), "x", odd).worksOn("26.10")
                + " " + new ModInfo("x", "x", "1", "", List.of(), List.of(), List.of(">=26.2"), "x", odd).worksOn("26.1"), "true true false");
        long oddBuilds;
        try (java.util.stream.Stream<Path> cached = java.nio.file.Files.list(odd.resolve(".squid-cache"))) {
            oddBuilds = cached.filter(p -> p.getFileName().toString().startsWith("twin-")).count();
        }
        check("only the winning copy is built", oddBuilds, 1L);
        // Squid's own folder: its parts have Squid's own ids, and they load (only the mods folder keeps those ids for Squid)
        Path ownParts = java.nio.file.Files.createTempDirectory("squid-own-parts");
        modJar(ownParts.resolve("store.jar"), "{\"id\": \"squid-store\", \"name\": \"Squid Store\", \"version\": \"1.0\", \"main\": \"x\"}");
        check("Squid's built-in parts load from its own folder", Mods.find(ownParts, "26.3").mods().stream().map(ModInfo::id).toList().toString(), "[squid-store]");
        // A broken copy can't hide a working copy of the same mod
        Path copies = java.nio.file.Files.createTempDirectory("squid-copies");
        java.nio.file.Files.createDirectories(copies.resolve("Twin/src"));
        java.nio.file.Files.writeString(copies.resolve("Twin/squid.json"), "{}");
        java.nio.file.Files.writeString(copies.resolve("Twin/src/Twin.java"), "public class Twin extends EasyMod {\n    void start() {\n        int x = \n    }\n}\n");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(copies.resolve("Twin.squid")))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("squid.json"));
            zip.write("{}".getBytes());
            zip.putNextEntry(new java.util.zip.ZipEntry("src/Twin.java"));
            zip.write(hi.formatted("Twin").getBytes());
            zip.closeEntry();
        }
        Mods.Found twins = Mods.find(copies, "26.3", new SourceMods(copies.resolve(".squid-cache"), System.getProperty("java.class.path") + File.pathSeparator + a[0]));
        check("when your own copy has a mistake, the working download of the same mod loads instead",
                twins.mods().stream().map(m -> m.id()).toList() + " " + twins.skipped().size(), "[twin] 1");

        // A change makes a new build; the old one stays while the game runs, and goes at the next start
        java.nio.file.Files.writeString(odd.resolve("Accent.java"), hi.formatted("Accent") + "\n");
        oddSources.compile(odd.resolve("Accent.java"));
        long accentBuilds;
        try (java.util.stream.Stream<Path> cached = java.nio.file.Files.list(odd.resolve(".squid-cache"))) {
            accentBuilds = cached.filter(p -> p.getFileName().toString().matches("accent-[0-9a-f]{12}")).count();
        }
        Mods.find(odd, "26.3", new SourceMods(odd.resolve(".squid-cache"), System.getProperty("java.class.path") + File.pathSeparator + a[0]));
        long accentAfter;
        try (java.util.stream.Stream<Path> cached = java.nio.file.Files.list(odd.resolve(".squid-cache"))) {
            accentAfter = cached.filter(p -> p.getFileName().toString().matches("accent-[0-9a-f]{12}")).count();
        }
        check("old builds stay while running, and are cleaned up at the next start", accentBuilds + " " + accentAfter, "2 1");

        // The Store: its list, safe file names, installing with a fingerprint check, and its Minecraft parts loading
        String storeList = "{\"items\": ["
                + "{\"id\": \"xray\", \"type\": \"mod\", \"name\": \"X-Ray\", \"author\": \"Samuel\", \"minecraft\": \"26.3.x\", \"devPicked\": true,"
                + " \"file\": \"xray-1.0.0.jar\", \"url\": \"https://example.com/x.jar\", \"sha256\": \"ABC\", \"size\": 5},"
                + "{\"id\": \"sneaky\", \"type\": \"mod\", \"name\": \"Sneaky\", \"file\": \"../../evil.jar\", \"url\": \"u\", \"sha256\": \"a\"},"
                + "{\"id\": \"packed\", \"type\": \"resourcepack\", \"name\": \"Packed\", \"file\": \"wrong.jar\", \"url\": \"u\", \"sha256\": \"a\"},"
                + "{\"id\": \"old\", \"type\": \"resourcepack\", \"name\": \"Old Pack\", \"minecraft\": [\"1.20.x\"], \"file\": \"old.zip\", \"url\": \"u\", \"sha256\": \"a\"}]}";
        List<squidstore.Catalog.Item> storeItems = squidstore.Catalog.parse(storeList);
        check("the store list keeps safe items only", storeItems.stream().map(squidstore.Catalog.Item::id).toList().toString(), "[xray, old]");
        check("a store item's icon is an https link (or nothing)", squidstore.Catalog.parse("{\"items\": [{\"id\": \"a\", \"type\": \"mod\", \"name\": \"A\","
                + " \"file\": \"a.jar\", \"url\": \"u\", \"sha256\": \"a\", \"icon\": \"https://example.com/a.png\"},"
                + " {\"id\": \"b\", \"type\": \"mod\", \"name\": \"B\", \"file\": \"b.jar\", \"url\": \"u\", \"sha256\": \"a\", \"icon\": \"http://x/b.png\"}]}")
                .stream().map(squidstore.Catalog.Item::icon).toList().toString(), "[https://example.com/a.png, ]");
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
                hosted.resolve("fun.jar").toUri().toString(), goodHash, modBytes.length, "1.0", "");
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
                "swapped.zip", hosted.resolve("fun.jar").toUri().toString(), "0".repeat(64), -1, "", "");
        String swapProblem;
        try {
            squidstore.Installer.install(swapped, storeGame);
            swapProblem = "installed anyway";
        } catch (java.io.IOException e) {
            swapProblem = e.getMessage();
        }
        check("a file that doesn't match its fingerprint is refused", swapProblem + " | left behind: "
                + java.nio.file.Files.exists(storeGame.resolve("resourcepacks/swapped.zip")), "it arrived damaged, so it wasn't installed | left behind: false");
        // A .squid zipped by hand (its folder inside, Windows' \, a BOM, no id: Squid gets it from the file name) is
        // the same mod to the Store as it is to Squid
        jar(storeGame.resolve("mods/Dice.squid"), "Dice\\squid.json", "﻿{\"name\": \"Dice\", \"version\": \"0.5\"}");
        squidstore.Catalog.Item dice = new squidstore.Catalog.Item("dice", "mod", "Dice", "Sam", "", List.of(), false, "dice-1.0.squid",
                hosted.resolve("fun.jar").toUri().toString(), goodHash, modBytes.length, "1.0", "");
        check("the Store knows a hand-zipped .squid of a mod", squidstore.Installer.installed(dice, storeGame) + " "
                + squidstore.Installer.updateAvailable(dice, storeGame), "true true");

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
        java.lang.reflect.Method milestones = countLoader.loadClass("squidcount.SquidCount").getDeclaredMethod("milestoneBetween", int.class, int.class);
        milestones.setAccessible(true);
        check("Squid Count milestones: 50, 100, 250, 500, 1000, then every 500 (the biggest one passed counts)",
                milestones.invoke(null, 40, 60) + " " + milestones.invoke(null, 90, 260) + " " + milestones.invoke(null, 990, 1010) + " "
                        + milestones.invoke(null, 1400, 1550) + " " + milestones.invoke(null, 60, 90), "50 250 1000 1500 0");

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
        // Squid comes with a slot for every vanilla cape, as links only (no pictures), so they're there without internet
        List<squidskins.OfficialCapes.Cape> slots = squidskins.OfficialCapes.bundled();
        check("Squid comes with a slot for every official cape", slots.size() + " " + slots.stream().map(squidskins.OfficialCapes.Cape::id)
                .filter(List.of("migrator", "minecon-2011", "mojang", "classic-mojang", "twisted", "aurora", "xbox", "4j-studios")::contains).count()
                + " " + slots.stream().filter(squidskins.OfficialCapes.Cape::fromMojang).count(), "137 8 49");
        // Capes Mojang's Java server doesn't have come from the wiki, only with a fingerprint of their pixels
        List<squidskins.OfficialCapes.Cape> wikiCapes = squidskins.OfficialCapes.parse("{\"capes\": ["
                + "{\"id\": \"a\", \"name\": \"A\", \"texture\": \"https://minecraft.wiki/images/A.png\", \"pixels\": \"0123456789abcdef0123456789abcdef01234567\"},"
                + "{\"id\": \"b\", \"name\": \"B\", \"texture\": \"https://minecraft.wiki/images/B.png\"},"
                + "{\"id\": \"c\", \"name\": \"C\", \"texture\": \"https://example.com/images/C.png\", \"pixels\": \"0123456789abcdef0123456789abcdef01234567\"}]}");
        check("wiki capes need a fingerprint, and only the wiki counts", wikiCapes.stream().map(c -> c.name() + " " + c.fromMojang()).toList().toString(), "[A false]");
        java.awt.image.BufferedImage twoPixels = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        twoPixels.setRGB(0, 0, 0xFF102030);
        twoPixels.setRGB(1, 0, 0x00FFFFFF); // see-through: its colour doesn't count
        java.lang.reflect.Method pixelsOf = squidskins.OfficialCapes.class.getDeclaredMethod("pixels", java.awt.image.BufferedImage.class);
        pixelsOf.setAccessible(true);
        check("a picture's pixel fingerprint (the same one the cape list was made with)", pixelsOf.invoke(null, twoPixels), "8f3be8e6dc6a8ee0a97006fff621db2e4a075ae4");
        check("each slot is in a group, and none is in twice", slots.stream().filter(c -> c.group().isEmpty()).count() + " "
                + (slots.size() - slots.stream().map(squidskins.OfficialCapes.Cape::hash).distinct().count()), "0 0");
        check("an official cape on the list is found by its choice", String.valueOf(squidskins.OfficialCapes.named(slots.get(0).choice())), slots.get(0).toString());
        check("Squid has no official cape pictures in it", String.valueOf(squidskins.OfficialCapes.class.getResource("/squidskins/official-capes")), "null");
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
        check("the Mod Maker hears that it reloaded", squid.LiveReload.last(ticker).worked() + " " + squid.LiveReload.last(ticker).message().startsWith("Reloaded"), "true true");

        java.nio.file.Files.writeString(ticker, "public class Ticker implements SquidMod {\n    public void init(Squid s) {\n        s.onTick(() -> squid.ReloadProbe.value = 3)\n    }\n}\n");
        java.nio.file.Files.setLastModifiedTime(ticker, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
        reloader.check();
        ReloadProbe.value = 0;
        Events.runTicks();
        check("a mistake keeps the old version running", ReloadProbe.value, 2);
        check("the Mod Maker hears about the mistake and its line", !squid.LiveReload.last(ticker).worked() + " " + squid.LiveReload.last(ticker).message().contains("line 3"), "true true");

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

        // A second copy of a running mod waits; when the running copy goes (an update dropped in before the old file
        // was turned off), the waiting copy takes over instead of the mod stopping until the game restarts
        Path copyA = project(live, "CopyA", "{\"id\": \"twin\", \"main\": \"Twin\"}", "Twin", "s.onTick(() -> squid.ReloadProbe.value = 5);");
        reloader.check();
        Path copyB = project(live, "CopyB", "{\"id\": \"twin\", \"main\": \"Twin\"}", "Twin", "s.onTick(() -> squid.ReloadProbe.value = 6);");
        reloader.check();
        ReloadProbe.value = 0;
        Events.runTicks();
        int whileBoth = ReloadProbe.value;
        deleteTree(copyA);
        reloader.check();
        ReloadProbe.value = 0;
        Events.runTicks();
        check("a waiting copy of a mod takes over when the running one is removed", whileBoth + " " + ReloadProbe.value + " "
                + Main.mods().stream().anyMatch(m -> m.id().equals("twin")), "5 6 true");
        // Giving a running mod another id in its squid.json: the mod it used to be stops
        java.nio.file.Files.writeString(copyB.resolve("squid.json"), "{\"id\": \"twin-two\", \"main\": \"Twin\"}");
        java.nio.file.Files.setLastModifiedTime(copyB.resolve("squid.json"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        reloader.check();
        check("changing a running mod's id stops the old one", Main.mods().stream().map(ModInfo::id).filter(id -> id.startsWith("twin")).toList().toString(), "[twin-two]");
        deleteTree(copyB);
        reloader.check();
        check("and removing it stops the new one", Main.mods().stream().anyMatch(m -> m.id().startsWith("twin")), false);
        // A project folder named only in another alphabet asks for an id, instead of saying the id it made up is wrong
        Path alphabet = java.nio.file.Files.createDirectories(live.resolve("日本語"));
        java.nio.file.Files.writeString(alphabet.resolve("squid.json"), "{\"name\": \"Nihongo\"}");
        check("a folder named in another alphabet asks for an id", failure(() -> Mods.describe(alphabet)),
                "IOException: 日本語: squid.json needs a \"id\"");
        deleteTree(alphabet);
        // A squid.json that unpacks to more than any real one (a made-up file could unpack to gigabytes) is refused
        // before it's all read, so it can't take the game's memory as it starts
        Path bomb = java.nio.file.Files.createTempDirectory("squid-json-bomb").resolve("Bomb.squid");
        jar(bomb, "squid.json", "{\"name\": \"Bomb\"" + " ".repeat(2 << 20) + "}");
        check("a squid.json far too big is refused without reading it all", failure(() -> Mods.describe(bomb)) + " | "
                + squidmods.ModFiles.list(bomb.getParent()).size(), "IOException: its squid.json is far too big. | 1");
        // .squid files cut short or with bytes flipped: each loads or is skipped with words a player can act on, and
        // never stops the game
        java.io.ByteArrayOutputStream fuzzBytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(fuzzBytes)) {
            for (String[] entry : new String[][] {{"squid.json", "{\"id\": \"fuzz\", \"name\": \"Fuzz\", \"main\": \"Fuzz\"}"},
                    {"src/Fuzz.java", "public class Fuzz extends EasyMod { void start() { say(\"hi\"); } }"}, {"resources/a.txt", "hello"}}) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry[0]));
                zip.write(entry[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        byte[] goodSquid = fuzzBytes.toByteArray();
        Path fuzzRoot = java.nio.file.Files.createTempDirectory("squid-fuzz");
        SourceMods fuzzSources = new SourceMods(fuzzRoot.resolve(".squid-cache"), System.getProperty("java.class.path") + File.pathSeparator + a[0]);
        java.util.Random squidFlips = new java.util.Random(5);
        java.util.Set<String> unclear = new java.util.TreeSet<>();
        for (int i = 0; i < 120; i++) {
            byte[] damaged = goodSquid.clone();
            if (i % 3 == 0) damaged = java.util.Arrays.copyOf(damaged, squidFlips.nextInt(damaged.length));
            else for (int k = 0; k <= squidFlips.nextInt(4); k++) damaged[squidFlips.nextInt(damaged.length)] ^= (byte) (1 << squidFlips.nextInt(8));
            Path fuzzFolder = java.nio.file.Files.createDirectories(fuzzRoot.resolve("m" + i));
            java.nio.file.Files.write(fuzzFolder.resolve("Fuzz.squid"), damaged);
            try {
                for (Mods.Skipped skippedFuzz : Mods.find(fuzzFolder, "26.3", fuzzSources).skipped()) {
                    String reason = skippedFuzz.reason();
                    if (reason.startsWith("Squid couldn't read it") || reason.contains("ZLIB")) unclear.add(reason);
                }
            } catch (Throwable e) {
                unclear.add("stopped: " + e);
            }
        }
        check("120 damaged .squid files each load or are skipped with a clear reason", unclear.toString(), "[]");

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
                .toList().toString(), "[mega-mod:Mega Mod:true:true:true, rainbow-sheep:Rainbow Sheep:true:true:true, sodium:sodium:true:false:false, xray:X-Ray:false:false:true]");
        squidmods.ModFiles.ModFile xray = listed.get(3);
        squidmods.ModFiles.toggle(xray);
        check("a mod can be turned on from the Mods screen", java.nio.file.Files.exists(modsFolder.resolve("xray.jar")), true);

        // The Mod Maker: a new easy mod from a name, ready to run as it is, and never over one that's there
        check("the Mod Maker turns names into class names", squidmods.ModMaker.className("rocket boots!") + " " + squidmods.ModMaker.className("3 cool")
                + " " + squidmods.ModMaker.className("!!"), "RocketBoots My3Cool MyMod");
        Path makerMods = java.nio.file.Files.createTempDirectory("squid-mod-maker");
        Path makerMade = squidmods.ModMaker.create(makerMods, "Hello Maker");
        check("a mod made in the Mod Maker compiles as it is", sources.compile(makerMade).id(), "hello-maker");
        java.nio.file.Files.writeString(makerMade, "// mine\r\n\tpublic class HelloMaker {}");
        check("making one that's there opens it instead", squidmods.ModMaker.create(makerMods, "hello maker") + " " + squidmods.ModMaker.read(makerMade),
                makerMade + " // mine\n    public class HelloMaker {}");
        check("the Mod Maker lists easy mods", squidmods.ModMaker.easyMods(makerMods).size(), 1);
        Path oddMaker = java.nio.file.Files.createTempDirectory("squid-mod-maker-odd");
        check("names Java uses get My in front, and quotes and backslashes in a name can't break the code",
                squidmods.ModMaker.className("Easy Mod") + " " + (sources.compile(squidmods.ModMaker.create(oddMaker, "Oops \\u0022 \"hi\" \\")).id() != null),
                "MyEasyMod true");
        // What's New shows once per update
        Path newsFile = java.nio.file.Files.createTempDirectory("squid-news").resolve("squid-whats-new.txt");
        boolean newsBefore = squidmods.WhatsNew.seen(newsFile);
        squidmods.WhatsNew.markSeen(newsFile);
        check("What's New shows once per update", newsBefore + " " + squidmods.WhatsNew.seen(newsFile), "false true");
        // Every starter mod the Mod Maker can start from compiles (they're the same as Kelp's)
        Path startersFolder = java.nio.file.Files.createTempDirectory("squid-maker-starters");
        List<String> startersBuilt = new ArrayList<>();
        for (squidmods.ModStarters.Starter starterMod : squidmods.ModStarters.ALL) {
            Path starterMade = squidmods.ModMaker.create(startersFolder, "My " + starterMod.name(), starterMod);
            startersBuilt.add(sources.compile(starterMade).id());
        }
        check("every Mod Maker starter compiles", startersBuilt.size() + " " + startersBuilt.contains("my-rocket-boots"), squidmods.ModStarters.ALL.size() + " true");
        // Every line in the Mod Maker's Commands list works, all together in one mod
        StringBuilder allSnippets = new StringBuilder("public class AllSnippets extends EasyMod {\n    void start() {\n");
        for (String[] snippet : squidmods.ModMaker.SNIPPETS) allSnippets.append("        ").append(snippet[1]).append('\n');
        allSnippets.append("    }\n}\n");
        Path snippetsFile = java.nio.file.Files.createTempDirectory("squid-snippets").resolve("AllSnippets.java");
        java.nio.file.Files.writeString(snippetsFile, allSnippets);
        check("every command in the Mod Maker's list compiles", sources.compile(snippetsFile).id() + " " + squidmods.ModMaker.SNIPPETS.size(), "all-snippets 29");

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
        // remember() keeps a number for next time, without it showing up as a setting
        check("a number not remembered yet is the starting one", settings.remembered("diamonds", 0), 0);
        settings.remember("diamonds", 12);
        boolean writtenAtOnce = java.nio.file.Files.readString(modsGame.resolve("config/squid/settings-test.properties")).contains("remember.diamonds=12");
        // written a moment later, in the background (waited for up to 10 seconds, in case the computer is busy)
        for (long until = System.currentTimeMillis() + 10_000; System.currentTimeMillis() < until
                && !java.nio.file.Files.readString(modsGame.resolve("config/squid/settings-test.properties")).contains("remember.diamonds=12"); ) {
            Thread.sleep(100);
        }
        check("a remembered number comes back, isn't a setting, and is written a moment later", settings.remembered("diamonds", 0) + " " + settings.list().size()
                + " " + writtenAtOnce + " " + java.nio.file.Files.readString(modsGame.resolve("config/squid/settings-test.properties")).contains("remember.diamonds=12"),
                "12 3 false true");
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
        // Pictures from the graphics card come bottom row first, 4 bytes a pixel: the top of the JPEG is red, the bottom blue
        byte[] gpuPicture = new byte[64 * 36 * 4];
        for (int y = 0; y < 36; y++) {
            for (int x = 0; x < 64; x++) {
                int at = (y * 64 + x) * 4;
                boolean bottom = y < 18; // the first rows are the picture's bottom
                gpuPicture[at] = (byte) (bottom ? 0 : 255);
                gpuPicture[at + 2] = (byte) (bottom ? 255 : 0);
                gpuPicture[at + 3] = (byte) 255;
            }
        }
        squidclips.ClipBuffer.Encoder clipEncoder = new squidclips.ClipBuffer.Encoder();
        clipEncoder.jpeg(gpuPicture, 64, 36); // the second picture reuses the first one's picture and writer
        java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(clipEncoder.jpeg(gpuPicture, 64, 36)));
        int topColor = decoded.getRGB(32, 4);
        int bottomColor = decoded.getRGB(32, 31);
        check("graphics card pictures come out the right way up and the right colors", decoded.getWidth() + "x" + decoded.getHeight()
                + " " + ((topColor >> 16 & 0xFF) > 200 && (topColor & 0xFF) < 60) + " " + ((bottomColor & 0xFF) > 200 && (bottomColor >> 16 & 0xFF) < 60), "64x36 true true");

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
        squid.audio.Pcm toneOgg = squid.audio.Audio.decode(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("test/audio/tone.ogg")));
        long oggError = 0;
        long oggSignal = 0;
        for (int i = 0; i < toneWav.samples().length && i < toneOgg.samples().length; i++) {
            long d = toneOgg.samples()[i] - toneWav.samples()[i];
            oggError += d * d;
            oggSignal += (long) toneWav.samples()[i] * toneWav.samples()[i];
        }
        check("an Ogg Vorbis file decodes to the sound it was made from, exactly as long as it says",
                toneOgg.channels() + " " + toneOgg.rate() + " " + toneOgg.samples().length + " " + (10 * Math.log10((double) oggSignal / oggError) > 20),
                "2 44100 22050 true");
        // AAC: an .m4a (its start trimmed by the edit list) and a raw .aac stream, matching ffmpeg's own decode
        for (String aacFile : new String[] {"tone.m4a", "tone.aac"}) {
            squid.audio.Pcm toneAac = squid.audio.Audio.decode(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("test/audio/" + aacFile)));
            byte[] refBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("test/audio/" + aacFile + ".ffmpeg.raw"));
            long aacError = 0;
            long aacSignal = 0;
            for (int i = 0; i < refBytes.length / 2 && i < toneAac.samples().length; i++) {
                int ref = (short) ((refBytes[2 * i] & 0xFF) | (refBytes[2 * i + 1] << 8));
                long d = toneAac.samples()[i] - ref;
                aacError += d * d;
                aacSignal += (long) ref * ref;
            }
            check("an AAC file (" + aacFile + ") decodes like ffmpeg does, to the sample",
                    toneAac.channels() + " " + toneAac.rate() + " " + (toneAac.samples().length == refBytes.length / 2)
                            + " " + (10 * Math.log10((double) aacSignal / Math.max(1, aacError)) > 80), "2 44100 true true");
        }
        byte[] realAac = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("test/audio/tone.m4a"));
        java.util.Random aacFlips = new java.util.Random(7);
        List<String> aacCrashes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            byte[] damaged = realAac.clone();
            for (int k = 0; k < 4; k++) damaged[aacFlips.nextInt(damaged.length)] ^= (byte) (1 << aacFlips.nextInt(8));
            String result = failure(() -> squid.audio.Audio.decode(damaged));
            if (!result.isEmpty() && !result.startsWith("IllegalArgumentException")) aacCrashes.add(result);
        }
        check("200 damaged .m4a files never crash the decoder", aacCrashes.stream().distinct().toList(), List.of());
        check("something that isn't sound is turned away", squid.audio.Audio.canDecode("hello".getBytes()) + "", "false");

        // Squid Voice: our own voice codec, 24 kbps, close to the original on a voice-like sound
        squid.audio.VoiceCodec.Encoder voiceIn = new squid.audio.VoiceCodec.Encoder();
        squid.audio.VoiceCodec.Decoder voiceOut = new squid.audio.VoiceCodec.Decoder();
        int voiceFrames = 100;
        short[] talking = new short[voiceFrames * squid.audio.VoiceCodec.FRAME];
        for (int i = 0; i < talking.length; i++) { // a "voice": a buzzing note with harmonics, its pitch wobbling
            double sec = i / 16000.0;
            double pitch = 140 + 20 * Math.sin(2 * Math.PI * 3 * sec);
            double v = 0;
            for (int h = 1; h <= 12; h++) v += Math.sin(2 * Math.PI * pitch * h * sec) / h;
            talking[i] = (short) (v * 5000);
        }
        short[] heard = new short[talking.length];
        int packetBytes = 0;
        for (int f = 0; f < voiceFrames; f++) {
            byte[] packet = voiceIn.encode(java.util.Arrays.copyOfRange(talking, f * 320, f * 320 + 320));
            packetBytes = packet.length;
            System.arraycopy(voiceOut.decode(packet), 0, heard, f * 320, 320);
        }
        double voiceError = 0;
        double voiceSignal = 0;
        for (int i = 320; i < heard.length; i++) { // one frame later: the overlap
            double d = heard[i] - talking[i - 320];
            voiceError += d * d;
            voiceSignal += (double) talking[i - 320] * talking[i - 320];
        }
        check("Squid Voice packs 20 ms into 60 bytes (24 kbps) and sounds close to the original", packetBytes + " " + (10 * Math.log10(voiceSignal / voiceError) > 12),
                "60 true");
        check("a lost packet just fades out, without breaking", voiceOut.decode(null).length, 320);

        // Emblems (Squid > Emblem): saved and read back layer by layer, drawn at 64x64, more layers with more Squid Count
        String homeBefore = System.getProperty("squid.home");
        System.setProperty("squid.home", java.nio.file.Files.createTempDirectory("squid-emblem-home").toString());
        squidprofile.Emblem emblem = squidprofile.Emblem.starter();
        emblem.layers.add(new squidprofile.Emblem.Layer(squidprofile.Emblem.Shape.SQUID, 0x202020, 0.25, 0.75, 0.4, 90, true, false));
        emblem.save("test-player");
        squidprofile.Emblem emblemBack = squidprofile.Emblem.load("test-player");
        squidprofile.Emblem.Layer squidLayer = emblemBack.layers.get(2);
        check("an emblem is saved and read back, layer by layer", emblemBack.layers.size() + " " + squidLayer.shape + " " + Integer.toHexString(squidLayer.color)
                + " " + squidLayer.x + " " + squidLayer.y + " " + squidLayer.turn + " " + squidLayer.flipX + " " + squidLayer.flipY, "3 SQUID 202020 0.25 0.75 90.0 true false");
        java.awt.image.BufferedImage emblemPicture = emblemBack.draw();
        int emblemFilled = 0;
        for (int x = 0; x < squidprofile.Emblem.SIZE; x++) {
            for (int y = 0; y < squidprofile.Emblem.SIZE; y++) if ((emblemPicture.getRGB(x, y) >>> 24) != 0) emblemFilled++;
        }
        boolean everyShapeDraws = true;
        for (squidprofile.Emblem.Shape shape : squidprofile.Emblem.Shape.values()) {
            squidprofile.Emblem one = new squidprofile.Emblem();
            one.layers.add(new squidprofile.Emblem.Layer(shape, 0xFFFFFF, 0.5, 0.5, 1, 0, false, false));
            java.awt.image.BufferedImage drawn = one.draw();
            int pixels = 0;
            for (int x = 0; x < 64; x++) for (int y = 0; y < 64; y++) if ((drawn.getRGB(x, y) >>> 24) != 0) pixels++;
            everyShapeDraws &= pixels > 100; // a shape filling the emblem covers well over 100 of its 4096 pixels
        }
        check("an emblem draws as a 64x64 picture with see-through corners, and every shape draws", emblemPicture.getWidth() + " " + (emblemFilled > 1000)
                + " " + (emblemPicture.getRGB(0, 0) >>> 24) + " " + everyShapeDraws, "64 true 0 true");
        check("more Squid Count unlocks more layers", squidprofile.Emblem.UNLOCKS.layers(0) + " " + squidprofile.Emblem.UNLOCKS.layers(49) + " "
                + squidprofile.Emblem.UNLOCKS.layers(50) + " " + squidprofile.Emblem.UNLOCKS.layers(5000), "3 3 5 32");
        check("a broken emblem file is read as far as it makes sense", squidprofile.Emblem.parse("CIRCLE ff0000 9 0.5 1 0 0 0\nNOT_A_SHAPE 1 1 1 1 1 1 1\nhi").layers.size()
                + " " + squidprofile.Emblem.parse("CIRCLE ff0000 9 0.5 1 0 0 0").layers.get(0).x + " " + (squidprofile.Emblem.load("nobody") == null), "1 1.5 true");
        if (homeBefore == null) System.clearProperty("squid.home");
        else System.setProperty("squid.home", homeBefore);

        // Squid Music, the codec inside .sqda: close to the original, exactly as long
        squid.audio.Pcm tone = squid.audio.Audio.decode(java.nio.file.Files.readAllBytes(Path.of("test", "audio", "tone.wav")));
        squid.audio.MusicCodec.Encoded music = squid.audio.MusicCodec.encode(tone, squid.audio.MusicCodec.DEFAULT_QUALITY);
        squid.audio.Pcm musicBack = squid.audio.MusicCodec.decode(music);
        double musicError = 0;
        double musicSignal = 0;
        for (int i = 0; i < tone.samples().length; i++) {
            double d = musicBack.samples()[i] - tone.samples()[i];
            musicError += d * d;
            musicSignal += (double) tone.samples()[i] * tone.samples()[i];
        }
        check("Squid Music sounds like the original and is exactly as long", musicBack.samples().length == tone.samples().length
                && 10 * Math.log10(musicSignal / musicError) > 20, true);

        // .sqda: everything it carries comes back, and the quick read skips the sound
        squid.audio.Sqda made = squid.audio.Sqda.fromSound(tone, 6);
        short[] blip = new short[4410];
        for (int i = 0; i < blip.length; i++) blip[i] = (short) (Math.sin(i * 0.2) * 8000);
        made.addVariant("sting", 0, new squid.audio.Pcm(blip, 1, 44100), 6);
        made.info.put("title", "Tone");
        made.loops.add(new squid.audio.Sqda.Loop(0, 1000, 9000));
        made.cues.add(new squid.audio.Sqda.Cue(0, 4410, squid.audio.Sqda.BEAT, ""));
        made.lights.add(new squid.audio.Sqda.Light(0, 4410, 2205, 0xFF4080, 200, squid.audio.Sqda.LIGHT_FLASH, "stage"));
        made.triggers.add(new squid.audio.Sqda.Trigger("minecraft:creeper", squid.audio.Sqda.ENTERS_VIEW, 24, "variant:sting", 1, 1, 100));
        made.settings = new squid.audio.Sqda.Settings("Music plays", 0.8f, 1, 24, 1);
        byte[] sqdaBytes = made.write();
        // A chunk from some future Squid, which this one must skip
        byte[] future = new byte[sqdaBytes.length + 12];
        System.arraycopy(sqdaBytes, 0, future, 0, 5);
        System.arraycopy(new byte[] {'Z', 'Z', 'Z', 'Z', 0, 0, 0, 4, 1, 2, 3, 4}, 0, future, 5, 12);
        System.arraycopy(sqdaBytes, 5, future, 17, sqdaBytes.length - 5);
        squid.audio.Sqda read = squid.audio.Sqda.read(future);
        check(".sqda keeps its info, loop, cues, lights, triggers, settings and variants (and skips chunks it doesn't know)",
                squid.audio.SqdaTool.describe(read).equals(squid.audio.SqdaTool.describe(made)) && read.cues.equals(made.cues)
                        && read.lights.equals(made.lights) && read.triggers.equals(made.triggers) && read.variants.size() == 2, true);
        squid.audio.Sqda quick = squid.audio.Sqda.read(new java.io.ByteArrayInputStream(sqdaBytes), false);
        check("the quick read has the settings but not the sound", quick.settings.subtitle() + " " + quick.variants.size() + " " + quick.triggers.size(), "Music plays 0 1");
        java.util.Random picker = new java.util.Random(7);
        boolean stingPicked = false;
        for (int i = 0; i < 200; i++) stingPicked |= read.pickVariant(picker) == 1;
        check("a weight-0 variant only plays from triggers", stingPicked, false);
        check("Squid's decoders read .sqda too", squid.audio.Audio.canDecode(sqdaBytes) + " " + squid.audio.Audio.decode(sqdaBytes).samples().length,
                "true " + tone.samples().length);
        // Playing a piece at a time is the same as decoding it whole, from the start or after a jump
        squid.audio.Pcm whole = read.decode(0);
        squid.audio.Sqda.Player straight = read.play(0, false);
        short[] piecesRead = straight.read(30000);
        straight.seek(5000);
        short[] later = straight.read(100);
        check("a .sqda plays in pieces exactly like it decodes whole, after a jump too",
                java.util.Arrays.equals(piecesRead, java.util.Arrays.copyOf(whole.samples(), piecesRead.length))
                        && java.util.Arrays.equals(later, java.util.Arrays.copyOfRange(whole.samples(), 5000 * whole.channels(), (5000 + 100) * whole.channels())), true);
        squid.audio.Sqda.Player looper = read.play(0, true);
        int ch = whole.channels();
        java.util.List<Short> heardLoop = new ArrayList<>();
        while (looper.loops() < 2) {
            short[] piece = looper.read(1000);
            for (short v : piece) heardLoop.add(v);
        }
        // Around the first seam (9000 samples in), the sound should flow on without a jump
        int seam = 9000 * ch;
        int biggestStep = 0;
        for (int i = seam - 300 * ch; i < seam + 300 * ch && i + ch < heardLoop.size(); i++) biggestStep = Math.max(biggestStep, Math.abs(heardLoop.get(i + ch) - heardLoop.get(i)));
        int normalStep = 0;
        for (int i = ch; i < 9000 * ch; i++) normalStep = Math.max(normalStep, Math.abs(whole.samples()[i] - whole.samples()[i - ch]));
        check("looping jumps back to the loop's start smoothly, over and over", looper.loops() + " " + (biggestStep <= normalStep * 2 + 200), "2 true");

        // A loop shorter than one read (Minecraft asks for about a second at a time) still loops, and never plays past its end
        squid.audio.Sqda tiny = squid.audio.Sqda.fromSound(tone, 6);
        tiny.loops.add(new squid.audio.Sqda.Loop(0, 1000, 1600));
        squid.audio.Sqda.Player tinyLoop = squid.audio.Sqda.read(tiny.write()).play(0, true);
        short[] second = tinyLoop.read(44100);
        squid.audio.Sqda.Player emptyLoop = squid.audio.Sqda.read(squid.audio.Sqda.fromSound(new squid.audio.Pcm(new short[0], 1, 44100), 6).write()).play(0, true);
        check("a looping sound with nothing in it ends instead of looping forever", emptyLoop.read(4410) == null, true);
        check("a loop shorter than one read fills the read and stays inside the loop", second.length / ch + " " + (tinyLoop.loops() >= 70)
                + " " + (tinyLoop.position() >= 1000 && tinyLoop.position() <= 1600), "44100 true true");

        // Broken and made-up files: each fails safely or plays what it can, and never hangs or runs out of memory
        java.nio.ByteBuffer badWav = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        badWav.put("RIFF".getBytes()).putInt(36).put("WAVE".getBytes()).put("junk".getBytes()).putInt(-8);
        String wavLoop;
        try {
            squid.audio.Audio.decode(badWav.array());
            wavLoop = "decoded";
        } catch (IllegalArgumentException e) {
            wavLoop = e.getMessage();
        }
        check("a WAV chunk with a size of -8 doesn't loop forever", wavLoop, "the WAV file has no sound data");
        check("a mu-law WAV is refused instead of playing as noise", failure(() -> squid.audio.Audio.decode(wav(7, 1, 8, new byte[100]))),
                "IllegalArgumentException: WAV format 7 isn't supported: save it as plain PCM");
        byte[] surround = new byte[6 * 2 * 100];
        java.nio.ByteBuffer six = java.nio.ByteBuffer.wrap(surround).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 100; i++) six.putShort((short) 1000).putShort((short) -1000).putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        squid.audio.Pcm folded = squid.audio.Audio.decode(wav(1, 6, 16, surround));
        check("5.1 sound comes back as stereo, left still left", folded.channels() + " " + folded.samples()[0] + " " + folded.samples()[1], "2 1000 -1000");
        // A FLAC header that claims 68 billion samples, in a 42-byte file
        java.math.BigInteger info = java.math.BigInteger.ZERO;
        long[][] fields = {{4096, 16}, {4096, 16}, {0, 24}, {0, 24}, {44100, 20}, {1, 3}, {15, 5}, {(1L << 36) - 1, 36}, {0, 64}, {0, 64}};
        for (long[] f : fields) info = info.shiftLeft((int) f[1]).or(java.math.BigInteger.valueOf(f[0]));
        byte[] infoBytes = info.toByteArray();
        byte[] liar = new byte[8 + 34];
        System.arraycopy(new byte[] {'f', 'L', 'a', 'C', (byte) 0x80, 0, 0, 34}, 0, liar, 0, 8);
        System.arraycopy(infoBytes, Math.max(0, infoBytes.length - 34), liar, 8 + Math.max(0, 34 - infoBytes.length), Math.min(34, infoBytes.length));
        check("a tiny FLAC that claims hours of sound doesn't take the memory", failure(() -> squid.audio.Audio.decode(liar)).contains("OutOfMemory"), false);
        // An Ogg Vorbis file whose few setup bytes claim a codebook of 16 million codes (it would need gigabytes)
        byte[] vorbisId = {1, 'v', 'o', 'r', 'b', 'i', 's', 0, 0, 0, 0, 1, 0x44, (byte) 0xAC, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xB8, 1};
        byte[] vorbisComments = {3, 'v', 'o', 'r', 'b', 'i', 's', 0, 0, 0, 0, 0, 0, 0, 0, 1};
        byte[] hugeBook = bitsLsbFirst(new long[][] {{5, 8}, {'v', 8}, {'o', 8}, {'r', 8}, {'b', 8}, {'i', 8}, {'s', 8}, {0, 8},
                {0x564342, 24}, {1, 16}, {0xFFFFFF, 24}, {1, 1}, {23, 5}, {0xFFFFFF, 24}, {0, 4}});
        check("an Ogg file can't claim a codebook that would fill the memory",
                failure(() -> squid.audio.Audio.decode(ogg(vorbisId, vorbisComments, hugeBook))),
                "IllegalArgumentException: the Vorbis codebooks are far too big");
        squid.audio.Sqda.Variant real = tiny.variants.getFirst();
        // (Squid won't write such a file itself, so it's put together by hand)
        byte[] bigClaim = oldSqda(new squid.audio.Sqda.Variant(real.name(), real.weight(), real.rate(), real.channels(), 1L << 40, real.frames()));
        check("a .sqda that claims more sound than it has is refused", failure(() -> squid.audio.Sqda.read(bigClaim)),
                "IllegalArgumentException: not a .sqda file Squid can read: a variant's format is broken");
        squid.audio.Sqda handMade = squid.audio.Sqda.fromSound(tone, 6);
        handMade.variants.set(0, new squid.audio.Sqda.Variant(real.name(), real.weight(), real.rate(), real.channels(), 1L << 40, real.frames()));
        check("Squid won't write a .sqda it couldn't read back", failure(handMade::write).startsWith("IllegalArgumentException: it's too long for a .sqda"), true);
        // Bytes flipped anywhere in a real .sqda: refused, or played with silent patches, but never a crash
        java.util.Random flips = new java.util.Random(62);
        List<String> crashes = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            byte[] damaged = sqdaBytes.clone();
            for (int k = 0; k < 3; k++) damaged[5 + flips.nextInt(damaged.length - 5)] ^= (byte) (1 << flips.nextInt(8));
            String result = failure(() -> {
                squid.audio.Sqda file = squid.audio.Sqda.read(damaged);
                squid.audio.Sqda.Player player = file.play(0, true);
                for (int r = 0; r < 20; r++) player.read(4410);
                return file;
            });
            if (!result.isEmpty() && !result.startsWith("IllegalArgumentException")) crashes.add(result);
        }
        check("300 damaged .sqda files never crash the player", crashes.stream().distinct().toList(), List.of());

        // Every chunk has a check, and the file says which codec version made it
        int infoAt = new String(sqdaBytes, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("INFO");
        byte[] oneFlip = sqdaBytes.clone();
        oneFlip[infoAt + 12] ^= 0x20; // a letter in the title
        check("a damaged chunk is caught by its check", failure(() -> squid.audio.Sqda.read(oneFlip)),
                "IllegalArgumentException: not a .sqda file Squid can read: it's damaged (the INFO chunk doesn't match its check). Download or make it again");
        byte[] newerCodec = {'S', 'Q', 'D', 'A', 1, 'C', 'O', 'D', 'C', 0, 0, 0, 2, 1, 9};
        check("a sound from a newer Squid Music asks for an update", failure(() -> squid.audio.Sqda.read(newerCodec)),
                "IllegalArgumentException: not a .sqda file Squid can read: its sound uses a newer Squid Music (version 9). Update Squid");
        // A file from before the checks (no CRCS or CODC) still plays
        check("a .sqda from before the checks still plays the same", java.util.Arrays.equals(squid.audio.Sqda.read(oldSqda(made.variants.getFirst())).decode(0).samples(), whole.samples()), true);

        // Sample rates a .sqda can't hold are changed to ones it can, so every file Squid makes reads back
        short[] slow = new short[4000];
        for (int i = 0; i < slow.length; i++) slow[i] = (short) (Math.sin(i * 0.5) * 8000);
        squid.audio.Sqda slowBack = squid.audio.Sqda.read(squid.audio.SqdaTool.simple(new squid.audio.Pcm(slow, 1, 4000), 6, 0.25, 0.75,
                java.util.Map.of(), null, 0, 4).write());
        squid.audio.Sqda fastBack = squid.audio.Sqda.read(squid.audio.Sqda.fromSound(new squid.audio.Pcm(new short[38400 * 2], 2, 384000), 6).write());
        check("4000 Hz and 384 kHz sounds become 8000 Hz and 192 kHz, just as long, with the loop where it was",
                slowBack.variants.getFirst().rate() + " " + slowBack.variants.getFirst().samples() + " " + slowBack.loops.getFirst()
                        + " | " + fastBack.variants.getFirst().rate() + " " + fastBack.variants.getFirst().samples(),
                "8000 8000 Loop[variant=0, start=2000, end=6000] | 192000 19200");
        squid.audio.Sqda oddSqda = squid.audio.Sqda.fromSound(new squid.audio.Pcm(blip, 1, 44100), 6);
        oddSqda.addVariant("heavy", 100000, new squid.audio.Pcm(blip, 1, 44100), 6);
        oddSqda.triggers.add(new squid.audio.Sqda.Trigger("*", squid.audio.Sqda.COMES_NEAR, Float.NaN, "variant:heavy", Float.NaN, Float.POSITIVE_INFINITY, 20));
        squid.audio.Sqda oddBack = squid.audio.Sqda.read(oddSqda.write());
        check("a weight too big for the file is kept at the most, and a trigger's numbers that aren't real go back to normal",
                oddBack.variants.get(1).weight() + " " + oddBack.triggers.getFirst(),
                "65535 Trigger[entity=*, event=2, distance=0.0, sound=variant:heavy, volume=1.0, pitch=1.0, cooldown=20]");
        check("a tempo no music has makes no beat cues, and a first beat before the start counts on from inside the sound",
                squid.audio.SqdaTool.simple(tone, 6, null, null, java.util.Map.of(), 50000.0, 0, 4).cues.size() + " "
                        + squid.audio.SqdaTool.simple(tone, 6, null, null, java.util.Map.of(), 60.0, -0.9, 4).cues.getFirst().at(),
                "0 " + tone.rate() / 10);

        // Very short sounds keep their length exactly, and a tiny looping one doesn't stall the sound thread (every lap
        // used to jump back and decode frames again: 1 sample took 4 seconds of work for each second heard)
        StringBuilder shortSounds = new StringBuilder();
        for (int length : new int[] {1, 1023, 1024, 1025}) {
            squid.audio.Sqda fewBack = squid.audio.Sqda.read(squid.audio.Sqda.fromSound(new squid.audio.Pcm(java.util.Arrays.copyOf(blip, length), 1, 44100), 6).write());
            squid.audio.Sqda.Player once = fewBack.play(0, false);
            int fewHeard = 0;
            for (short[] piece = once.read(700); piece != null; piece = once.read(700)) fewHeard += piece.length;
            shortSounds.append(fewBack.decode(0).samples().length).append('/').append(fewHeard).append(' ');
        }
        squid.audio.Sqda.Player hum = squid.audio.Sqda.read(squid.audio.Sqda.fromSound(new squid.audio.Pcm(new short[] {1000}, 1, 48000), 6).write()).play(0, true);
        long humStart = System.nanoTime();
        short[] humSecond = null;
        for (int i = 0; i < 10; i++) humSecond = hum.read(48000);
        double humTook = (System.nanoTime() - humStart) / 1e9;
        check("very short sounds keep their length, and a 1-sample loop plays 10 seconds in well under a second",
                shortSounds.toString().strip() + " | " + humSecond.length + " " + (humTook < 1) + " " + (humSecond[0] == humSecond[47999]),
                "1/1 1023/1023 1024/1024 1025/1025 | 48000 true true");
        // A short loop is kept decoded: it must sound exactly like the sound itself, after the blend at its start
        squid.audio.Sqda.Player lapper = squid.audio.Sqda.read(tiny.write()).play(0, true);
        lapper.read(1600); // the first time through
        short[] lap = lapper.read(600);
        squid.audio.Pcm tinyWhole = squid.audio.Sqda.read(tiny.write()).decode(0);
        check("a kept loop sounds like the sound itself, and goes round and round",
                java.util.Arrays.equals(java.util.Arrays.copyOfRange(lap, 256 * ch, 600 * ch), java.util.Arrays.copyOfRange(tinyWhole.samples(), 1256 * ch, 1600 * ch))
                        + " " + lapper.loops() + " " + lapper.position(), "true 2 1000");

        // Analysis: a made-up song with a kick drum at 128 BPM, starting 0.3 s in, finds its tempo and first beat
        int beatRate = 22050;
        short[] drums = new short[beatRate * 30];
        double beatEvery = 60.0 / 128;
        for (double at = 0.3; at < 30; at += beatEvery) {
            int start = (int) (at * beatRate);
            for (int i = 0; i < 2000 && start + i < drums.length; i++) drums[start + i] += (short) (Math.sin(i * 0.05) * 20000 * Math.exp(-i / 400.0));
        }
        squid.audio.Analysis.Tempo foundTempo = squid.audio.Analysis.tempo(new squid.audio.Pcm(drums, 1, beatRate));
        double beatMiss = foundTempo == null ? 1 : ((foundTempo.offset() - 0.3) % beatEvery + beatEvery) % beatEvery;
        check("Analysis finds a song's tempo and first beat", foundTempo == null ? "nothing" : foundTempo.bpm() + " " + (Math.min(beatMiss, beatEvery - beatMiss) < 0.03),
                "128.0 true");
        // A song with a 6-second intro, then the same 4-second tune over and over: the loop is whole repeats, after the intro
        java.util.Random noise = new java.util.Random(4);
        short[] song = new short[beatRate * 40];
        for (int i = 0; i < beatRate * 6; i++) song[i] = (short) (noise.nextGaussian() * 3000);
        double[] notes = {220, 277, 330, 440, 330, 277, 247, 196};
        for (int i = beatRate * 6; i < song.length; i++) {
            double inTune = (i - beatRate * 6) % (beatRate * 4) / (double) beatRate; // where in the tune
            double hz = notes[(int) (inTune / 0.5)];
            song[i] = (short) (Math.sin(2 * Math.PI * hz * inTune) * 9000 * Math.exp(-(inTune % 0.5) * 3));
        }
        squid.audio.Analysis.LoopPoints loopFound = squid.audio.Analysis.loop(new squid.audio.Pcm(song, 1, beatRate), null, 8);
        double repeats = loopFound == null ? 0 : (loopFound.end() - loopFound.start()) / 4;
        check("Analysis finds a seamless loop: whole repeats of the tune, after the intro", loopFound != null && loopFound.start() >= 5.5
                && Math.abs(repeats - Math.rint(repeats)) < 0.02 && repeats >= 2, true);

        // Tags: a song's title and artist, from ID3v2 (MP3), Vorbis comments (FLAC) and a WAV's INFO list
        java.io.ByteArrayOutputStream id3 = new java.io.ByteArrayOutputStream();
        byte[] titleFrame = ("Pigstep").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] artistFrame = (" Lena Raine").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int tagSize = 10 + titleFrame.length + 10 + artistFrame.length;
        id3.write(new byte[] {'I', 'D', '3', 3, 0, 0, 0, 0, (byte) (tagSize >> 7), (byte) (tagSize & 0x7F)});
        for (Object[] frame : new Object[][] {{"TIT2", titleFrame}, {"TPE1", artistFrame}}) {
            byte[] body = (byte[]) frame[1];
            id3.write(((String) frame[0]).getBytes());
            id3.write(new byte[] {0, 0, 0, (byte) body.length, 0, 0});
            id3.write(body);
        }
        id3.write(new byte[64]);
        check("an MP3's ID3 tag gives the title and artist", squid.audio.Tags.read(id3.toByteArray()).toString(), "{title=Pigstep, artist=Lena Raine}");
        java.io.ByteArrayOutputStream flacTags = new java.io.ByteArrayOutputStream();
        java.nio.ByteBuffer comment = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        comment.putInt(2).put("me".getBytes()).putInt(2).putInt(9).put("TITLE=Cat".getBytes()).putInt(11).put("artist=C418".getBytes());
        int commentLength = comment.position();
        flacTags.write(new byte[] {'f', 'L', 'a', 'C', (byte) 0x84, 0, 0, (byte) commentLength});
        flacTags.write(comment.array(), 0, commentLength);
        check("a FLAC's Vorbis comments give the title and artist", squid.audio.Tags.read(flacTags.toByteArray()).toString(), "{title=Cat, artist=C418}");
        byte[] plainWav = wav(1, 1, 16, new byte[200]);
        check("a file without tags gives nothing (so its name can stand in)", squid.audio.Tags.read(plainWav).toString(), "{}");
        byte[] oldTag = new byte[128];
        System.arraycopy("TAGSweden".getBytes(), 0, oldTag, 0, 9);
        System.arraycopy("C418".getBytes(), 0, oldTag, 33, 4);
        check("an .m4a's iTunes tags give the title and artist", squid.audio.Tags.read(java.nio.file.Files.readAllBytes(Path.of("test", "audio", "tagged.m4a"))).toString(),
                "{title=Test Tone, artist=Squid}");
        check("an MP3's old ID3v1 tag at the end works too", squid.audio.Tags.readEnd(oldTag).toString(), "{title=Sweden, artist=C418}");

        // Music bars: a deep tone lights the bass bars, a high one the treble bars
        short[] lowTone = new short[2048];
        short[] highTone = new short[2048];
        for (int i = 0; i < 2048; i++) {
            lowTone[i] = (short) (Math.sin(2 * Math.PI * 80 * i / 44100.0) * 20000);
            highTone[i] = (short) (Math.sin(2 * Math.PI * 8000 * i / 44100.0) * 20000);
        }
        float[] lowBars = squid.audio.Analysis.bands(lowTone, 1, 44100, 8);
        float[] highBars = squid.audio.Analysis.bands(highTone, 1, 44100, 8);
        check("music bars: a deep tone lights the bass, a high tone the treble", (lowBars[0] > lowBars[7] + 0.3) + " " + (highBars[7] > highBars[0] + 0.3), "true true");

        // SqdaTool: mono, notes about things that won't work as hoped, and a full --info
        squid.audio.Pcm stereoTone = new squid.audio.Pcm(new short[] {100, 300, -100, -300}, 2, 44100);
        check("--mono mixes both channels into one", java.util.Arrays.toString(squid.audio.SqdaTool.toMono(stereoTone).samples()), "[200, -200]");
        squid.audio.Sqda placedStereo = squid.audio.Sqda.fromSound(new squid.audio.Pcm(new short[44100 * 2], 2, 44100), 6);
        placedStereo.settings = new squid.audio.Sqda.Settings("", 1, 1, 48, 0);
        check("a stereo sound with a distance gets a note", squid.audio.SqdaTool.warnings(placedStereo).size(), 1);
        squid.audio.Sqda withCues = squid.audio.Sqda.fromSound(tone, 6);
        withCues.cues.add(new squid.audio.Sqda.Cue(0, tone.rate(), squid.audio.Sqda.SECTION, "chorus"));
        check("--info lists cues with their times", squid.audio.SqdaTool.details(withCues).strip(), "1.00 s  section chorus");

        // Squid Net: Squid's messages ride in Minecraft's own custom payload packets, both ways, through the real
        // packet code. Minecraft throws away channels it doesn't know; Squid keeps its own.
        List<URL> netUrls = new ArrayList<>(urls);
        for (String part : new String[] {"net", "voice", "voiceserver", "sounds", "jukebox", "mods", "paint", "emotes"}) netUrls.add(Path.of("build", "builtin", part + ".jar").toUri().toURL());
        SquidClassLoader netLoader = new SquidClassLoader(netUrls.toArray(URL[]::new));
        Squid netSquid = new Squid(mod("squid-net"));
        ((SquidMod) netLoader.loadClass("squidnet.Net").getDeclaredConstructor().newInstance()).init(netSquid);
        Class<?> netClass = netLoader.loadClass("squidnet.Net");
        List<String> serverGot = new ArrayList<>();
        List<String> clientGot = new ArrayList<>();
        java.util.function.BiConsumer<Object, byte[]> onServer = (player, data) -> serverGot.add(new String(data));
        java.util.function.Consumer<byte[]> onClient = data -> clientGot.add(new String(data));
        Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer", false, netLoader);
        netClass.getMethod("onServer", String.class, java.util.function.BiConsumer.class).invoke(null, "test", onServer);
        netClass.getMethod("onClient", String.class, java.util.function.Consumer.class).invoke(null, "test", onClient);
        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", true, netLoader);
        java.lang.reflect.Method idOf = identifier.getMethod("fromNamespaceAndPath", String.class, String.class);
        Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf", true, netLoader);
        Class<?> friendly = Class.forName("net.minecraft.network.FriendlyByteBuf", true, netLoader);
        Class<?> streamCodec = Class.forName("net.minecraft.network.codec.StreamCodec", true, netLoader);
        Class<?> payloadClass = netLoader.loadClass("squidnet.SquidPayload");
        Class<?> serverbound = Class.forName("net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket", true, netLoader);
        Class<?> clientbound = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket", true, netLoader);
        // Written into bytes and read back, the way a real connection does
        java.util.function.BiFunction<Object, Object, Object> throughTheWire = (codec, packet) -> {
            try {
                Object buf = friendly.getConstructor(byteBuf).newInstance(Class.forName("io.netty.buffer.Unpooled", true, netLoader).getMethod("buffer").invoke(null));
                streamCodec.getMethod("encode", Object.class, Object.class).invoke(codec, buf, packet);
                return streamCodec.getMethod("decode", Object.class).invoke(codec, buf);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e.getCause() == null ? e : e.getCause());
            }
        };
        Object hiServer = serverbound.getConstructors()[0].newInstance(payloadClass.getConstructors()[0].newInstance(idOf.invoke(null, "squid", "test"), "hi server".getBytes()));
        Object arrived = throughTheWire.apply(serverbound.getField("STREAM_CODEC").get(null), hiServer);
        Object arrivedPayload = serverbound.getMethod("payload").invoke(arrived);
        check("a Squid message to the server arrives whole", arrivedPayload.getClass().getSimpleName() + " "
                + new String((byte[]) payloadClass.getMethod("data").invoke(arrivedPayload)), "SquidPayload hi server");
        // Another mod's message, as it comes off the wire: its channel name, then its bytes
        Object otherBuf = friendly.getConstructor(byteBuf).newInstance(Class.forName("io.netty.buffer.Unpooled", true, netLoader).getMethod("buffer").invoke(null));
        friendly.getMethod("writeIdentifier", identifier).invoke(otherBuf, idOf.invoke(null, "othermod", "x"));
        friendly.getMethod("writeBytes", byte[].class).invoke(otherBuf, (Object) "?".getBytes());
        Object otherMod = streamCodec.getMethod("decode", Object.class).invoke(serverbound.getField("STREAM_CODEC").get(null), otherBuf);
        check("other channels are still thrown away like Minecraft does", serverbound.getMethod("payload").invoke(otherMod).getClass().getSimpleName(), "DiscardedPayload");
        Object hiGame = clientbound.getConstructors()[0].newInstance(payloadClass.getConstructors()[0].newInstance(idOf.invoke(null, "squid", "test"), "hi game".getBytes()));
        Object arrivedInGame = throughTheWire.apply(clientbound.getField("CONFIG_STREAM_CODEC").get(null), hiGame);
        // The server's listener gets it: a real ServerGamePacketListenerImpl (made without its constructor) with a player.
        // Players need Minecraft's registries, so Minecraft starts them up first, like it does when it opens.
        Class.forName("net.minecraft.SharedConstants", true, netLoader).getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap", true, netLoader).getMethod("bootStrap").invoke(null);
        Class<?> gameListener = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl", true, netLoader);
        Object listener = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, gameListener);
        Object fakePlayer = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, serverPlayerClass);
        Field uuidField = Class.forName("net.minecraft.world.entity.Entity", false, netLoader).getDeclaredField("uuid");
        uuidField.setAccessible(true);
        uuidField.set(fakePlayer, java.util.UUID.fromString("00000000-0000-0000-0000-000000000062"));
        gameListener.getField("player").set(listener, fakePlayer);
        check("before saying hello, a player doesn't count as having Squid", netClass.getMethod("hasSquid", serverPlayerClass).invoke(null, fakePlayer), false);
        gameListener.getMethod("handleCustomPayload", serverbound).invoke(listener, arrived);
        Object hello = serverbound.getConstructors()[0].newInstance(payloadClass.getConstructors()[0].newInstance(idOf.invoke(null, "squid", "hello"), new byte[] {1}));
        gameListener.getMethod("handleCustomPayload", serverbound).invoke(listener, throughTheWire.apply(serverbound.getField("STREAM_CODEC").get(null), hello));
        check("the server hands Squid messages to Squid, and knows who has Squid", serverGot + " " + netClass.getMethod("hasSquid", serverPlayerClass).invoke(null, fakePlayer),
                "[hi server] true");
        // And the game's listener
        Class<?> clientListener = Class.forName("net.minecraft.client.multiplayer.ClientPacketListener", true, netLoader);
        Object inGame = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, clientListener);
        clientListener.getMethod("handleCustomPayload", clientbound).invoke(inGame, arrivedInGame);
        check("the game hands Squid messages to Squid", clientGot.toString(), "[hi game]");
        // Squid Sounds' hooks go into Minecraft's sound classes, which must still load and pass Java's checks
        ((SquidMod) netLoader.loadClass("squidsounds.Sounds").getDeclaredConstructor().newInstance()).init(new Squid(mod("squid-sounds")));
        boolean soundsLoad = true;
        for (String name : new String[] {"net.minecraft.client.resources.sounds.Sound", "net.minecraft.client.sounds.WeighedSoundEvents",
                "net.minecraft.client.sounds.SoundBufferLibrary"}) {
            soundsLoad &= Class.forName(name, true, netLoader).getClassLoader() == netLoader;
        }
        check("Minecraft's sound classes load with Squid Sounds' .sqda hooks", soundsLoad, true);
        // A damaged .sqda in a resource pack is said once and left to Minecraft. Failing inside the hook on every play
        // would count against the hook, and after a few failures Squid Sounds would stop working for every sound.
        Object soundsPart = netLoader.loadClass("squidsounds.Sounds").getDeclaredConstructor().newInstance();
        java.lang.reflect.Method readPackSqda = soundsPart.getClass().getDeclaredMethod("sqda", identifier, byte[].class);
        readPackSqda.setAccessible(true);
        java.lang.reflect.Method parseId = identifier.getMethod("tryParse", String.class);
        Object brokenPath = parseId.invoke(null, "minecraft:sounds/broken.ogg");
        Object brokenFirst = readPackSqda.invoke(soundsPart, brokenPath, oneFlip);
        Object brokenAgain = readPackSqda.invoke(soundsPart, brokenPath, oneFlip);
        check("a damaged .sqda in a pack doesn't fail inside Squid Sounds' hooks, and a good one still plays",
                brokenFirst + " " + brokenAgain + " " + (readPackSqda.invoke(soundsPart, parseId.invoke(null, "minecraft:sounds/good.ogg"), sqdaBytes) != null),
                "null null true");
        java.lang.reflect.Field variantName = soundsPart.getClass().getDeclaredField("VARIANT");
        variantName.setAccessible(true);
        java.util.regex.Pattern variantPattern = (java.util.regex.Pattern) variantName.get(null);
        check("a variant asked for by number can't be a number too big to read", variantPattern.matcher("music/x.squidvariant3.ogg").matches() + " "
                + variantPattern.matcher("music/x.squidvariant99999999999.ogg").matches(), "true false");
        // Loop points only work streamed (Minecraft loops a whole buffer from end to start), so a .sqda with them streams
        java.lang.reflect.Method streams = soundsPart.getClass().getDeclaredMethod("streams", netLoader.loadClass("squid.audio.Sqda"));
        streams.setAccessible(true);
        java.lang.reflect.Method readInGame = netLoader.loadClass("squid.audio.Sqda").getMethod("read", byte[].class);
        StringBuilder streamed = new StringBuilder();
        for (int[] streamKind : new int[][] {{0, 0}, {1, 0}, {1, 2}, {0, 1}}) {
            squid.audio.Sqda file = squid.audio.Sqda.fromSound(new squid.audio.Pcm(blip, 1, 44100), 6);
            if (streamKind[0] == 1) file.loops.add(new squid.audio.Sqda.Loop(0, 600, 3000));
            file.settings = new squid.audio.Sqda.Settings("", 1, 1, 16, streamKind[1]);
            streamed.append(streams.invoke(null, readInGame.invoke(null, (Object) file.write()))).append(' ');
        }
        check("a .sqda with loop points streams, unless it says never", streamed.toString().strip(), "null true false true");
        // The Jukebox's hook goes into Minecraft's music manager (so the game's music waits while a song plays)
        ((SquidMod) netLoader.loadClass("squidjukebox.Jukebox").getDeclaredConstructor().newInstance()).init(new Squid(mod("squid-jukebox")));
        // The voice changer: Chipmunk is higher and Giant deeper (counted by how often the sound crosses zero), at the
        // same speed; Robot changes the sound; Echo keeps going after you stop
        Class<?> voiceEffect = netLoader.loadClass("squidvoice.VoiceEffect");
        java.lang.reflect.Constructor<?> makeEffect = voiceEffect.getDeclaredConstructor(String.class, int.class);
        makeEffect.setAccessible(true);
        java.lang.reflect.Method processVoice = voiceEffect.getDeclaredMethod("process", short[].class);
        processVoice.setAccessible(true);
        StringBuilder changerPitches = new StringBuilder();
        for (String changerKind : new String[] {"None", "Chipmunk", "Giant"}) {
            Object changer = makeEffect.newInstance(changerKind, 16000);
            int changerCrossings = 0;
            short changerLast = 0;
            for (int f = 0; f < 50; f++) { // a second of a 400 Hz tone, 20 ms at a time
                short[] frame = new short[320];
                for (int i = 0; i < 320; i++) frame[i] = (short) (Math.sin(2 * Math.PI * 400 * (f * 320 + i) / 16000.0) * 8000);
                processVoice.invoke(changer, (Object) frame);
                if (f >= 10) for (short v : frame) {
                    if ((changerLast < 0) != (v < 0)) changerCrossings++;
                    changerLast = v;
                }
            }
            changerPitches.append(Math.round(changerCrossings / 2.0 / 0.8 / 50) * 50).append(' '); // in Hz, to the nearest 50
        }
        Object robotChanger = makeEffect.newInstance("Robot", 16000);
        short[] robotFrame = new short[320];
        java.util.Arrays.fill(robotFrame, (short) 1000);
        processVoice.invoke(robotChanger, (Object) robotFrame);
        Object echoChanger = makeEffect.newInstance("Echo", 16000);
        short[] echoLoud = new short[320];
        java.util.Arrays.fill(echoLoud, (short) 10000);
        processVoice.invoke(echoChanger, (Object) echoLoud);
        short[] echoQuiet = new short[320];
        for (int f = 0; f < 12; f++) processVoice.invoke(echoChanger, (Object) (echoQuiet = new short[320])); // 240 ms of silence
        short[] echoAfter = new short[320];
        processVoice.invoke(echoChanger, (Object) echoAfter);
        check("the voice changer: higher, deeper, robot and echo", changerPitches.toString().strip() + " " + (robotFrame[100] != 1000) + " " + (echoAfter[100] != 0),
                "400 600 300 true true");
        // Emotes: the server's message says who and which, and nobody can send more than one a second
        Class<?> emotes = netLoader.loadClass("squidemotes.Emotes");
        java.lang.reflect.Method emoteMessage = emotes.getDeclaredMethod("message", java.util.UUID.class, int.class);
        java.lang.reflect.Method emoteWho = emotes.getDeclaredMethod("who", byte[].class);
        java.lang.reflect.Method emoteWhich = emotes.getDeclaredMethod("emote", byte[].class);
        java.lang.reflect.Method emoteAllowed = emotes.getDeclaredMethod("allowed", java.util.Map.class, java.util.UUID.class, long.class);
        for (java.lang.reflect.Method m : new java.lang.reflect.Method[] {emoteMessage, emoteWho, emoteWhich, emoteAllowed}) m.setAccessible(true);
        java.util.UUID waver = java.util.UUID.randomUUID();
        byte[] waved = (byte[]) emoteMessage.invoke(null, waver, 3);
        byte[] unknownEmote = waved.clone();
        unknownEmote[16] = 99;
        java.util.Map<java.util.UUID, Long> emoteTimes = new java.util.HashMap<>();
        check("an emote's message says who and which, and an unknown emote is ignored", waver.equals(emoteWho.invoke(null, waved)) + " "
                + emoteWhich.invoke(null, waved) + " " + emoteWhich.invoke(null, unknownEmote), "true 3 -1");
        check("one emote a second at most", emoteAllowed.invoke(null, emoteTimes, waver, 10_000L) + " " + emoteAllowed.invoke(null, emoteTimes, waver, 10_500L) + " "
                + emoteAllowed.invoke(null, emoteTimes, waver, 11_000L), "true false true");
        check("the emote wheel loads", Class.forName("squidemotes.EmoteScreen", true, netLoader).getSimpleName() + " "
                + Class.forName("squidemotes.EmotesClient", true, netLoader).getSimpleName(), "EmoteScreen EmotesClient");
        // Squid's own achievements: each one is reported by a part of Squid, and counts once
        List<Object> achievementsHeard = new ArrayList<>();
        Events.on("achievement", "achievement-test", achievementsHeard::add);
        Events.fire("achievement", "paint");
        Events.remove("achievement-test");
        Path achievementCount = java.nio.file.Files.createTempDirectory("squid-count").resolve("squid-count.json");
        squidcount.CountFile achievementFile = squidcount.CountFile.load(achievementCount);
        int firstTime = achievementFile.earn("abc", "Sam", "squid:paint", 25);
        int secondTime = achievementFile.earn("abc", "Sam", "squid:paint", 25);
        check("an achievement reaches Squid Count, and counts once ever", achievementsHeard + " " + firstTime + " " + secondTime, "[paint] 25 0");
        // Karaoke lyrics from an .lrc file: times in any order, a line sung twice, an offset, word times left out
        Class<?> lyricsClass = netLoader.loadClass("squidjukebox.Lyrics");
        java.lang.reflect.Method parseLyrics = lyricsClass.getDeclaredMethod("parse", String.class);
        java.lang.reflect.Method currentLyric = lyricsClass.getDeclaredMethod("current", List.class, double.class);
        parseLyrics.setAccessible(true);
        currentLyric.setAccessible(true);
        List<?> sung = (List<?>) parseLyrics.invoke(null, "[ar:Someone]\n[offset:+500]\n[00:12.50]Second <00:13.00>line\r\n[00:05.00][00:20.00]Chorus!\nno time here\n[01:02:25]Last");
        check("lyrics from an .lrc file, in the order they're sung", sung.toString().replaceAll("Line\\[at=|, text=|]", " ").replaceAll("\\s+", " ").strip(),
                "[ 4.5 Chorus! , 12.0 Second line , 19.5 Chorus! , 61.75 Last");
        check("the line being sung at a time", currentLyric.invoke(null, sung, 3.0) + " " + currentLyric.invoke(null, sung, 12.0) + " "
                + currentLyric.invoke(null, sung, 15.0) + " " + currentLyric.invoke(null, sung, 999.0), "-1 1 1 3");
        // A .sqda the speakers won't take at its own rate (like 96 kHz) is changed to 48 kHz as it streams in the Jukebox
        short[] fastSong = new short[96000];
        for (int i = 0; i < fastSong.length; i++) fastSong[i] = (short) (Math.sin(i * 2 * Math.PI * 440 / 96000) * 10000);
        Object jukeboxSqda = netLoader.loadClass("squid.audio.Sqda").getMethod("read", byte[].class)
                .invoke(null, (Object) squid.audio.Sqda.fromSound(new squid.audio.Pcm(fastSong, 1, 96000), 6).write());
        java.lang.reflect.Constructor<?> sqdaSource = netLoader.loadClass("squidjukebox.SongPlayer$SqdaSource").getDeclaredConstructor(jukeboxSqda.getClass(), boolean.class);
        sqdaSource.setAccessible(true);
        Class<?> sourceClass = netLoader.loadClass("squidjukebox.SongPlayer$Source");
        java.lang.reflect.Constructor<?> resampled = netLoader.loadClass("squidjukebox.SongPlayer$Resampled").getDeclaredConstructor(sourceClass, int.class);
        resampled.setAccessible(true);
        Object slowedDown = resampled.newInstance(sqdaSource.newInstance(jukeboxSqda, false), 48000);
        java.lang.reflect.Method readSource = sourceClass.getDeclaredMethod("read", int.class);
        readSource.setAccessible(true);
        int resampledLength = 0;
        int crossings = 0;
        short lastSample = 0;
        for (short[] piece = (short[]) readSource.invoke(slowedDown, 1000); piece != null; piece = (short[]) readSource.invoke(slowedDown, 1000)) {
            for (short v : piece) {
                if ((v >= 0) != (lastSample >= 0)) crossings++;
                lastSample = v;
            }
            resampledLength += piece.length;
        }
        check("a 96 kHz .sqda streams at 48 kHz in the Jukebox, as long and at the same pitch", (Math.abs(resampledLength - 48000) <= 2) + " "
                + (Math.abs(crossings - 880) <= 4), "true true");
        // Texture pack names become folder names: nothing that could reach another folder, or that Windows refuses
        java.lang.reflect.Method goodPackName = netLoader.loadClass("squidpaint.Paint").getDeclaredMethod("goodName", String.class);
        goodPackName.setAccessible(true);
        StringBuilder packNames = new StringBuilder();
        for (String name : new String[] {"My Pack", "Sam's Blocks (v2)", "Épée", "../escape", "a/b", "a\b", "", " ", ".hidden", "dot.", "trailing ", "x".repeat(33)}) {
            packNames.append(goodPackName.invoke(null, name).equals(true) ? "y" : "n");
        }
        check("which texture pack names are allowed", packNames.toString(), "yyynnnnnnnnn");
        // The Block Painter starts, and its screens load against Minecraft's classes
        ((SquidMod) netLoader.loadClass("squidpaint.Paint").getDeclaredConstructor().newInstance()).init(new Squid(mod("squid-paint")));
        // Voice chat, Emotes and the Squid menu (with the Mod Maker) start too, like they do when the game opens
        List<String> partsStarted = new ArrayList<>();
        for (String[] part : new String[][] {{"squidvoice.Voice", "squid-voice"}, {"squidemotes.Emotes", "squid-emotes"}, {"squidmods.ModsMenu", "squid-mods"}}) {
            ((SquidMod) netLoader.loadClass(part[0]).getDeclaredConstructor().newInstance()).init(new Squid(mod(part[1])));
            partsStarted.add(part[1]);
        }
        check("voice chat, emotes and the Squid menu start", partsStarted.toString(), "[squid-voice, squid-emotes, squid-mods]");
        check("the Block Painter's and Sound Swapper's screens load", Class.forName("squidpaint.BlockPaintScreen", true, netLoader).getSimpleName() + " "
                + Class.forName("squidpaint.BlockPickScreen", true, netLoader).getSimpleName() + " "
                + Class.forName("squidpaint.SoundSwapScreen", true, netLoader).getSimpleName(), "BlockPaintScreen BlockPickScreen SoundSwapScreen");
        // Recording a sound: the quiet at both ends is cut off (keeping 50 ms), and it's made loud
        java.lang.reflect.Method tidy = netLoader.loadClass("squidpaint.Recorder").getDeclaredMethod("tidy", short[].class, int.class);
        tidy.setAccessible(true);
        short[] recorded = new short[44100];
        for (int i = 22050; i < 26460; i++) recorded[i] = (short) (Math.sin(i * 0.1) * 3000); // 0.1 s of tone after 0.5 s of quiet
        for (int i = 0; i < recorded.length; i += 7) recorded[i] += 20; // a little hiss
        short[] tidied = (short[]) tidy.invoke(null, recorded, 44100);
        int loudestTidied = 0;
        for (short v : tidied) loudestTidied = Math.max(loudestTidied, Math.abs(v));
        check("a recording loses its quiet ends and gets loud", Math.abs(tidied.length - 4410 - 4410) <= 441 * 2 && loudestTidied > 28000
                && tidied[0] == 0, true);
        check("a recording of nothing is empty", ((short[]) tidy.invoke(null, new short[44100], 44100)).length, 0);
        // Sound Swapper effects: Chipmunk is shorter (faster), Giant longer, Echo rings on, Backwards is backwards
        Class<?> effects = netLoader.loadClass("squidpaint.Effects");
        java.lang.reflect.Method effect = effects.getDeclaredMethod("apply", String.class, squid.audio.Pcm.class);
        effect.setAccessible(true);
        squid.audio.Pcm voice = new squid.audio.Pcm(new short[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 1, 8);
        StringBuilder lengths = new StringBuilder();
        for (String name : new String[] {"None", "Chipmunk", "Giant", "Robot", "Echo", "Backwards"}) {
            lengths.append(((squid.audio.Pcm) effect.invoke(null, name, voice)).samples().length).append(' ');
        }
        check("Sound Swapper effects change the sound", lengths.toString().strip() + " "
                + ((squid.audio.Pcm) effect.invoke(null, "Backwards", voice)).samples()[0], "10 6 15 10 14 10 10");
        // A dropped picture becomes pixel art: a picture half red, half blue fills a 16 x 16 texture the same way
        java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(200, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 100; y++) for (int x = 0; x < 200; x++) picture.setRGB(x, y, x < 100 ? 0xFFFF0000 : 0xFF0000FF);
        java.lang.reflect.Method fit = netLoader.loadClass("squidpaint.Paint").getDeclaredMethod("fit", java.awt.image.BufferedImage.class, int.class, int.class, int.class);
        fit.setAccessible(true);
        int[] art = (int[]) fit.invoke(null, picture, 16, 16, 0);
        check("a picture becomes pixel art (the middle of a wide picture)", Integer.toHexString(art[0]) + " " + Integer.toHexString(art[15]) + " " + Integer.toHexString(art[255]),
                "ffff0000 ff0000ff ff0000ff");
        int[] paintedFrames = (int[]) fit.invoke(null, picture, 16, 48, 16);
        check("an animated texture gets the picture in every frame", paintedFrames[0] == paintedFrames[16 * 16] && paintedFrames[15] == paintedFrames[32 * 16 + 15] && paintedFrames[0] != paintedFrames[15], true);
        java.awt.image.BufferedImage upright = new java.awt.image.BufferedImage(100, 200, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 200; y++) for (int x = 0; x < 100; x++) upright.setRGB(x, y, y < 100 ? 0xFF00FF00 : 0xFFFFFF00);
        int[] tall = (int[]) fit.invoke(null, upright, 16, 32, 0); // a tall painting isn't two frames
        check("a tall painting gets one picture, not two", Integer.toHexString(tall[0]) + " " + Integer.toHexString(tall[16 * 16]), "ff00ff00 ffffff00");
        // The Fill bucket fills what touches, not what only touches at a corner
        java.lang.reflect.Method fill = netLoader.loadClass("squidpaint.BlockPaintScreen").getDeclaredMethod("fill", int[].class, int.class, int.class, int.class, int.class);
        fill.setAccessible(true);
        int[] grid = {1, 1, 2, 1, 2, 2, 2, 1, 1}; // 3 x 3: the top left 1s are cut off from the bottom right one
        fill.invoke(null, grid, 3, 3, 0, 9);
        check("the Fill bucket fills the touching pixels", java.util.Arrays.toString(grid), "[9, 9, 2, 9, 2, 2, 2, 1, 1]");
        // Mod icons: read from a project's resources (its squid.json "icon", or icon.png)
        Path iconMod = java.nio.file.Files.createDirectories(java.nio.file.Files.createTempDirectory("squid-icon").resolve("Shiny"));
        java.nio.file.Files.createDirectories(iconMod.resolve("resources"));
        java.nio.file.Files.writeString(iconMod.resolve("squid.json"), "{\"icon\": \"pic.png\"}");
        java.nio.file.Files.write(iconMod.resolve("resources/pic.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        java.lang.reflect.Method readIcon = netLoader.loadClass("squidmods.ModIcons").getDeclaredMethod("read", Path.class);
        readIcon.setAccessible(true);
        byte[] iconBytes = (byte[]) readIcon.invoke(null, iconMod);
        check("the Mods screen finds a mod's icon from its squid.json", iconBytes == null ? "none" : iconBytes.length + " bytes", "4 bytes");
        check("Minecraft's music manager loads with the Jukebox's hook",
                Class.forName("net.minecraft.client.sounds.MusicManager", true, netLoader).getClassLoader() == netLoader, true);
        check("the server's player list loads with Squid Net's join and leave hooks",
                Class.forName("net.minecraft.server.players.PlayerList", true, netLoader).getClassLoader() == netLoader, true);

        // Squid Voice on the network: what the server sends on is what the game reads, and voices come from where people stand
        java.util.UUID speakerId = java.util.UUID.fromString("12345678-0000-0000-0000-00000000abcd");
        java.lang.reflect.Method message = netLoader.loadClass("squidvoiceserver.VoiceServer").getDeclaredMethod("message", java.util.UUID.class, int.class, byte[].class, double.class, double.class, double.class);
        message.setAccessible(true);
        Class<?> speakersClass = netLoader.loadClass("squidvoice.Speakers");
        Class<?> earClass = netLoader.loadClass("squidvoice.Speakers$Ear");
        java.lang.reflect.Constructor<?> earMaker = earClass.getDeclaredConstructor(double.class, double.class, double.class, float.class);
        earMaker.setAccessible(true);
        Object voiceEar = earMaker.newInstance(0.0, 64.0, 0.0, 0f); // facing south (+z), so east (+x) is on your left
        java.lang.reflect.Constructor<?> speakersMaker = speakersClass.getDeclaredConstructor(java.util.function.Supplier.class);
        speakersMaker.setAccessible(true);
        Object speakers = speakersMaker.newInstance((java.util.function.Supplier<Object>) () -> voiceEar);
        java.lang.reflect.Method received = speakersClass.getDeclaredMethod("received", byte[].class);
        received.setAccessible(true);
        squid.audio.VoiceCodec.Encoder netVoice = new squid.audio.VoiceCodec.Encoder();
        for (int seq = 0; seq < 3; seq++) {
            byte[] packet = netVoice.encode(java.util.Arrays.copyOfRange(talking, seq * 320, seq * 320 + 320));
            byte[] fromGame = new byte[2 + packet.length];
            fromGame[1] = (byte) seq;
            System.arraycopy(packet, 0, fromGame, 2, packet.length);
            received.invoke(speakers, (byte[]) message.invoke(null, speakerId, 0, fromGame, 10.0, 64.0, 0.0));
        }
        java.lang.reflect.Method all = speakersClass.getDeclaredMethod("all");
        all.setAccessible(true);
        Object speaker = ((java.util.Map<?, ?>) all.invoke(speakers)).get(speakerId);
        java.lang.reflect.Method next = speaker.getClass().getDeclaredMethod("next");
        next.setAccessible(true);
        java.lang.reflect.Method place = speakersClass.getDeclaredMethod("place", earClass, speaker.getClass(), int.class);
        place.setAccessible(true);
        float[] gains = (float[]) place.invoke(null, voiceEar, speaker, 48);
        int frames = 0;
        while (next.invoke(speaker) != null && frames < 20) frames++;
        clearSpeakers(speakersClass, speakers);
        check("the game reads who's talking and plays every frame, then stops when they do", frames, 3 + 4);
        check("a voice 10 blocks to the east, while you face south, is on your left and a bit quieter",
                (gains[0] > gains[1] * 3) + " " + (gains[0] < 0.75 * Math.sqrt(2)), "true true");
        Field nearField = speaker.getClass().getDeclaredField("near");
        nearField.setAccessible(true);
        nearField.set(speaker, false);
        float[] callGains = (float[]) place.invoke(null, voiceEar, speaker, 48);
        check("a group or whole-server voice is in the middle, the same however far", callGains[0] + " " + callGains[1], "0.75 0.75");
        java.lang.reflect.Method loadConfig = netLoader.loadClass("squidvoiceserver.VoiceServer").getDeclaredMethod("loadConfig", Path.class);
        loadConfig.setAccessible(true);
        Path voiceConfig = java.nio.file.Files.createTempDirectory("squid-voice").resolve("config").resolve("squid-voice.properties");
        String firstConfig = loadConfig.invoke(null, voiceConfig).toString();
        java.nio.file.Files.writeString(voiceConfig, "mode=world\ndistance=500\ngroups=false\n");
        check("the server owner's voice settings: written the first time, and kept in bounds", firstConfig + " | " + loadConfig.invoke(null, voiceConfig)
                + " | " + java.nio.file.Files.readString(voiceConfig).contains("world"), "Config[enabled=true, mode=0, distance=48, groups=true] | Config[enabled=true, mode=1, distance=128, groups=false] | true");

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

        // Starting Squid's built-in parts, the way the game does, must not load any class something hooks: a class
        // that loads before a hook into it is in never gets it (the Squid and Store buttons went missing that way, and
        // so did your own skin and cape). This runs last, as it starts every part again.
        List<ModInfo> builtInParts = Mods.find(Path.of("build", "builtin"), "26.3").mods();
        List<URL> startUrls = new ArrayList<>(urls);
        for (ModInfo part : builtInParts) startUrls.add(part.jar().toUri().toURL());
        SquidClassLoader startLoader = new SquidClassLoader(startUrls.toArray(URL[]::new));
        Main.start(builtInParts, startLoader, new ArrayList<>());
        List<String> loadedBeforeHooked = new ArrayList<>();
        for (String hooked : Transformers.patchedClasses()) {
            if (hooked.startsWith("net.minecraft.") && startLoader.hasLoaded(hooked)) loadedBeforeHooked.add(hooked);
        }
        check("starting every built-in part loads nothing that's hooked (" + builtInParts.size() + " parts)", loadedBeforeHooked, List.of());

        // Squid Speed's faster chunk drawing: Minecraft's chunk buffers get the quicker map, and the changed class still
        // passes Java's checks as it loads
        String uberName = "com.mojang.blaze3d.vertex.UberGpuBuffer";
        byte[] uberBytes;
        try (java.io.InputStream in = startLoader.getResourceAsStream(uberName.replace('.', '/') + ".class")) {
            uberBytes = in.readAllBytes();
        }
        String uberPatched = new String(Transformers.patch(uberName, uberBytes, startLoader), java.nio.charset.StandardCharsets.ISO_8859_1);
        check("faster chunk drawing swaps in the quicker map, and the class still loads", uberPatched.contains("Reference2ObjectOpenHashMap")
                + " " + failure(() -> Class.forName(uberName, true, startLoader)), "true ");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Stops a Speakers' sound thread, so the test doesn't play anything. */
    static void clearSpeakers(Class<?> speakersClass, Object speakers) throws ReflectiveOperationException {
        java.lang.reflect.Method clear = speakersClass.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(speakers);
    }

    /** The {0}, {1}... in a text, sorted. */
    static List<String> placeholders(String text) {
        return java.util.regex.Pattern.compile("\\{\\d}").matcher(text).results().map(r -> r.group()).sorted().toList();
    }

    /** What went wrong running it, as "Kind: message", or "" if it worked. */
    static String failure(java.util.concurrent.Callable<?> task) {
        try {
            task.call();
            return "";
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** A .sqda the way the first Squids wrote it (no CRCS or CODC): just one VARI chunk, with nothing checked. */
    static byte[] oldSqda(squid.audio.Sqda.Variant v) throws java.io.IOException {
        java.io.ByteArrayOutputStream file = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(file);
        out.writeBytes("SQDA");
        out.writeByte(1);
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream b = new java.io.DataOutputStream(body);
        b.writeUTF(v.name());
        b.writeShort(v.weight());
        b.writeInt(v.rate());
        b.writeByte(v.channels());
        b.writeLong(v.samples());
        b.writeInt(v.frames().size());
        for (byte[] f : v.frames()) {
            b.writeShort(f.length);
            b.write(f);
        }
        out.writeBytes("VARI");
        out.writeInt(body.size());
        body.writeTo(out);
        return file.toByteArray();
    }

    /** A project folder with a squid.json and one class whose init runs the code given. */
    static Path project(Path mods, String folder, String squidJson, String className, String initCode) throws java.io.IOException {
        Path project = java.nio.file.Files.createDirectories(mods.resolve(folder).resolve("src"));
        java.nio.file.Files.writeString(mods.resolve(folder).resolve("squid.json"), squidJson);
        java.nio.file.Files.writeString(project.resolve(className + ".java"), "public class " + className
                + " implements SquidMod {\n    public void init(Squid s) {\n        " + initCode + "\n    }\n}\n");
        return mods.resolve(folder);
    }

    static void deleteTree(Path folder) throws java.io.IOException {
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(folder)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(p);
        }
    }

    /** Numbers packed into bits the way Vorbis packs them: {value, how many bits}, lowest bit first. */
    static byte[] bitsLsbFirst(long[][] fields) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int current = 0;
        int used = 0;
        for (long[] field : fields) {
            for (int i = 0; i < field[1]; i++) {
                current |= (int) ((field[0] >>> i) & 1) << used;
                if (++used == 8) {
                    out.write(current);
                    current = 0;
                    used = 0;
                }
            }
        }
        if (used > 0) out.write(current);
        return out.toByteArray();
    }

    /** An Ogg file with each packet on a page of its own (Squid doesn't check the pages' CRCs, so they're left 0). */
    static byte[] ogg(byte[]... packets) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int p = 0; p < packets.length; p++) {
            byte[] packet = packets[p];
            out.writeBytes(new byte[] {'O', 'g', 'g', 'S', 0, (byte) (p == 0 ? 2 : 0)});
            out.writeBytes(new byte[8]); // granule
            out.writeBytes(new byte[] {1, 0, 0, 0}); // serial
            out.writeBytes(new byte[] {(byte) p, 0, 0, 0}); // page number
            out.writeBytes(new byte[4]); // CRC
            int laces = packet.length / 255 + 1;
            out.write(laces);
            for (int i = 0; i < laces - 1; i++) out.write(255);
            out.write(packet.length % 255);
            out.writeBytes(packet);
        }
        return out.toByteArray();
    }

    /** A WAV file's bytes: format 1 is plain samples, 3 decimals, 7 mu-law. */
    static byte[] wav(int format, int channels, int bits, byte[] data) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + data.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + data.length).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) format).putShort((short) channels).putInt(44100).putInt(44100 * channels * bits / 8)
                .putShort((short) (channels * bits / 8)).putShort((short) bits).put("data".getBytes()).putInt(data.length).put(data);
        return b.array();
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
