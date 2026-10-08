package squidmods;

import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reading mod files the same way Squid's loader does, so the Mods screen agrees with it: a .squid zipped by hand
 * (the whole folder inside, or Windows' \ between folders), and squid.json text with a BOM or in old Windows text.
 */
final class Packed {
    private Packed() {
    }

    /** Where a .squid file's squid.json is: "" at the top, or "MegaMod/" when a folder was zipped. Null if none. */
    static String root(ZipFile zip) {
        String nested = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName().replace('\\', '/');
            if (name.equals("squid.json")) return "";
            int slash = name.indexOf('/');
            if (slash > 0 && name.substring(slash + 1).equals("squid.json")) nested = name.substring(0, slash + 1);
        }
        return nested;
    }

    /** The entry with this name, however its folders are written. */
    static ZipEntry entry(ZipFile zip, String name) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().replace('\\', '/').equals(name)) return entry;
        }
        return null;
    }

    /**
     * A file inside a mod's zip, read no further than `most` bytes: a made-up file could unpack to gigabytes, and the
     * screen would run out of memory reading it.
     */
    static byte[] small(java.io.InputStream in, int most) throws java.io.IOException {
        try (in) {
            byte[] bytes = in.readNBytes(most + 1);
            if (bytes.length > most) throw new java.io.IOException("too big");
            return bytes;
        }
    }

    /** Text from a mod's file: UTF-8 (with or without a BOM), or else Windows' own encoding. */
    static String text(byte[] bytes) {
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes, start, bytes.length - start)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, start, bytes.length - start, java.nio.charset.Charset.forName("windows-1252"));
        }
    }
}
