package core.amazake.systems.coreeffects.logic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Длительности: разбор «90», «90s», «2m», «1m30s», «1h», «inf» и вывод «1:05». Время — в тиках (20 в секунде). */
public final class Durations {

    /** Бесконечная длительность. */
    public static final long INFINITE = -1L;

    private static final Pattern PART = Pattern.compile("(\\d+)([hms]?)");
    /** Больше суток не бывает (защита от переполнения и опечаток). */
    public static final long MAX_SECONDS = 86_400L * 7;

    private Durations() {
    }

    /**
     * Секунды из строки: «90», «90s», «2m», «1m30s», «1h», «1ч», «2м», «30с»; «inf», «infinite», «∞», «навсегда» —
     * {@link #INFINITE}. Неверно, ноль или больше {@link #MAX_SECONDS} — {@code null}.
     */
    public static Long parseSeconds(final String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("inf") || s.equals("infinite") || s.equals("∞") || s.equals("навсегда")) {
            return INFINITE;
        }
        s = s.replace('ч', 'h').replace('м', 'm').replace('с', 's');
        if (s.isEmpty()) {
            return null;
        }
        final Matcher m = PART.matcher(s);
        long total = 0;
        int at = 0;
        while (m.find()) {
            if (m.start() != at) {
                return null;
            }
            at = m.end();
            final String num = m.group(1);
            if (num.length() > 9) {
                return null;
            }
            final long n = Long.parseLong(num);
            total += switch (m.group(2)) {
                case "h" -> n * 3600;
                case "m" -> n * 60;
                default -> n;
            };
            if (total > MAX_SECONDS) {
                return null;
            }
        }
        if (at != s.length() || total <= 0) {
            return null;
        }
        return total;
    }

    /** «m:ss» (или «h:mm:ss»); бесконечность — «∞». */
    public static String format(final long ticks) {
        if (ticks < 0) {
            return "∞";
        }
        final long sec = (ticks + 19) / 20;
        final long h = sec / 3600;
        final long m = sec % 3600 / 60;
        final long s = sec % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    /** Оставшиеся целые секунды (вверх); бесконечность — -1. */
    public static long seconds(final long ticks) {
        return ticks < 0 ? -1 : (ticks + 19) / 20;
    }
}
