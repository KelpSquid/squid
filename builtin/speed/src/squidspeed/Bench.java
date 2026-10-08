package squidspeed;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import squid.api.Squid;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Squid's speed test, for working on Squid itself: start the game with -Dsquid.bench=results.json and a world to open
 * (Kelp's Play World), and it flies the same path every time while it times every frame, then writes the results and
 * closes the game. The window stays hidden, so it can run in the background.
 *
 * -Dsquid.bench.seconds (60) is how long it measures, after -Dsquid.bench.warmup (20) seconds of flying first, so the
 * chunks load and Java warms up. -Dsquid.bench.path=circle (the default) flies circles around where the world starts,
 * over chunks already loaded, which measures drawing the world; "line" flies straight into new chunks, which measures
 * loading them (and the hitches that come with it).
 */
final class Bench {
    private static final int SDL_WINDOW_HIDDEN = 0x8;
    private static final double SPEED = 0.6; // blocks a tick: 12 a second, about as fast as flying in creative

    private static long[] frames = new long[1 << 18];
    private static int frameCount;
    private static long lastFrame;
    private static long presentStart, lastPresent, presentTotal;
    // Windows holds back a hidden window's frames now and then (about half a second, once a second): showing the frame
    // takes the whole time. Those frames are left out of the "clean" numbers, which are what a visible window gets.
    private static long[] cleanFrames = new long[1 << 18];
    private static int cleanCount;
    private static long heldBackNanos;
    private static volatile boolean measuring;
    private static final StringBuilder hitches = new StringBuilder(); // frames over 50 ms: when (seconds in) and how long

    private static int ticks;
    private static int stage; // 0 waiting for the world, 1 warming up, 2 measuring, 3 saving a clip, 4 done
    private static double startX, startZ, height;
    private static long worldUptimeMs; // from Java starting to standing in the world
    private static long startNanos, gcCountBefore, gcTimeBefore, cpuBefore, mainCpuBefore, maxHeap;

    private Bench() {
    }

    static boolean on() {
        return System.getProperty("squid.bench") != null;
    }

    static void install(Squid squid) {
        // Hidden window: Minecraft never shows the window itself, it's made visible, so it's made hidden instead
        squid.patch("com.mojang.blaze3d.platform.Window", node -> {
            for (MethodNode method : node.methods) {
                if (!method.name.equals("createWindow")) continue;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long flags && (flags & 0x2000) != 0) {
                        ldc.cst = flags | SDL_WINDOW_HIDDEN;
                    }
                }
            }
        });
        squid.atStart("net.minecraft.client.Minecraft", "renderFrame", call -> frame());
        // How long showing each frame takes (handing it to the screen), to tell slow drawing from waiting on Windows
        squid.atStart("com.mojang.renderpearl.backend.opengl.GlSurface", "present", call -> presentStart = System.nanoTime());
        squid.atEnd("com.mojang.renderpearl.backend.opengl.GlSurface", "present", call -> {
            lastPresent = System.nanoTime() - presentStart;
            if (measuring) presentTotal += lastPresent;
        });
        squid.onTick(Bench::tick);
    }

    private static void frame() {
        long now = System.nanoTime();
        if (measuring && frameCount < frames.length) {
            long took = now - lastFrame;
            frames[frameCount++] = took;
            if (lastPresent > 100_000_000L) heldBackNanos += took;
            else if (cleanCount < cleanFrames.length) cleanFrames[cleanCount++] = took;
            if (took > 50_000_000L && hitches.length() < 4000) {
                hitches.append(String.format(Locale.ROOT, "%.2fs:%dms(%d) ", (now - startNanos) / 1e9, took / 1_000_000, lastPresent / 1_000_000));
            }
        }
        lastFrame = now;
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (stage == 4 || mc.player == null || mc.level == null) return;
        if (stage == 3) {
            // -Dsquid.bench.clip: F8 was pressed as measuring ended; close once the clip's picture is written (after its video)
            ticks++;
            boolean saved;
            try (java.util.stream.Stream<Path> files = Files.list(squid.Main.gameFolder().resolve("clips"))) {
                saved = files.anyMatch(f -> f.toString().endsWith(".jpg"));
            } catch (Exception e) {
                saved = false;
            }
            if (saved || ticks > 1200) {
                stage = 4;
                mc.stop();
            }
            return;
        }
        ticks++;
        if (stage == 0) {
            stage = 1;
            ticks = 0;
            worldUptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
            startX = mc.player.getX();
            startZ = mc.player.getZ();
            height = 140;
            IntegratedServer server = mc.getSingleplayerServer();
            java.util.UUID id = mc.player.getUUID();
            if (server != null) server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) player.setGameMode(GameType.SPECTATOR);
            });
            System.out.println("[Squid Bench] In the world at " + (int) startX + ", " + (int) startZ + ". Warming up.");
        }
        int warmup = Integer.getInteger("squid.bench.warmup", 20) * 20;
        mc.player.getAbilities().flying = true;
        mc.player.setDeltaMovement(0, 0, 0);
        if ("line".equals(System.getProperty("squid.bench.path"))) {
            double along = stage == 2 ? ticks * SPEED : 0;
            mc.player.setPos(startX + along, height, startZ);
            mc.player.setYRot((float) (-90 + 25 * Math.sin(ticks / 60.0)));
        } else {
            // Round a circle 96 blocks out from the start, once every 30 seconds, looking the way it goes
            int t = stage == 2 ? ticks + warmup : ticks;
            double angle = t * Math.PI * 2 / 600;
            mc.player.setPos(startX + 96 * Math.cos(angle), height, startZ + 96 * Math.sin(angle));
            mc.player.setYRot((float) Math.toDegrees(angle));
        }
        mc.player.setXRot(20);
        if (stage == 1 && ticks >= warmup) {
            stage = 2;
            ticks = 0;
            startNanos = System.nanoTime();
            gcCountBefore = gcCount();
            gcTimeBefore = gcTime();
            cpuBefore = cpuNanos();
            mainCpuBefore = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime(); // ticks run on the game's main thread
            lastFrame = startNanos;
            measuring = true;
            System.out.println("[Squid Bench] Measuring.");
        } else if (stage == 2) {
            Runtime rt = Runtime.getRuntime();
            maxHeap = Math.max(maxHeap, rt.totalMemory() - rt.freeMemory());
            if (ticks >= Integer.getInteger("squid.bench.seconds", 60) * 20) {
                measuring = false;
                finish();
                if (System.getProperty("squid.bench.clip") != null) {
                    stage = 3;
                    ticks = 0;
                    net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD
                            .getOrCreate(com.mojang.blaze3d.platform.InputConstants.KEY_F8));
                } else {
                    stage = 4;
                    mc.stop();
                }
            }
        }
    }

    private static void finish() {
        double seconds = (System.nanoTime() - startNanos) / 1e9;
        long[] times = Arrays.copyOf(frames, frameCount);
        Arrays.sort(times);
        int n = times.length;
        long total = 0;
        for (long t : times) total += t;
        long worst = 0;
        int worstCount = Math.max(1, n / 100);
        for (int i = n - worstCount; i < n; i++) worst += times[i];
        long tenthCount = Math.max(1, n / 1000);
        long tenth = 0;
        for (int i = n - (int) tenthCount; i < n; i++) tenth += times[i];
        int over50 = 0;
        for (long t : times) if (t > 50_000_000L) over50++;
        StringBuilder json = new StringBuilder("{\n");
        long[] clean = Arrays.copyOf(cleanFrames, cleanCount);
        Arrays.sort(clean);
        int c = Math.max(1, clean.length);
        long cleanWorst = 0;
        for (int i = clean.length - Math.max(1, c / 100); i < clean.length; i++) cleanWorst += clean[i];
        field(json, "cleanFps", clean.length / (seconds - heldBackNanos / 1e9));
        field(json, "cleanLow1", 1e9 / (cleanWorst / (double) Math.max(1, c / 100)));
        field(json, "cleanP99ms", clean.length == 0 ? 0 : clean[(int) (clean.length * 0.99)] / 1e6);
        field(json, "heldBackSeconds", heldBackNanos / 1e9);
        field(json, "secondsToWorld", worldUptimeMs / 1000.0);
        field(json, "frames", n);
        field(json, "seconds", seconds);
        field(json, "fps", n / seconds);
        field(json, "low1", 1e9 / (worst / (double) worstCount));
        field(json, "low01", 1e9 / (tenth / (double) tenthCount));
        field(json, "p50ms", times[n / 2] / 1e6);
        field(json, "p99ms", times[(int) (n * 0.99)] / 1e6);
        field(json, "maxms", times[n - 1] / 1e6);
        field(json, "over50ms", over50);
        field(json, "gcCount", gcCount() - gcCountBefore);
        field(json, "gcMs", gcTime() - gcTimeBefore);
        field(json, "cpuSeconds", (cpuNanos() - cpuBefore) / 1e9);
        // The main thread's own work for each frame: steadier than frames a second when other programs are busy too
        field(json, "mainCpuMsPerFrame", (ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - mainCpuBefore) / 1e6 / n);
        field(json, "maxHeapMb", maxHeap / 1048576.0);
        field(json, "meanMs", total / (double) n / 1e6);
        field(json, "presentMsPerFrame", presentTotal / 1e6 / n);
        json.append("  \"hitches\": \"").append(hitches.toString().strip()).append("\"\n}\n");
        try {
            Files.writeString(Path.of(System.getProperty("squid.bench")), json);
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }
        System.out.println("[Squid Bench] Done:\n" + json);
    }

    private static void field(StringBuilder json, String name, double value) {
        json.append("  \"").append(name).append("\": ").append(String.format(Locale.ROOT, "%.3f", value)).append(",\n");
    }

    private static long gcCount() {
        long sum = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) sum += Math.max(0, gc.getCollectionCount());
        return sum;
    }

    private static long gcTime() {
        long sum = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) sum += Math.max(0, gc.getCollectionTime());
        return sum;
    }

    private static long cpuNanos() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getProcessCpuTime();
        }
        return 0;
    }
}
