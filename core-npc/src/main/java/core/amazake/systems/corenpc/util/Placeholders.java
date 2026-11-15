package core.amazake.systems.corenpc.util;

import java.util.Map;
import java.util.function.UnaryOperator;

/** Подстановка {ключ} в строку. Неизвестные ключи остаются как есть. Чистая логика. */
public final class Placeholders {

    private Placeholders() {
    }

    /** Подставить значения как есть (для консольных команд). */
    public static String apply(final String template, final Map<String, String> values) {
        return apply(template, values, UnaryOperator.identity());
    }

    /**
     * Подставить значения, пропустив каждое через escape (для MiniMessage — экранирование тегов, чтобы ник игрока
     * не вставил свои теги). {{ — литерал {.
     */
    public static String apply(final String template, final Map<String, String> values, final UnaryOperator<String> escape) {
        if (template == null || template.indexOf('{') < 0) {
            return template;
        }
        final StringBuilder sb = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            final char c = template.charAt(i);
            if (c == '{') {
                if (i + 1 < template.length() && template.charAt(i + 1) == '{') {
                    sb.append('{');
                    i += 2;
                    continue;
                }
                final int end = template.indexOf('}', i + 1);
                if (end > i + 1) {
                    final String key = template.substring(i + 1, end);
                    final String v = values.get(key);
                    if (v != null) {
                        sb.append(escape.apply(v));
                        i = end + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
