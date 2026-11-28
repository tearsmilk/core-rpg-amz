package ink.hoka.odyssey.gravity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Запись раздела black-holes в текст config.yml с сохранением остальных комментариев (ModuleConfig.save() их
 * стирает). Раздел — от строки {@code black-holes:} до следующей строки с начала (ключ или комментарий в
 * колонке 0); пустые строки перед следующим разделом остаются. Комментарии внутри раздела не сохраняются —
 * описывайте поля над {@code black-holes:}.
 */
final class HoleYaml {
    static final String KEY = "black-holes";
    /** Допустимое имя дыры (без точек — путь в конфиге через точку). */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");

    private HoleYaml() {}

    static String replace(String text, Map<String, List<BlackHole>> holes) {
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
        List<String> block = block(holes);
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(KEY + ":")) {
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
        while (end < lines.size() && (lines.get(end).isEmpty() || Character.isWhitespace(lines.get(end).charAt(0)))) {
            end++;
        }
        while (end > at + 1 && lines.get(end - 1).isBlank()) {
            end--;
        }
        lines.subList(at, end).clear();
        lines.addAll(at, block);
        return String.join("\n", lines);
    }

    static List<String> block(Map<String, List<BlackHole>> holes) {
        List<String> out = new ArrayList<>();
        boolean any = holes.values().stream().anyMatch(l -> !l.isEmpty());
        if (!any) {
            out.add(KEY + ": {}");
            return out;
        }
        out.add(KEY + ":");
        for (var e : holes.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            out.add("  " + quoted(e.getKey()) + ":");
            for (BlackHole h : e.getValue()) {
                out.add("    " + quoted(h.name()) + ": {x: " + num(h.x()) + ", y: " + num(h.y()) + ", z: " + num(h.z())
                    + ", horizon: " + num(h.horizon()) + ", reach: " + num(h.reach()) + ", pull: " + num(h.pull())
                    + ", max-speed: " + num(h.maxSpeed()) + h.disk().yaml() + "}");
            }
        }
        return out;
    }

    /** Ключ всегда в кавычках: иначе YAML прочтёт {@code 1} как число, {@code on}/{@code no} — как true/false. */
    static String quoted(String v) {
        return '"' + v.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** Число без лишних нулей, до сотых. */
    static String num(double v) {
        if (!Double.isFinite(v)) {
            return "0";
        }
        BigDecimal b = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        String s = b.toPlainString();
        return s.equals("-0") ? "0" : s;
    }
}
