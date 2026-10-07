package squid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A tiny JSON reader (the same one Kelp uses). Objects become Maps, arrays become Lists, and numbers become Doubles. */
public final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.skipSpace();
        if (json.pos != text.length()) throw json.error(Lang.t("extra text after the end"));
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return (List<Object>) value;
    }

    private Object value() {
        skipSpace();
        return switch (peek()) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // skip the {
        skipSpace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            String key = readString();
            skipSpace();
            if (next() != ':') throw error(Lang.t("expected :"));
            map.put(key, value());
            skipSpace();
            char c = next();
            if (c == '}') return map;
            if (c != ',') throw error(Lang.t("expected , or }"));
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // skip the [
        skipSpace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(value());
            skipSpace();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw error(Lang.t("expected , or ]"));
        }
    }

    private String readString() {
        if (next() != '"') throw error(Lang.t("expected a string"));
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case '"', '\\', '/' -> sb.append(escaped);
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (pos + 4 > text.length()) throw error(Lang.t("unexpected end"));
                    sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw error(Lang.t("unknown escape \\{0}", escaped));
            }
        }
    }

    private Double readNumber() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        if (start == pos) throw error(Lang.t("unexpected character"));
        return Double.parseDouble(text.substring(start, pos));
    }

    private Object word(String word, Object value) {
        if (!text.startsWith(word, pos)) throw error(Lang.t("expected {0}", word));
        pos += word.length();
        return value;
    }

    private char peek() {
        if (pos >= text.length()) throw error(Lang.t("unexpected end"));
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private IllegalArgumentException error(String message) {
        // Shown to players when a mod's squid.json is broken, so it's translated
        return new IllegalArgumentException(Lang.t("Bad JSON at character {0}: {1}", pos, message));
    }
}
