package core.amazake.systems.corerpg.progression;

import java.util.Map;

/**
 * Кривая опыта: сколько опыта нужно ДЛЯ уровня N (переход N−1 → N). Таблица из конфига, для уровней без значения —
 * формула {@code base × N^exponent}. Неизменяемая, любой поток.
 */
public record ExpCurve(Map<Integer, Long> table, double base, double exponent) {

    public ExpCurve {
        table = Map.copyOf(table);
    }

    /** Опыт, нужный для уровня {@code level}; 0 для уровня 1 и ниже, не меньше 1 для остальных. */
    public long need(final int level) {
        if (level <= 1) {
            return 0L;
        }
        final Long fixed = this.table.get(level);
        if (fixed != null) {
            return Math.max(1L, fixed);
        }
        final double value = this.base * Math.pow(level, this.exponent);
        if (!Double.isFinite(value) || value >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, (long) value);
    }

    /** Опыт от уровня 1 до {@code level} всего. */
    public long total(final int level) {
        long sum = 0L;
        for (int l = 2; l <= level; l++) {
            final long n = this.need(l);
            sum = n > Long.MAX_VALUE - sum ? Long.MAX_VALUE : sum + n;
        }
        return sum;
    }
}
