package squid;

import squid.api.ModInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * squid-report.json, a small file Squid keeps in the game folder so the launcher can show what happened.
 * Kelp reads it to show "Squid loaded 3 mods", or which mod broke and why.
 *
 * "status" is "loading" while mods start, "running" once Minecraft starts, or "failed" if a mod stopped the game.
 */
final class Report {
    private final Path file;

    Report(Path gameFolder) {
        this.file = gameFolder.resolve("squid-report.json");
    }

    void loading() {
        write("loading", List.of(), null, null);
    }

    void running(List<ModInfo> mods) {
        write("running", mods, null, null);
    }

    /** modName is the mod that was starting when it broke, or null if it wasn't any one mod's fault. */
    void failed(List<ModInfo> mods, String modName, String error) {
        write("failed", mods, modName, error);
    }

    private void write(String status, List<ModInfo> mods, String modName, String error) {
        StringBuilder json = new StringBuilder("{\n");
        json.append("    \"squid\": ").append(quote(Main.VERSION)).append(",\n");
        json.append("    \"status\": ").append(quote(status)).append(",\n");
        json.append("    \"mods\": [");
        for (int i = 0; i < mods.size(); i++) {
            ModInfo mod = mods.get(i);
            json.append(i == 0 ? "\n" : ",\n");
            json.append("        {\"id\": ").append(quote(mod.id()))
                    .append(", \"name\": ").append(quote(mod.name()))
                    .append(", \"version\": ").append(quote(mod.version())).append("}");
        }
        json.append(mods.isEmpty() ? "]" : "\n    ]");
        if (modName != null) json.append(",\n    \"mod\": ").append(quote(modName));
        if (error != null) json.append(",\n    \"error\": ").append(quote(error));
        json.append("\n}\n");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json);
        } catch (IOException e) {
            System.out.println("[Squid] Couldn't write " + file + ": " + e.getMessage());
        }
    }

    /** Text in JSON quotes, with quotes, backslashes and line breaks escaped. */
    private static String quote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
