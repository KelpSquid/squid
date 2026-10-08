package squidjukebox;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A song's lyrics with their times, from an .lrc file next to it (song.mp3 and song.lrc), so the Jukebox can show
 * each line as it's sung, karaoke style. Nothing here needs Minecraft, so it can be tested on its own.
 *
 * An .lrc file is lines like "[01:23.45]The words", where the time is when they're sung. A line can have more than
 * one time (a chorus sung twice), "[offset:+250]" moves every line earlier by 250 milliseconds, word times like
 * "<01:23.90>" are left out, and other tags like "[ar:Artist]" are skipped.
 */
final class Lyrics {
    /** One line: when it starts, in seconds, and its words. */
    record Line(double at, String text) {
    }

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TIME = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    private Lyrics() {
    }

    /** The lines of an .lrc file, in the order they're sung. Lines without a time are left out. */
    static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        double offset = 0;
        for (String raw : lrc.split("\\r?\\n|\\r")) {
            Matcher shift = OFFSET.matcher(raw);
            if (shift.find()) offset = Integer.parseInt(shift.group(1)) / 1000.0;
            Matcher time = TIME.matcher(raw);
            List<Double> times = new ArrayList<>();
            int end = 0;
            while (time.find() && time.start() == end) { // the times come first, one after another
                double seconds = Integer.parseInt(time.group(1)) * 60 + Double.parseDouble(time.group(2).replace(':', '.'));
                times.add(seconds);
                end = time.end();
            }
            if (times.isEmpty()) continue;
            String text = WORD_TIME.matcher(raw.substring(end)).replaceAll("").strip();
            for (double at : times) lines.add(new Line(at, text));
        }
        double shiftBy = offset;
        List<Line> shifted = new ArrayList<>();
        for (Line line : lines) shifted.add(new Line(Math.max(0, line.at() - shiftBy), line.text()));
        shifted.sort(Comparator.comparingDouble(Line::at));
        return shifted;
    }

    /** Which line is being sung at this many seconds into the song: the last one that has started, or -1 before the first. */
    static int current(List<Line> lines, double seconds) {
        int low = 0;
        int high = lines.size() - 1;
        int found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).at() <= seconds) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return found;
    }

    /** The .lrc file that goes with a song: the same name, ending in .lrc. */
    static Path fileFor(Path song) {
        String name = song.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return song.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".lrc");
    }

    /** A song's lyrics, or an empty list if it has no .lrc file (or it can't be read). */
    static List<Line> forSong(Path song) {
        Path file = fileFor(song);
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 1 << 20) return List.of();
            byte[] bytes = Files.readAllBytes(file);
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                text = new String(bytes, Charset.forName("windows-1252")); // older .lrc files
            }
            if (text.startsWith("﻿")) text = text.substring(1);
            return parse(text);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
