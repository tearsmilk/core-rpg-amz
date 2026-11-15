package core.amazake.systems.corevault.coin;

import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Выпадение монет с моба: шанс в процентах, количество монет min–max, стоимость одной монеты (минорные единицы;
 * ≤ 0 — стоимость по умолчанию из coins.value).
 */
public record DropRule(double chance, int min, int max, long value) {

    public static final DropRule NONE = new DropRule(0, 0, 0, 0);

    public DropRule {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("количество " + min + "-" + max);
        }
        chance = Math.max(0, Math.min(100, chance));
    }

    /** Сколько монет выпало (0 — ничего). */
    public int roll(final RandomGenerator random) {
        if (this.chance <= 0 || this.max <= 0) {
            return 0;
        }
        if (this.chance < 100 && random.nextDouble() * 100 >= this.chance) {
            return 0;
        }
        return this.min == this.max ? this.min : this.min + random.nextInt(this.max - this.min + 1);
    }

    /** «1-3», «2» → [min, max]. */
    public static int[] parseRange(final String raw) {
        final String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("пустое количество");
        }
        final int dash = s.indexOf('-', 1);
        try {
            if (dash < 0) {
                final int v = Integer.parseInt(s);
                if (v < 0) {
                    throw new IllegalArgumentException("отрицательное количество: " + raw);
                }
                return new int[] {v, v};
            }
            final int a = Integer.parseInt(s.substring(0, dash).trim());
            final int b = Integer.parseInt(s.substring(dash + 1).trim());
            if (a < 0 || b < a) {
                throw new IllegalArgumentException("количество «" + raw + "»: нужно min-max, 0 ≤ min ≤ max");
            }
            return new int[] {a, b};
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("количество «" + raw + "» — нужно число или min-max", e);
        }
    }
}
