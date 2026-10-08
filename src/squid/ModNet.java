package squid;

import squid.api.Sender;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Mods' own messages between the copy of a mod in the game and its copy on a Squid server (see
 * {@link squid.api.Squid#send}). They ride on Squid Net's one "squid:mods" channel, written as: the mod's id, the
 * channel name the mod picked, and the value (text, numbers, true/false, bytes, lists and maps of those).
 *
 * A mod can't flood anyone: a message is at most {@link #MAX_BYTES} long, and each mod (and on a server, each player
 * for each mod) can send about {@link #PER_SECOND} a second. What's over is held back (dropped) and said once in the log.
 * Squid Net does the sending ({@link #attach}) and hands what arrives to {@link #fromServer} and {@link #fromPlayer}.
 */
public final class ModNet {
    private ModNet() {
    }

    /** Biggest message a mod can send, in bytes (Minecraft allows a little more for messages to a server). */
    public static final int MAX_BYTES = 30_000;
    /** How many messages a second each mod can send, and how many more it can send in a burst. */
    static final int PER_SECOND = 40;
    static final int BURST = 80;
    /** How many bytes a second each mod can send, and in a burst. */
    static final int BYTES_PER_SECOND = 64 * 1024;
    static final int BYTES_BURST = 128 * 1024;
    private static final int MAX_DEPTH = 16;

    /** How Squid Net sends messages and finds players. Squid Net (a built-in part, built with Minecraft) gives one. */
    public interface Transport {
        /** Sends to the server. False if the game isn't on a server with Squid. */
        boolean toServer(byte[] message);

        /** Sends to one player (a ServerPlayer), if their game has Squid. */
        void toPlayer(Object player, byte[] message);

        /** Every player on this server whose game has Squid. */
        Collection<?> players();

        /** The player with this name or id on this server, or null. */
        Object findPlayer(Object nameOrId);

        /** A player's name. */
        String nameOf(Object player);

        /** A player's id, to keep their limits apart. */
        java.util.UUID idOf(Object player);

        /** Runs it on the game's own thread (or the server's, for a message from a player). */
        void onMainThread(Object player, Runnable task);
    }

    private static volatile Transport transport;

    /** Squid Net plugs itself in here as it starts. */
    public static void attach(Transport t) {
        transport = t;
    }

    // ---- Who listens ----

    private record Listener(String modId, BiConsumer<Sender, Object> handler, AtomicInteger failures) {
    }

    /** By mod id, then channel. */
    private static final Map<String, Map<String, List<Listener>>> LISTENERS = new ConcurrentHashMap<>();

    /** A mod listens on one of its channels. */
    public static void on(String modId, String channel, BiConsumer<Sender, Object> handler) {
        checkChannel(channel);
        LISTENERS.computeIfAbsent(modId, m -> new ConcurrentHashMap<>())
                .computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(new Listener(modId, handler, new AtomicInteger()));
    }

    /** A mod is reloaded or turned off: it stops listening. */
    static void remove(String modId) {
        LISTENERS.remove(modId);
        OUTGOING.keySet().removeIf(key -> key.equals(modId) || key.startsWith(modId + " to "));
    }

    // ---- Sending ----

    /** From the game to the copy of the mod on the server. False if it wasn't sent (no Squid server, or too many). */
    public static boolean send(String modId, String channel, Object value) {
        byte[] message = encode(modId, channel, value);
        Transport t = transport;
        if (t == null || !allowed(OUTGOING, modId, message.length, modId)) return false;
        return t.toServer(message);
    }

    /** From the server to the copy of the mod in one player's game (a ServerPlayer, a name, or a Sender). */
    public static boolean sendTo(String modId, Object player, String channel, Object value) {
        byte[] message = encode(modId, channel, value);
        Transport t = transport;
        if (t == null) return false;
        Object found = player instanceof Sender s ? s.player() : t.findPlayer(player);
        if (found == null) return false;
        // Each player has limits of their own, so a mod can keep 20 players up to date at once
        if (!allowed(OUTGOING, modId + " to " + t.idOf(found), message.length, modId)) return false;
        t.toPlayer(found, message);
        return true;
    }

    /** From the server to every player's game. Gives back how many players it went to. */
    public static int sendToAll(String modId, String channel, Object value) {
        byte[] message = encode(modId, channel, value);
        Transport t = transport;
        if (t == null || !allowed(OUTGOING, modId + " to everyone", message.length, modId)) return 0;
        int sent = 0;
        for (Object player : t.players()) {
            t.toPlayer(player, message);
            sent++;
        }
        return sent;
    }

    // ---- Arriving ----

    /** A message from the server, in the game. */
    public static void fromServer(byte[] message) {
        deliver(null, message);
    }

    /** A message from a player's game, on the server (on the network thread: it's checked here and handled later). */
    public static void fromPlayer(Object player, byte[] message) {
        Transport t = transport;
        if (t == null || message.length > MAX_BYTES + 512) return;
        String key = t.idOf(player) + "";
        if (!allowed(INCOMING, key, message.length, t.nameOf(player))) return;
        deliver(player, message);
    }

    private static void deliver(Object player, byte[] message) {
        Decoded decoded;
        try {
            decoded = decode(message);
        } catch (RuntimeException broken) {
            System.out.println("[Squid] A mod message arrived broken, so it was thrown away: " + broken.getMessage());
            return;
        }
        Map<String, List<Listener>> channels = LISTENERS.get(decoded.modId());
        List<Listener> listeners = channels == null ? null : channels.get(decoded.channel());
        if (listeners == null || listeners.isEmpty()) return; // this side doesn't have that mod, or isn't listening
        Transport t = transport;
        Sender from = player == null ? new Sender(decoded.modId(), "server", null, null)
                : new Sender(decoded.modId(), t.nameOf(player), player, t.idOf(player));
        Runnable run = () -> {
            for (Listener listener : listeners) {
                if (listener.failures().get() >= 3) continue;
                try {
                    listener.handler().accept(from, decoded.value());
                } catch (RuntimeException | LinkageError e) {
                    int failures = listener.failures().incrementAndGet();
                    if (failures == 1) {
                        System.out.println("[Squid] A message handler from " + listener.modId() + " failed:");
                        e.printStackTrace(System.out);
                    }
                    if (failures == 3) {
                        System.out.println("[Squid] Turned off a message handler from " + listener.modId() + " because it kept failing.");
                        Main.hookProblem(listener.modId(), Main.describe(e));
                    }
                }
            }
        };
        if (t != null) t.onMainThread(player, run);
        else run.run();
    }

    // ---- Limits ----

    /** A bucket of messages and bytes that fills up again over time (a "token bucket"). */
    private static final class Bucket {
        double messages = BURST;
        double bytes = BYTES_BURST;
        long last = System.nanoTime();
        long warnedAt;
        int heldBack;
    }

    private static final Map<String, Bucket> OUTGOING = new ConcurrentHashMap<>(); // by mod id
    private static final Map<String, Bucket> INCOMING = new ConcurrentHashMap<>(); // by player id (on a server)

    /** Whether one more message of this size fits in the limits. Held back ones are said in the log, at most every 10 seconds. */
    static boolean allowed(Map<String, Bucket> buckets, String key, int size, String who) {
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket());
        synchronized (b) {
            long now = System.nanoTime();
            double seconds = (now - b.last) / 1e9;
            b.last = now;
            b.messages = Math.min(BURST, b.messages + seconds * PER_SECOND);
            b.bytes = Math.min(BYTES_BURST, b.bytes + seconds * BYTES_PER_SECOND);
            if (b.messages >= 1 && b.bytes >= size) {
                b.messages -= 1;
                b.bytes -= size;
                return true;
            }
            b.heldBack++;
            if (now - b.warnedAt > 10_000_000_000L || b.warnedAt == 0) {
                b.warnedAt = now;
                System.out.println("[Squid] Held back " + b.heldBack + " mod message(s) from " + who + ": more than " + PER_SECOND
                        + " a second (or " + BYTES_PER_SECOND / 1024 + " KB a second) is too many.");
                b.heldBack = 0;
            }
            return false;
        }
    }

    /** Forgets the limits used so far. Only tests need this. */
    static void resetLimits() {
        OUTGOING.clear();
        INCOMING.clear();
    }

    // ---- Writing and reading messages ----

    record Decoded(String modId, String channel, Object value) {
    }

    private static final int NULL = 0, TRUE = 1, FALSE = 2, INT = 3, LONG = 4, DOUBLE = 5, TEXT = 6, BYTES = 7, LIST = 8, MAP = 9, FLOAT = 10;

    private static void checkChannel(String channel) {
        if (channel == null || channel.isEmpty() || channel.length() > 64) {
            throw new IllegalArgumentException(Lang.t("a message channel needs a name of 1 to 64 letters, like \"score\""));
        }
    }

    /** A message, ready to send. Something that can't be sent, or is too big, is explained in plain words. */
    public static byte[] encode(String modId, String channel, Object value) {
        checkChannel(channel);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        out.write(1); // the message layout's version, so a future Squid can tell
        text(out, modId);
        text(out, channel);
        value(out, value, 0);
        if (out.size() > MAX_BYTES) {
            throw new IllegalArgumentException(Lang.t("that message is too big to send ({0} bytes). The most is {1}", out.size(), MAX_BYTES));
        }
        return out.toByteArray();
    }

    private static void value(ByteArrayOutputStream out, Object value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException(Lang.t("that message has lists or maps inside each other too deep to send"));
        if (out.size() > MAX_BYTES) {
            throw new IllegalArgumentException(Lang.t("that message is too big to send ({0} bytes). The most is {1}", out.size(), MAX_BYTES));
        }
        if (value == null) {
            out.write(NULL);
        } else if (value instanceof Boolean b) {
            out.write(b ? TRUE : FALSE);
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            out.write(INT);
            number(out, ((Number) value).intValue(), 4);
        } else if (value instanceof Long l) {
            out.write(LONG);
            number(out, l, 8);
        } else if (value instanceof Float f) {
            out.write(FLOAT);
            number(out, Float.floatToIntBits(f), 4);
        } else if (value instanceof Number n) {
            out.write(DOUBLE);
            number(out, Double.doubleToLongBits(n.doubleValue()), 8);
        } else if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            out.write(TEXT);
            text(out, value instanceof Enum<?> e ? e.name() : value.toString());
        } else if (value instanceof byte[] bytes) {
            out.write(BYTES);
            fits(out, bytes.length);
            size(out, bytes.length);
            out.write(bytes, 0, bytes.length);
        } else if (value instanceof Map<?, ?> map) {
            out.write(MAP);
            size(out, map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                text(out, String.valueOf(entry.getKey()));
                value(out, entry.getValue(), depth + 1);
            }
        } else if (value instanceof Collection<?> list) {
            out.write(LIST);
            size(out, list.size());
            for (Object item : list) value(out, item, depth + 1);
        } else if (value instanceof Object[] array) {
            value(out, java.util.Arrays.asList(array), depth);
        } else if (value.getClass().isArray()) { // int[], double[]...
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) items.add(java.lang.reflect.Array.get(value, i));
            value(out, items, depth);
        } else {
            throw new IllegalArgumentException(Lang.t("a message can't carry {0}. Send text, numbers, true/false, or lists and maps of those",
                    value.getClass().getSimpleName()));
        }
    }

    private static void number(ByteArrayOutputStream out, long bits, int bytes) {
        for (int i = bytes - 1; i >= 0; i--) out.write((int) (bits >>> (i * 8)));
    }

    private static void size(ByteArrayOutputStream out, int n) {
        while ((n & ~0x7F) != 0) { // 7 bits at a time, so small sizes take one byte
            out.write((n & 0x7F) | 0x80);
            n >>>= 7;
        }
        out.write(n);
    }

    private static void text(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        fits(out, bytes.length);
        size(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /** Stops before writing something that can't fit, saying how big the message would have been. */
    private static void fits(ByteArrayOutputStream out, int more) {
        if (out.size() + more > MAX_BYTES) {
            throw new IllegalArgumentException(Lang.t("that message is too big to send ({0} bytes). The most is {1}", out.size() + more, MAX_BYTES));
        }
    }

    /** Reads a message. Anything broken or made-up (a size past the end, too deep) throws instead of using up memory. */
    static Decoded decode(byte[] message) {
        ByteBuffer in = ByteBuffer.wrap(message);
        try {
            int version = in.get();
            if (version != 1) throw new IllegalArgumentException("it's from a newer Squid (layout " + version + ")");
            String modId = readText(in);
            String channel = readText(in);
            Object value = readValue(in, 0);
            if (in.hasRemaining()) throw new IllegalArgumentException("it has extra bytes at the end");
            return new Decoded(modId, channel, value);
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("it ends too soon");
        }
    }

    private static Object readValue(ByteBuffer in, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("it's too deep");
        int tag = in.get();
        return switch (tag) {
            case NULL -> null;
            case TRUE -> true;
            case FALSE -> false;
            case INT -> in.getInt();
            case LONG -> in.getLong();
            case FLOAT -> in.getFloat();
            case DOUBLE -> in.getDouble();
            case TEXT -> readText(in);
            case BYTES -> {
                byte[] bytes = new byte[readSize(in)];
                in.get(bytes);
                yield bytes;
            }
            case LIST -> {
                int n = readSize(in);
                List<Object> list = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) list.add(readValue(in, depth + 1));
                yield list;
            }
            case MAP -> {
                int n = readSize(in);
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) map.put(readText(in), readValue(in, depth + 1));
                yield map;
            }
            default -> throw new IllegalArgumentException("it has a value Squid doesn't know (" + tag + ")");
        };
    }

    private static int readSize(ByteBuffer in) {
        int n = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.get() & 0xFF;
            n |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                // Every item takes at least a byte, so a size bigger than what's left is made up
                if (n < 0 || n > in.remaining()) throw new IllegalArgumentException("it claims more than it has");
                return n;
            }
        }
        throw new IllegalArgumentException("it has a broken size");
    }

    private static String readText(ByteBuffer in) {
        byte[] bytes = new byte[readSize(in)];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
