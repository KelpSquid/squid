package squid.audio;

import squid.Json;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Makes .sqda files. Simple, from any sound Squid can read (WAV, FLAC, MP3, Ogg Vorbis):
 *
 *   java -cp squid.jar squid.audio.SqdaTool song.mp3 [song.sqda] [--quality 6] [--loop 12.5 96] [--title "My Song"]
 *        [--artist Me] [--bpm 120 [--offset 0.25] [--beats-per-bar 4]]
 *
 * Or with everything, from a recipe (a .json file next to the sounds):
 *
 *   java -cp squid.jar squid.audio.SqdaTool song.json [song.sqda]
 *
 *   {
 *     "quality": 6,
 *     "variants": [{"file": "song.wav", "name": "main", "weight": 1}, {"file": "sting.wav", "name": "sting"}],
 *     "loop": {"start": 12.5, "end": 96.0},
 *     "info": {"title": "My Song", "artist": "Me"},
 *     "settings": {"subtitle": "Music plays", "volume": 1.0, "pitch": 1.0, "distance": 16, "stream": "auto"},
 *     "beats": {"bpm": 120, "offset": 0.25, "beatsPerBar": 4},
 *     "cues": [{"at": 30.0, "kind": "section", "name": "chorus"}],
 *     "lights": [{"at": 30.0, "length": 0.5, "color": "#FF4080", "brightness": 255, "effect": "flash", "group": "stage"}],
 *     "triggers": [{"entity": "minecraft:creeper", "when": "enters view", "sound": "variant:sting",
 *                   "distance": 24, "volume": 1.0, "pitch": 1.0, "cooldown": 100}]
 *   }
 *
 * Times are in seconds. Light effects: on, flash, fade, strobe. Trigger "when": enters view, leaves view, comes near.
 * A trigger's sound is a Minecraft sound (like "minecraft:entity.creeper.primed") or "variant:name". Loops, beats,
 * cues and lights belong to the first variant unless they say "variant": "name".
 *
 *   java -cp squid.jar squid.audio.SqdaTool --info song.sqda      what's inside
 *   java -cp squid.jar squid.audio.SqdaTool --wav song.sqda out.wav
 */
public final class SqdaTool {
    private SqdaTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Makes .sqda files. Try: java -cp squid.jar squid.audio.SqdaTool song.wav  (see SqdaTool.java for more)");
            return;
        }
        if (args[0].equals("--info")) {
            System.out.println(describe(Sqda.read(Files.readAllBytes(Path.of(args[1])))));
            return;
        }
        if (args[0].equals("--wav")) {
            writeWav(Path.of(args[2]), Sqda.read(Files.readAllBytes(Path.of(args[1]))).decode(0));
            return;
        }
        Path in = Path.of(args[0]);
        int next = 1;
        Path out;
        if (args.length > 1 && !args[1].startsWith("--")) {
            out = Path.of(args[1]);
            next = 2;
        } else {
            String name = in.getFileName().toString();
            int dot = name.lastIndexOf('.');
            out = in.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".sqda");
        }
        Sqda made;
        if (in.toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            made = fromRecipe(Json.object(Json.parse(Files.readString(in))), in.toAbsolutePath().getParent());
        } else {
            int quality = MusicCodec.DEFAULT_QUALITY;
            Double loopStart = null;
            Double loopEnd = null;
            Double bpm = null;
            double offset = 0;
            int perBar = 4;
            Map<String, String> info = new java.util.LinkedHashMap<>();
            for (int i = next; i < args.length; i++) {
                switch (args[i]) {
                    case "--quality" -> quality = Integer.parseInt(args[++i]);
                    case "--loop" -> {
                        loopStart = Double.parseDouble(args[++i]);
                        loopEnd = Double.parseDouble(args[++i]);
                    }
                    case "--title" -> info.put("title", args[++i]);
                    case "--artist" -> info.put("artist", args[++i]);
                    case "--bpm" -> bpm = Double.parseDouble(args[++i]);
                    case "--offset" -> offset = Double.parseDouble(args[++i]);
                    case "--beats-per-bar" -> perBar = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            made = simple(Audio.decode(Files.readAllBytes(in)), quality, loopStart, loopEnd, info, bpm, offset, perBar);
        }
        byte[] bytes = made.write();
        Files.write(out, bytes);
        System.out.println("Made " + out + " (" + bytes.length / 1024 + " KB)");
        System.out.println(describe(made));
    }

    /** A .sqda from one sound, with optional loop points (seconds), info, and beats from a tempo. */
    public static Sqda simple(Pcm pcm, int quality, Double loopStart, Double loopEnd, Map<String, String> info, Double bpm, double offset, int beatsPerBar) {
        Sqda s = Sqda.fromSound(pcm, quality);
        s.info.putAll(info);
        int rate = pcm.rate();
        if (loopStart != null && loopEnd != null && loopEnd > loopStart) s.loops.add(new Sqda.Loop(0, seconds(loopStart, rate), seconds(loopEnd, rate)));
        if (bpm != null && bpm > 0) beats(s, 0, bpm, offset, beatsPerBar);
        return s;
    }

    /** Beat and bar cues all the way through a variant, from its tempo. */
    static void beats(Sqda s, int variant, double bpm, double offset, int beatsPerBar) {
        Sqda.Variant v = s.variants.get(variant);
        double every = 60.0 / bpm;
        int n = 0;
        for (double t = offset; t < v.seconds(); t += every, n++) {
            s.cues.add(new Sqda.Cue(variant, seconds(t, v.rate()), n % Math.max(1, beatsPerBar) == 0 ? Sqda.BAR : Sqda.BEAT, ""));
        }
    }

    private static long seconds(double seconds, int rate) {
        return Math.max(0, Math.round(seconds * rate));
    }

    /** A .sqda from a recipe (see the top of this file). Sound files are found next to the recipe. */
    public static Sqda fromRecipe(Map<String, Object> recipe, Path folder) throws IOException {
        Sqda s = new Sqda();
        int quality = (int) number(recipe, "quality", MusicCodec.DEFAULT_QUALITY);
        List<Object> variants = Json.array(recipe.get("variants"));
        if (variants == null && recipe.get("file") != null) variants = List.of(Map.of("file", recipe.get("file")));
        if (variants == null || variants.isEmpty()) throw new IOException("the recipe has no \"variants\" (or \"file\")");
        for (Object o : variants) {
            Map<String, Object> v = Json.object(o);
            String file = text(v, "file", null);
            if (file == null) throw new IOException("a variant has no \"file\"");
            Pcm pcm = Audio.decode(Files.readAllBytes(folder.resolve(file)));
            String name = text(v, "name", s.variants.isEmpty() ? "main" : "variant" + s.variants.size());
            s.addVariant(name, (int) number(v, "weight", 1), pcm, (int) number(v, "quality", quality));
        }
        Map<String, Object> info = Json.object(recipe.get("info"));
        if (info != null) for (Map.Entry<String, Object> e : info.entrySet()) s.info.put(e.getKey(), String.valueOf(e.getValue()));
        Map<String, Object> st = Json.object(recipe.get("settings"));
        if (st != null) {
            int stream = switch (text(st, "stream", "auto").toLowerCase(Locale.ROOT)) {
                case "true", "yes", "always" -> 1;
                case "false", "no", "never" -> 2;
                default -> 0;
            };
            s.settings = new Sqda.Settings(text(st, "subtitle", ""), (float) number(st, "volume", 1), (float) number(st, "pitch", 1),
                    (int) number(st, "distance", 16), stream);
        }
        Map<String, Object> loop = Json.object(recipe.get("loop"));
        if (loop != null) {
            int v = variant(s, loop);
            int rate = s.variants.get(v).rate();
            s.loops.add(new Sqda.Loop(v, seconds(number(loop, "start", 0), rate), seconds(number(loop, "end", s.variants.get(v).seconds()), rate)));
        }
        Map<String, Object> beats = Json.object(recipe.get("beats"));
        if (beats != null) beats(s, variant(s, beats), number(beats, "bpm", 120), number(beats, "offset", 0), (int) number(beats, "beatsPerBar", 4));
        for (Object o : listOf(recipe, "cues")) {
            Map<String, Object> c = Json.object(o);
            int v = variant(s, c);
            int kind = switch (text(c, "kind", "cue").toLowerCase(Locale.ROOT)) {
                case "beat" -> Sqda.BEAT;
                case "bar" -> Sqda.BAR;
                case "section" -> Sqda.SECTION;
                default -> Sqda.CUE;
            };
            s.cues.add(new Sqda.Cue(v, seconds(number(c, "at", 0), s.variants.get(v).rate()), kind, text(c, "name", "")));
        }
        for (Object o : listOf(recipe, "lights")) {
            Map<String, Object> l = Json.object(o);
            int v = variant(s, l);
            int rate = s.variants.get(v).rate();
            int effect = switch (text(l, "effect", "on").toLowerCase(Locale.ROOT)) {
                case "flash" -> Sqda.LIGHT_FLASH;
                case "fade" -> Sqda.LIGHT_FADE;
                case "strobe" -> Sqda.LIGHT_STROBE;
                default -> Sqda.LIGHT_ON;
            };
            s.lights.add(new Sqda.Light(v, seconds(number(l, "at", 0), rate), seconds(number(l, "length", 0.5), rate), color(text(l, "color", "#FFFFFF")),
                    (int) Math.clamp(number(l, "brightness", 255), 0, 255), effect, text(l, "group", "")));
        }
        for (Object o : listOf(recipe, "triggers")) {
            Map<String, Object> t = Json.object(o);
            int when = switch (text(t, "when", "enters view").toLowerCase(Locale.ROOT).replace('_', ' ')) {
                case "leaves view" -> Sqda.LEAVES_VIEW;
                case "comes near" -> Sqda.COMES_NEAR;
                default -> Sqda.ENTERS_VIEW;
            };
            String entity = text(t, "entity", null);
            String sound = text(t, "sound", null);
            if (entity == null || sound == null) throw new IOException("a trigger needs an \"entity\" and a \"sound\"");
            if (!entity.contains(":") && !entity.equals("*")) entity = "minecraft:" + entity;
            s.triggers.add(new Sqda.Trigger(entity, when, (float) number(t, "distance", 32), sound, (float) number(t, "volume", 1),
                    (float) number(t, "pitch", 1), (int) number(t, "cooldown", 100)));
        }
        return s;
    }

    private static int variant(Sqda s, Map<String, Object> o) {
        String name = text(o, "variant", null);
        if (name == null) return 0;
        for (int i = 0; i < s.variants.size(); i++) if (s.variants.get(i).name().equals(name)) return i;
        throw new IllegalArgumentException("there's no variant called " + name);
    }

    private static List<Object> listOf(Map<String, Object> o, String key) {
        List<Object> list = Json.array(o.get(key));
        return list == null ? new ArrayList<>() : list;
    }

    private static double number(Map<String, Object> o, String key, double fallback) {
        Object v = o.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String text(Map<String, Object> o, String key, String fallback) {
        Object v = o.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static int color(String text) {
        String t = text.startsWith("#") ? text.substring(1) : text;
        try {
            return Integer.parseInt(t, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    /** What's in a .sqda, in a few lines. */
    public static String describe(Sqda s) {
        StringBuilder b = new StringBuilder();
        for (Sqda.Variant v : s.variants) {
            long bytes = 0;
            for (byte[] f : v.frames()) bytes += f.length + 2;
            b.append(String.format(Locale.ROOT, "  variant \"%s\": %.1f s, %s, %d Hz, %.0f kbps, weight %d%n", v.name(), v.seconds(),
                    v.channels() == 2 ? "stereo" : "mono", v.rate(), bytes * 8 / Math.max(0.001, v.seconds()) / 1000, v.weight()));
        }
        for (Map.Entry<String, String> e : s.info.entrySet()) b.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        for (Sqda.Loop l : s.loops) {
            int rate = s.variants.get(Math.min(l.variant(), s.variants.size() - 1)).rate();
            b.append(String.format(Locale.ROOT, "  loops from %.2f s back to %.2f s%n", l.end() / (double) rate, l.start() / (double) rate));
        }
        if (!s.settings.equals(Sqda.Settings.DEFAULT)) b.append("  settings: ").append(s.settings).append('\n');
        if (!s.cues.isEmpty()) b.append("  ").append(s.cues.size()).append(" cues\n");
        if (!s.lights.isEmpty()) b.append("  ").append(s.lights.size()).append(" light cues\n");
        for (Sqda.Trigger t : s.triggers) b.append("  when ").append(t.entity()).append(' ')
                .append(t.event() == Sqda.ENTERS_VIEW ? "enters view" : t.event() == Sqda.LEAVES_VIEW ? "leaves view" : "comes near")
                .append(": play ").append(t.sound()).append('\n');
        return b.toString().stripTrailing();
    }

    public static void writeWav(Path out, Pcm p) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + p.samples().length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + p.samples().length * 2).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1)
                .putShort((short) p.channels()).putInt(p.rate()).putInt(p.rate() * p.channels() * 2).putShort((short) (p.channels() * 2)).putShort((short) 16)
                .put("data".getBytes()).putInt(p.samples().length * 2);
        for (short s : p.samples()) b.putShort(s);
        Files.write(out, b.array());
    }
}
