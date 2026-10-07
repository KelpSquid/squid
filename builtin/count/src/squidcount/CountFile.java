package squidcount;

import squid.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * squid-count.json in Kelp's folder: each player's Squid Count (points) and which advancements earned them,
 * so each advancement only ever counts once. Kelp reads it too, to show the count.
 *
 * <pre>
 * {"players": {"&lt;uuid&gt;": {"name": "Sam", "points": 85, "earned": ["minecraft:story/mine_stone", ...]}}}
 * </pre>
 */
public final class CountFile {
    /** One player's count. */
    public static final class Player {
        public String name;
        public int points;
        public final Set<String> earned = new LinkedHashSet<>();
    }

    private final Path file;
    private final Map<String, Player> players = new LinkedHashMap<>();

    private CountFile(Path file) {
        this.file = file;
    }

    /** Reads the file, or starts an empty one if it isn't there (or can't be read). */
    public static CountFile load(Path file) {
        CountFile count = new CountFile(file);
        try {
            if (Files.exists(file)) {
                Map<String, Object> root = Json.object(Json.parse(Files.readString(file)));
                Map<String, Object> all = root == null ? null : Json.object(root.get("players"));
                if (all != null) {
                    for (Map.Entry<String, Object> entry : all.entrySet()) {
                        Map<String, Object> p = Json.object(entry.getValue());
                        Player player = new Player();
                        player.name = p.get("name") instanceof String s ? s : "";
                        player.points = p.get("points") instanceof Double d ? d.intValue() : 0;
                        if (p.get("earned") != null) for (Object id : Json.array(p.get("earned"))) player.earned.add(String.valueOf(id));
                        count.players.put(entry.getKey(), player);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[Squid Count] Couldn't read " + file + ", starting fresh: " + e.getMessage());
        }
        return count;
    }

    public Player player(String uuid) {
        return players.computeIfAbsent(uuid, id -> new Player());
    }

    /**
     * Adds an advancement to a player's count if they haven't earned it before.
     * Gives back the points it was worth, or 0 if it already counted.
     */
    public int earn(String uuid, String name, String advancement, int points) {
        Player player = player(uuid);
        player.name = name;
        if (!player.earned.add(advancement)) return 0;
        player.points += points;
        return points;
    }

    /** Saves the file, writing a .part first so a crash halfway never loses anyone's count. */
    public void save() throws IOException {
        StringBuilder json = new StringBuilder("{\n    \"players\": {");
        boolean first = true;
        for (Map.Entry<String, Player> entry : players.entrySet()) {
            Player p = entry.getValue();
            json.append(first ? "\n" : ",\n");
            first = false;
            List<String> earned = new ArrayList<>();
            for (String id : p.earned) earned.add(quote(id));
            json.append("        ").append(quote(entry.getKey())).append(": {\"name\": ").append(quote(p.name))
                    .append(", \"points\": ").append(p.points)
                    .append(", \"earned\": [").append(String.join(", ", earned)).append("]}");
        }
        json.append(first ? "}\n}\n" : "\n    }\n}\n");
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(part, json);
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** How many points an advancement is worth: task 10, goal 25, challenge 50, like gamerscore. */
    public static int points(String type) {
        return switch (type) {
            case "goal" -> 25;
            case "challenge" -> 50;
            default -> 10;
        };
    }

    private static String quote(String text) {
        return "\"" + (text == null ? "" : text).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
