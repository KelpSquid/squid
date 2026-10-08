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

    static final String HELP = """
            SqdaTool makes .sqda files (Squid's own sound files) from WAV, FLAC, MP3 or Ogg Vorbis.

              java -cp squid.jar squid.audio.SqdaTool song.mp3 [song.sqda] [options]
                --quality 0-10        how good it sounds (default %d; higher is bigger)
                --loop START END      loop points, in seconds (END can be "end")
                --loop auto           find a seamless loop by listening (on whole bars if it finds the beat)
                --title "My Song"     --artist "Me"
                --bpm 120             add beat and bar cues  [--offset 0.25] [--beats-per-bar 4]
                --bpm auto            find the tempo and the first beat by listening
                --mono                mix down to one channel (Minecraft only places mono sounds in the world)

              java -cp squid.jar squid.audio.SqdaTool recipe.json [out.sqda]    everything, from a recipe
              java -cp squid.jar squid.audio.SqdaTool --info song.sqda          what's inside
              java -cp squid.jar squid.audio.SqdaTool --wav song.sqda out.wav [--variant name]

            The recipe and the file layout are explained in docs/sqda.md.""".formatted(MusicCodec.DEFAULT_QUALITY);

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Problem problem) {
            System.err.println("SqdaTool: " + problem.getMessage());
            System.exit(1);
        } catch (java.nio.file.NoSuchFileException e) {
            System.err.println("SqdaTool: there's no file called " + e.getFile());
            System.exit(1);
        } catch (IOException | RuntimeException e) {
            System.err.println("SqdaTool: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            System.exit(1);
        }
    }

    /** A mistake in how SqdaTool was asked, said plainly. */
    static final class Problem extends RuntimeException {
        Problem(String message) {
            super(message);
        }
    }

    static void run(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h") || args[0].equals("/?")) {
            System.out.println(HELP);
            return;
        }
        if (args[0].equals("--info")) {
            if (args.length < 2) throw new Problem("--info needs a .sqda file: SqdaTool --info song.sqda");
            Sqda s = Sqda.read(Files.readAllBytes(Path.of(args[1])));
            System.out.println(describe(s));
            System.out.println(details(s));
            return;
        }
        if (args[0].equals("--wav")) {
            if (args.length < 3) throw new Problem("--wav needs a .sqda file and where to save: SqdaTool --wav song.sqda out.wav");
            Sqda s = Sqda.read(Files.readAllBytes(Path.of(args[1])));
            int variant = 0;
            if (args.length >= 5 && args[3].equals("--variant")) variant = variantNamed(s, args[4]);
            writeWav(Path.of(args[2]), s.decode(variant));
            System.out.println("Saved " + args[2]);
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
        if (out.toAbsolutePath().normalize().equals(in.toAbsolutePath().normalize())) {
            throw new Problem("that would save over " + in.getFileName() + ". Give the new file another name");
        }
        Sqda made;
        if (in.toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            Object recipe;
            try {
                recipe = Json.parse(Files.readString(in));
            } catch (IllegalArgumentException e) {
                throw new Problem("the recipe isn't valid JSON: " + e.getMessage());
            }
            if (!(recipe instanceof Map<?, ?>)) throw new Problem("the recipe has to start with { and end with }");
            made = fromRecipe(Json.object(recipe), in.toAbsolutePath().getParent());
        } else {
            int quality = MusicCodec.DEFAULT_QUALITY;
            Double loopStart = null;
            Double loopEnd = null;
            boolean loopToEnd = false;
            Double bpm = null;
            double offset = 0;
            int perBar = 4;
            boolean mono = false;
            boolean autoLoop = false;
            boolean autoBpm = false;
            Map<String, String> info = new java.util.LinkedHashMap<>();
            for (int i = next; i < args.length; i++) {
                String option = args[i];
                switch (option) {
                    case "--quality" -> {
                        quality = (int) number(args, ++i, option);
                        if (quality < 0 || quality > 10) throw new Problem("--quality goes from 0 to 10");
                    }
                    case "--loop" -> {
                        if (i + 1 < args.length && args[i + 1].equalsIgnoreCase("auto")) {
                            autoLoop = true;
                            i++;
                            continue;
                        }
                        loopStart = number(args, ++i, option);
                        if (i + 1 < args.length && args[i + 1].equalsIgnoreCase("end")) {
                            loopToEnd = true;
                            i++;
                        } else {
                            loopEnd = number(args, ++i, option);
                        }
                    }
                    case "--title" -> info.put("title", value(args, ++i, option));
                    case "--artist" -> info.put("artist", value(args, ++i, option));
                    case "--bpm" -> {
                        if (i + 1 < args.length && args[i + 1].equalsIgnoreCase("auto")) {
                            autoBpm = true;
                            i++;
                            continue;
                        }
                        bpm = number(args, ++i, option);
                        if (bpm <= 0 || bpm > 1000) throw new Problem("--bpm should be a tempo like 120");
                    }
                    case "--offset" -> offset = number(args, ++i, option);
                    case "--beats-per-bar" -> perBar = (int) number(args, ++i, option);
                    case "--mono" -> mono = true;
                    default -> throw new Problem("there's no option " + option + ". Run SqdaTool --help to see them");
                }
            }
            Pcm pcm = Audio.decode(Files.readAllBytes(in));
            if (mono) pcm = toMono(pcm);
            if (autoBpm || autoLoop) {
                Analysis.Tempo tempo = Analysis.tempo(pcm);
                if (autoBpm) {
                    if (tempo == null) {
                        System.out.println("Note: no steady beat found, so no beat cues");
                    } else {
                        bpm = tempo.bpm();
                        offset = tempo.offset();
                        System.out.printf(Locale.ROOT, "Found the tempo: %s BPM, first beat at %.2f s%n", fmt(bpm), offset);
                    }
                }
                if (autoLoop) {
                    Analysis.LoopPoints found = Analysis.loop(pcm, tempo, 10);
                    if (found == null) {
                        System.out.println("Note: it's too short to find a loop in, so it loops from start to end");
                    } else {
                        loopStart = found.start();
                        loopEnd = found.end();
                        System.out.printf(Locale.ROOT, "Found a loop: %.2f s back to %.2f s%n", loopEnd, loopStart);
                    }
                }
            }
            double seconds = pcm.samples().length / (double) pcm.channels() / pcm.rate();
            if (loopToEnd) loopEnd = seconds;
            if (loopStart != null) {
                if (loopEnd <= loopStart) throw new Problem("the loop's end has to come after its start");
                if (loopStart >= seconds) throw new Problem(String.format(Locale.ROOT, "the loop starts at %.2f s, but the sound is only %.2f s long", loopStart, seconds));
                if (loopEnd > seconds + 0.01) {
                    System.out.printf(Locale.ROOT, "Note: the sound is %.2f s long, so the loop ends there instead of at %.2f s%n", seconds, loopEnd);
                    loopEnd = seconds;
                }
            }
            made = simple(pcm, quality, loopStart, loopEnd, info, bpm, offset, perBar);
        }
        for (String warning : warnings(made)) System.out.println("Note: " + warning);
        byte[] bytes = made.write();
        Files.write(out, bytes);
        System.out.println("Made " + out + " (" + bytes.length / 1024 + " KB)");
        System.out.println(describe(made));
    }

    private static String fmt(double number) {
        return number == Math.rint(number) ? String.valueOf((long) number) : String.format(Locale.ROOT, "%.2f", number);
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) throw new Problem(option + " needs a value after it");
        return args[i];
    }

    private static double number(String[] args, int i, String option) {
        String text = value(args, i, option);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new Problem(option + " needs a number, not \"" + text + "\"");
        }
    }

    private static int variantNamed(Sqda s, String name) {
        for (int i = 0; i < s.variants.size(); i++) if (s.variants.get(i).name().equals(name)) return i;
        List<String> names = new ArrayList<>();
        for (Sqda.Variant v : s.variants) names.add(v.name());
        throw new Problem("there's no variant called " + name + ". It has: " + String.join(", ", names));
    }

    /** Both channels mixed into one. */
    public static Pcm toMono(Pcm pcm) {
        if (pcm.channels() == 1) return pcm;
        int ch = pcm.channels();
        short[] in = pcm.samples();
        short[] out = new short[in.length / ch];
        for (int i = 0; i < out.length; i++) {
            int sum = 0;
            for (int c = 0; c < ch; c++) sum += in[i * ch + c];
            out[i] = (short) (sum / ch);
        }
        return new Pcm(out, 1, pcm.rate());
    }

    /** Things that work, but probably not the way the maker hoped. */
    public static List<String> warnings(Sqda s) {
        List<String> warnings = new ArrayList<>();
        boolean stereo = s.variants.stream().anyMatch(v -> v.channels() == 2);
        boolean placed = s.settings.distance() != Sqda.Settings.DEFAULT.distance() || !s.triggers.isEmpty();
        if (stereo && placed) {
            warnings.add("it's stereo, and Minecraft plays stereo sounds the same everywhere, so \"distance\" won't make it quieter far away. Use --mono for sounds that come from a place");
        }
        for (Sqda.Loop l : s.loops) {
            if (l.variant() < s.variants.size() && l.end() - l.start() < 512) {
                warnings.add("the loop is shorter than 512 samples (about 0.01 s), so it loops the whole sound instead");
            }
        }
        return warnings;
    }

    /** Every loop, cue, light and trigger, with times in seconds: what --info shows after the summary. */
    public static String details(Sqda s) {
        StringBuilder b = new StringBuilder();
        String[] kinds = {"cue", "beat", "bar", "section"};
        String[] effects = {"on", "flash", "fade", "strobe"};
        int beats = 0;
        for (Sqda.Cue c : s.cues) {
            if (c.kind() == Sqda.BEAT || c.kind() == Sqda.BAR) {
                beats++;
                continue;
            }
            double rate = s.variants.get(Math.min(c.variant(), s.variants.size() - 1)).rate();
            b.append(String.format(Locale.ROOT, "  %8.2f s  %s %s%n", c.at() / rate, c.kind() < kinds.length ? kinds[c.kind()] : "cue", c.name()));
        }
        if (beats > 0) b.append("  (").append(beats).append(" beat and bar cues)\n");
        for (Sqda.Light l : s.lights) {
            double rate = s.variants.get(Math.min(l.variant(), s.variants.size() - 1)).rate();
            b.append(String.format(Locale.ROOT, "  %8.2f s  light %s #%06X for %.2f s%s%n", l.at() / rate,
                    l.effect() < effects.length ? effects[l.effect()] : "?", l.color(), l.length() / rate, l.group().isEmpty() ? "" : " (" + l.group() + ")"));
        }
        return b.toString().stripTrailing();
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
            Path sound = folder.resolve(file);
            if (!Files.exists(sound)) throw new IOException("the recipe asks for " + file + ", but it isn't next to the recipe");
            Pcm pcm = Audio.decode(Files.readAllBytes(sound));
            if (Boolean.TRUE.equals(v.get("mono"))) pcm = toMono(pcm);
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
