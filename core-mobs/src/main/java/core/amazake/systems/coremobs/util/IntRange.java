package core.amazake.systems.coremobs.util;

import java.util.random.RandomGenerator;

/** Диапазон целых «3» или «1-4» (включительно). */
public record IntRange(int min, int max) {

    public IntRange {
        if (min > max) {
            final int t = min;
            min = max;
            max = t;
        }
    }

    public static IntRange of(final int value) {
        return new IntRange(value, value);
    }

    /** «3», «1-4», «1..4»; null — ошибка формата. */
    public static IntRange parse(final String text) {
        if (text == null) {
            return null;
        }
        final String s = text.trim().replace("..", "-");
        try {
            final int dash = s.indexOf('-', 1);
            if (dash < 0) {
                return of(Integer.parseInt(s));
            }
            return new IntRange(Integer.parseInt(s.substring(0, dash).trim()), Integer.parseInt(s.substring(dash + 1).trim()));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    public int roll(final RandomGenerator random) {
        return this.min == this.max ? this.min : this.min + random.nextInt(this.max - this.min + 1);
    }

    public String format() {
        return this.min == this.max ? Integer.toString(this.min) : this.min + "-" + this.max;
    }
}
