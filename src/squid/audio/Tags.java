package squid.audio;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A song's title and artist, read from the file itself: ID3 tags in MP3s (both kinds), Vorbis comments in FLAC and
 * Ogg files, the INFO list in WAVs, and a .sqda's own info. Anything the file doesn't say is left out, so the file's
 * name can stand in.
 */
public final class Tags {
    private Tags() {
    }

    /** "title" and "artist" (and "album" when there is one), as far as the file says. Never throws. */
    public static Map<String, String> read(byte[] d) {
        Map<String, String> tags = new LinkedHashMap<>();
        try {
            if (Sqda.is(d)) {
                Sqda s = Sqda.read(new java.io.ByteArrayInputStream(d), false);
                for (String key : new String[] {"title", "artist", "album"}) {
                    if (s.info.get(key) != null && !s.info.get(key).isBlank()) tags.put(key, s.info.get(key));
                }
            } else if (d.length > 10 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
                id3v2(d, tags);
            } else if (Audio.Wav.is(d)) {
                wav(d, tags);
            } else if (d.length > 4 && d[0] == 'f' && d[1] == 'L' && d[2] == 'a' && d[3] == 'C') {
                flac(d, tags);
            } else if (d.length > 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') {
                ogg(d, tags);
            }
            if (!tags.containsKey("title") && d.length > 128) id3v1(d, tags, d.length - 128);
        } catch (RuntimeException | java.io.IOException e) {
            // tags are only nice to have: a broken one is left out
        }
        return tags;
    }

    /** Tags from a file's last 128 bytes (an MP3's old ID3v1 tag), for when only the end was read. Never throws. */
    public static Map<String, String> readEnd(byte[] last128) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (last128.length == 128) id3v1(last128, tags, 0);
        return tags;
    }

    // ---- MP3: ID3v2 at the start (2.2, 2.3 and 2.4), ID3v1 in the last 128 bytes ----

    private static void id3v2(byte[] d, Map<String, String> tags) {
        int major = d[3];
        int size = syncsafe(d, 6);
        int end = Math.min(d.length, 10 + size);
        int at = 10;
        if ((d[5] & 0x40) != 0 && major >= 3) { // an extended header: skipped
            int ext = major == 4 ? syncsafe(d, at) : int32(d, at) + 4;
            at += ext;
        }
        boolean short2 = major == 2; // ID3v2.2: 3-letter names and 3-byte sizes
        int header = short2 ? 6 : 10;
        while (at + header <= end) {
            String id = new String(d, at, short2 ? 3 : 4, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == 0) break; // padding
            int length = short2 ? ((d[at + 3] & 0xFF) << 16) | ((d[at + 4] & 0xFF) << 8) | (d[at + 5] & 0xFF)
                    : major == 4 ? syncsafe(d, at + 4) : int32(d, at + 4);
            int body = at + header;
            if (length <= 0 || body + length > end) break;
            String key = switch (id) {
                case "TIT2", "TT2" -> "title";
                case "TPE1", "TP1" -> "artist";
                case "TALB", "TAL" -> "album";
                default -> null;
            };
            if (key != null && !tags.containsKey(key)) {
                String text = id3Text(d, body, length);
                if (!text.isBlank()) tags.put(key, text);
            }
            at = body + length;
        }
    }

    /** An ID3 text frame: an encoding byte, then the text. */
    private static String id3Text(byte[] d, int at, int length) {
        int encoding = d[at];
        Charset charset = switch (encoding) {
            case 1 -> StandardCharsets.UTF_16; // with a byte-order mark
            case 2 -> StandardCharsets.UTF_16BE;
            case 3 -> StandardCharsets.UTF_8;
            default -> StandardCharsets.ISO_8859_1;
        };
        String text = new String(d, at + 1, length - 1, charset);
        int zero = text.indexOf('\0'); // 2.4 can hold several, split by zeros: the first is enough
        return (zero >= 0 ? text.substring(0, zero) : text).strip();
    }

    private static void id3v1(byte[] d, Map<String, String> tags, int at) {
        if (d[at] != 'T' || d[at + 1] != 'A' || d[at + 2] != 'G') return;
        String title = fixed(d, at + 3, 30);
        String artist = fixed(d, at + 33, 30);
        if (!title.isEmpty()) tags.putIfAbsent("title", title);
        if (!artist.isEmpty()) tags.putIfAbsent("artist", artist);
    }

    private static String fixed(byte[] d, int at, int length) {
        String text = new String(d, at, length, StandardCharsets.ISO_8859_1);
        int zero = text.indexOf('\0');
        return (zero >= 0 ? text.substring(0, zero) : text).strip();
    }

    // ---- WAV: a LIST chunk of type INFO with INAM (name), IART (artist) and IPRD (album) ----

    private static void wav(byte[] d, Map<String, String> tags) {
        int at = 12;
        while (at + 8 <= d.length) {
            String id = new String(d, at, 4, StandardCharsets.ISO_8859_1);
            long size = little32(d, at + 4) & 0xFFFFFFFFL;
            int body = at + 8;
            if (id.equals("LIST") && body + 4 <= d.length && new String(d, body, 4, StandardCharsets.ISO_8859_1).equals("INFO")) {
                int end = (int) Math.min(d.length, body + size);
                int p = body + 4;
                while (p + 8 <= end) {
                    String sub = new String(d, p, 4, StandardCharsets.ISO_8859_1);
                    int length = little32(d, p + 4);
                    if (length < 0 || p + 8 + length > end) break;
                    String text = fixed(d, p + 8, length);
                    String key = switch (sub) {
                        case "INAM" -> "title";
                        case "IART" -> "artist";
                        case "IPRD" -> "album";
                        default -> null;
                    };
                    if (key != null && !text.isEmpty()) tags.putIfAbsent(key, text);
                    p += 8 + length + (length & 1);
                }
            }
            long next = body + size + (size & 1);
            if (next > d.length) break;
            at = (int) next;
        }
    }

    // ---- FLAC and Ogg: Vorbis comments, "TITLE=..." ----

    private static void flac(byte[] d, Map<String, String> tags) {
        int at = 4;
        while (at + 4 <= d.length) {
            boolean last = (d[at] & 0x80) != 0;
            int type = d[at] & 0x7F;
            int length = ((d[at + 1] & 0xFF) << 16) | ((d[at + 2] & 0xFF) << 8) | (d[at + 3] & 0xFF);
            if (at + 4 + length > d.length) return;
            if (type == 4) {
                comments(d, at + 4, at + 4 + length, tags);
                return;
            }
            if (last) return;
            at += 4 + length;
        }
    }

    private static void ogg(byte[] d, Map<String, String> tags) {
        // The comment header is the second packet: "\3vorbis", usually in the first few kilobytes
        byte[] mark = {3, 'v', 'o', 'r', 'b', 'i', 's'};
        int limit = Math.min(d.length - mark.length, 1 << 16);
        for (int i = 0; i < limit; i++) {
            boolean match = true;
            for (int j = 0; j < mark.length && match; j++) match = d[i + j] == mark[j];
            if (match) {
                comments(d, i + mark.length, d.length, tags);
                return;
            }
        }
    }

    /** A Vorbis comment block: the encoder's name, then "KEY=value" entries, every length 4 bytes little-endian. */
    private static void comments(byte[] d, int at, int end, Map<String, String> tags) {
        int vendor = little32(d, at);
        if (vendor < 0 || at + 4 + vendor > end) return;
        at += 4 + vendor;
        int count = little32(d, at);
        at += 4;
        for (int i = 0; i < count && at + 4 <= end; i++) {
            int length = little32(d, at);
            if (length < 0 || at + 4 + length > end) return;
            String entry = new String(d, at + 4, length, StandardCharsets.UTF_8);
            at += 4 + length;
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String key = switch (entry.substring(0, eq).toUpperCase(Locale.ROOT)) {
                case "TITLE" -> "title";
                case "ARTIST" -> "artist";
                case "ALBUM" -> "album";
                default -> null;
            };
            String value = entry.substring(eq + 1).strip();
            if (key != null && !value.isEmpty()) tags.putIfAbsent(key, value);
        }
    }

    private static int syncsafe(byte[] d, int at) {
        return ((d[at] & 0x7F) << 21) | ((d[at + 1] & 0x7F) << 14) | ((d[at + 2] & 0x7F) << 7) | (d[at + 3] & 0x7F);
    }

    private static int int32(byte[] d, int at) {
        return ((d[at] & 0xFF) << 24) | ((d[at + 1] & 0xFF) << 16) | ((d[at + 2] & 0xFF) << 8) | (d[at + 3] & 0xFF);
    }

    private static int little32(byte[] d, int at) {
        return (d[at] & 0xFF) | ((d[at + 1] & 0xFF) << 8) | ((d[at + 2] & 0xFF) << 16) | ((d[at + 3] & 0xFF) << 24);
    }
}
