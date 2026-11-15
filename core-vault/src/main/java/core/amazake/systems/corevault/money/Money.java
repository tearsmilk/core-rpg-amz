package core.amazake.systems.corevault.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Разбор и вывод денежных сумм. Суммы — long в минорных единицах (×10^decimals); без double. */
public final class Money {

    private Money() {
    }

    /** Ошибка разбора суммы: текст — для игрока. */
    public static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        public ParseException(final String message) {
            super(message);
        }
    }

    public static long pow10(final int n) {
        long r = 1;
        for (int i = 0; i < n; i++) {
            r *= 10;
        }
        return r;
    }

    /**
     * Сумма из текста игрока: {@code 10}, {@code 10.5}, {@code 10,50}, {@code 1 000}, {@code 1_000}, {@code 1.5k},
     * {@code 2м}. Отрицательные, нулевые (если {@code allowZero} = false), с лишними знаками после запятой — ошибка.
     *
     * @return сумма в минорных единицах
     */
    public static long parse(final String raw, final int decimals, final boolean allowZero) throws ParseException {
        if (raw == null) {
            throw new ParseException("пустая сумма");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "").replace(" ", "");
        if (s.isEmpty()) {
            throw new ParseException("пустая сумма");
        }
        if (s.startsWith("-")) {
            throw new ParseException("сумма не может быть отрицательной");
        }
        if (s.startsWith("+")) {
            s = s.substring(1);
        }
        long mult = 1;
        final char last = s.charAt(s.length() - 1);
        if (last == 'k' || last == 'к') {
            mult = 1_000;
        } else if (last == 'm' || last == 'м') {
            mult = 1_000_000;
        }
        if (mult != 1) {
            s = s.substring(0, s.length() - 1);
        }
        s = s.replace(',', '.');
        if (s.isEmpty() || !s.matches("\\d+(\\.\\d+)?|\\.\\d+")) {
            throw new ParseException("не число: " + raw);
        }
        final BigDecimal v;
        try {
            v = new BigDecimal(s).multiply(BigDecimal.valueOf(mult));
        } catch (final NumberFormatException e) {
            throw new ParseException("не число: " + raw);
        }
        final BigDecimal minor = v.movePointRight(decimals);
        if (minor.stripTrailingZeros().scale() > 0) {
            throw new ParseException(decimals == 0 ? "только целые суммы" : "не больше " + decimals + " знаков после запятой");
        }
        if (minor.compareTo(BigDecimal.valueOf(Long.MAX_VALUE / 4)) > 0) {
            throw new ParseException("слишком большая сумма");
        }
        final long out = minor.longValueExact();
        if (out == 0 && !allowZero) {
            throw new ParseException("сумма должна быть больше нуля");
        }
        return out;
    }

    /** Десятичная строка для файла: 12345 при 2 знаках → "123.45". */
    public static String plain(final long minor, final int decimals) {
        return BigDecimal.valueOf(minor, decimals).toPlainString();
    }

    /** Из десятичной строки/числа файла в минорные единицы (лишние знаки отбрасываются вниз). */
    public static long fromPlain(final String text, final int decimals) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return new BigDecimal(text.trim()).setScale(decimals, RoundingMode.DOWN).unscaledValue().longValueExact();
    }

    /** double (Vault) → минорные единицы, округление до ближайшего. */
    public static long fromDouble(final double value, final int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("сумма не число: " + value);
        }
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).unscaledValue().longValueExact();
    }

    public static double toDouble(final long minor, final int decimals) {
        return BigDecimal.valueOf(minor, decimals).doubleValue();
    }

    /** «1 234,5»: разделитель тысяч и дробной части; trimZeros — убрать нули в конце дробной части. */
    public static String format(final long minor, final int decimals, final String group, final String decimal,
                                final boolean trimZeros) {
        final boolean neg = minor < 0;
        final long abs = Math.abs(minor);
        final long unit = pow10(decimals);
        final String whole = Long.toString(abs / unit);
        final StringBuilder sb = new StringBuilder();
        if (neg) {
            sb.append('-');
        }
        final int len = whole.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0 && !group.isEmpty()) {
                sb.append(group);
            }
            sb.append(whole.charAt(i));
        }
        if (decimals > 0) {
            String frac = Long.toString(abs % unit);
            while (frac.length() < decimals) {
                frac = "0" + frac;
            }
            if (trimZeros) {
                int end = frac.length();
                while (end > 0 && frac.charAt(end - 1) == '0') {
                    end--;
                }
                frac = frac.substring(0, end);
            }
            if (!frac.isEmpty()) {
                sb.append(decimal).append(frac);
            }
        }
        return sb.toString();
    }

    /** Русская форма по числу: 1 монета, 2 монеты, 5 монет, 11 монет, 21 монета. */
    public static String plural(final long n, final String one, final String few, final String many) {
        final long a = Math.abs(n);
        final long mod100 = a % 100;
        final long mod10 = a % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        if (mod10 == 1) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return few;
        }
        return many;
    }
}
