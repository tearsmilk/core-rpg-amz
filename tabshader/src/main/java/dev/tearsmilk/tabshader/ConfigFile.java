package dev.tearsmilk.tabshader;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConfigFile {

    private ConfigFile() {
    }

    static String setBoolean(String text, String section, String key, boolean value) {
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
        Pattern head = Pattern.compile("^" + Pattern.quote(section) + ":\\s*(#.*)?$");
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (head.matcher(line).matches()) {
                start = i;
                break;
            }
            if (line.startsWith(section + ":")) {
                return null;
            }
        }
        if (start < 0) {
            StringBuilder sb = new StringBuilder(text);
            if (!text.isEmpty() && !text.endsWith("\n")) {
                sb.append(nl);
            }
            return sb.append(section).append(':').append(nl).append("  ").append(key).append(": ").append(value).append(nl).toString();
        }
        int end = lines.size();
        int indent = Integer.MAX_VALUE;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int lead = line.length() - line.stripLeading().length();
            if (lead == 0) {
                end = i;
                break;
            }
            indent = Math.min(indent, lead);
        }
        String pad = indent == Integer.MAX_VALUE ? "  " : lines.get(firstChild(lines, start, end, indent)).substring(0, indent);
        Pattern entry = Pattern.compile("^" + Pattern.quote(pad + key) + ":(?:\\s+[^#]*?)?(\\s+#.*)?\\s*$");
        String fresh = pad + key + ": " + value;
        for (int i = start + 1; i < end; i++) {
            Matcher m = entry.matcher(lines.get(i));
            if (m.matches()) {
                lines.set(i, fresh + (m.group(1) == null ? "" : m.group(1)));
                return String.join(nl, lines);
            }
        }
        lines.add(start + 1, fresh);
        return String.join(nl, lines);
    }

    private static int firstChild(List<String> lines, int start, int end, int indent) {
        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && line.length() - line.stripLeading().length() == indent) {
                return i;
            }
        }
        return start + 1;
    }

    static void saveBoolean(Path file, FileConfiguration fallback, String section, String key, boolean value) throws IOException {
        String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        String out = text == null ? null : setBoolean(text, section, key, value);
        if (out == null) {
            fallback.save(file.toFile());
            return;
        }
        if (out.equals(text)) {
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, out, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
