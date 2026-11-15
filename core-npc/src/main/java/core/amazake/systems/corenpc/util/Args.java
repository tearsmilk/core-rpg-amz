package core.amazake.systems.corenpc.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Разбор аргументов команд: склейка хвоста, кавычки, числа, длительности, флаги. Чистая логика. */
public final class Args {

    private Args() {
    }

    /** Аргументы с from до конца через пробел ("" — если их нет). */
    public static String join(final String[] args, final int from) {
        if (from >= args.length) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    /**
     * Строка → слова; "в двойных кавычках" — одно слово (внутри \" и \\ экранируются). Незакрытая кавычка
     * съедает остаток строки.
     */
    public static List<String> tokenize(final String line) {
        final List<String> out = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < line.length() && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
                    cur.append(line.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (Character.isWhitespace(c)) {
                if (any || cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else {
                cur.append(c);
            }
        }
        if (any || cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Число или null. Запятая как десятичный разделитель тоже понимается. */
    public static Double number(final String raw) {
        if (raw == null) {
            return null;
        }
        try {
            final double v = Double.parseDouble(raw.trim().replace(',', '.'));
            return Double.isFinite(v) ? v : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** Да/нет: true/false, yes/no, on/off, да/нет, 1/0; иначе null. */
    public static Boolean bool(final String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1", "да", "вкл" -> Boolean.TRUE;
            case "false", "no", "off", "0", "нет", "выкл" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Длительность в секундах: 5, 2.5, 5s, 1m, 1m30s, 500ms, 1h; null — не разобрать. */
    public static Double seconds(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        final String s = raw.trim().toLowerCase(Locale.ROOT);
        final Double plain = number(s);
        if (plain != null) {
            return plain < 0 ? null : plain;
        }
        double total = 0;
        int i = 0;
        boolean any = false;
        while (i < s.length()) {
            int j = i;
            while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                j++;
            }
            if (j == i) {
                return null;
            }
            final Double v = number(s.substring(i, j));
            int k = j;
            while (k < s.length() && Character.isLetter(s.charAt(k))) {
                k++;
            }
            final String unit = s.substring(j, k);
            if (v == null) {
                return null;
            }
            switch (unit) {
                case "ms" -> total += v / 1000.0;
                case "s", "" -> total += v;
                case "m" -> total += v * 60;
                case "h" -> total += v * 3600;
                default -> {
                    return null;
                }
            }
            any = true;
            i = k;
        }
        return any ? total : null;
    }

    /** Id NPC/диалога: латиница, цифры, _ - . (1..48). */
    public static boolean validId(final String id) {
        return id != null && id.matches("[A-Za-z0-9_.-]{1,48}");
    }
}
