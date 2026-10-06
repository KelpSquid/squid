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
        return new ModInfo(id, id, "1", "", List.of(), List.of(depends), "x", Path.of("."));
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
        List<ModInfo> mods = Mods.find(Path.of(a[1]));
        check("mods found", mods.size(), 1);
        check("mod id", mods.get(0).id(), "hello-squid");
        for (ModInfo m : mods) urls.add(m.jar().toUri().toURL());
        SquidClassLoader loader = new SquidClassLoader(urls.toArray(URL[]::new));
        Thread.currentThread().setContextClassLoader(loader);
        Main.registerBuiltInHooks();

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
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Object manager = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, splashManager);
        Object splash = splashManager.getMethod("getSplash").invoke(manager);
        Field text = splash.getClass().getDeclaredField("splash");
        text.setAccessible(true);
        Object component = text.get(splash);
        Method getString = component.getClass().getMethod("getString");
        check("Minecraft's splash says", getString.invoke(component), "Squid is working!");

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

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
