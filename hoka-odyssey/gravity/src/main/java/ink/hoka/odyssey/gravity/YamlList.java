package ink.hoka.odyssey.gravity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Замена списка верхнего уровня в тексте YAML с сохранением комментариев (ModuleConfig.save() их стирает).
 * Понимает блочный список ({@code key:} и строки {@code - x} ниже) и строчный ({@code key: [a, b]}).
 */
final class YamlList {
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_.\\-]+");
    /** Без кавычек YAML прочтёт это как число, true/false или null. */
    private static final Pattern AMBIGUOUS = Pattern.compile(
        "[-+]?[0-9][0-9_.]*|(?i)(y|n|yes|no|on|off|true|false|null|[-+]?\\.inf|\\.nan)");

    private YamlList() {}

    static String replace(String text, String key, Collection<String> values) {
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
        List<String> block = new ArrayList<>();
        if (values.isEmpty()) {
            block.add(key + ": []");
        } else {
            block.add(key + ":");
            for (String v : values) {
                block.add("  - " + quote(v));
            }
        }
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith(key + ":")) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            int end = lines.size();
            while (end > 0 && lines.get(end - 1).isEmpty()) {
                end--;
            }
            lines.addAll(end, block);
            String out = String.join("\n", lines);
            return out.endsWith("\n") ? out : out + "\n";
        }
        int end = at + 1;
        while (end < lines.size() && lines.get(end).matches("\\s+-.*")) {
            end++;
        }
        lines.subList(at, end).clear();
        lines.addAll(at, block);
        return String.join("\n", lines);
    }

    static String quote(String v) {
        if (PLAIN.matcher(v).matches() && !AMBIGUOUS.matcher(v).matches()) {
            return v;
        }
        return '"' + v.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
