package core.amazake.systems.coreeffects.logic;

import java.util.random.RandomGenerator;

/** Целый диапазон «a-b» или одно число; {@link #pick} — равномерно в [min, max]. */
public record Range(int min, int max) {

    public Range {
        if (min > max) {
            final int t = min;
            min = max;
            max = t;
        }
    }

    public int pick(final RandomGenerator random) {
        return this.min == this.max ? this.min : this.min + random.nextInt(this.max - this.min + 1);
    }

    /** «3», «2-5», «2..5»; вне [lo, hi] или не число — {@code null}. */
    public static Range parse(final Object raw, final int lo, final int hi) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            final int v = n.intValue();
            return v >= lo && v <= hi && n.doubleValue() == v ? new Range(v, v) : null;
        }
        final String s = raw.toString().trim().replace("..", "-");
        try {
            final int dash = s.indexOf('-', 1);
            final int a;
            final int b;
            if (dash < 0) {
                a = Integer.parseInt(s);
                b = a;
            } else {
                a = Integer.parseInt(s.substring(0, dash).trim());
                b = Integer.parseInt(s.substring(dash + 1).trim());
            }
            final Range r = new Range(a, b);
            return r.min >= lo && r.max <= hi ? r : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return this.min == this.max ? Integer.toString(this.min) : this.min + "-" + this.max;
    }
}
