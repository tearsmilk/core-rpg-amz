package core.amazake.systems.corevault.level;

import java.util.Map;
import java.util.TreeMap;

/**
 * Сколько опыта нужно, чтобы с уровня {@code level} перейти на следующий. Таблица (если уровень в ней есть) важнее
 * формулы. Уровни от {@code startLevel} до {@code maxLevel}; на максимальном уровне опыт не копится.
 */
public final class LevelCurve {

    private final Expr formula;
    private final Map<Integer, Long> table;
    private final int startLevel;
    private final int maxLevel;

    public LevelCurve(final Expr formula, final Map<Integer, Long> table, final int startLevel, final int maxLevel) {
        if (startLevel < 0 || maxLevel < startLevel) {
            throw new IllegalArgumentException("уровни: start " + startLevel + ", max " + maxLevel);
        }
        this.formula = formula;
        this.table = Map.copyOf(new TreeMap<>(table));
        this.startLevel = startLevel;
        this.maxLevel = maxLevel;
    }

    public int startLevel() {
        return this.startLevel;
    }

    public int maxLevel() {
        return this.maxLevel;
    }

    public Expr formula() {
        return this.formula;
    }

    /** Опыт до следующего уровня (не меньше 1); на максимальном уровне — 0. */
    public long need(final int level) {
        if (level >= this.maxLevel) {
            return 0;
        }
        final Long t = this.table.get(level);
        if (t != null) {
            return Math.max(1, t);
        }
        final double v = this.formula.eval(level);
        if (Double.isNaN(v) || v < 1) {
            return 1;
        }
        if (v >= Long.MAX_VALUE / 4.0) {
            return Long.MAX_VALUE / 4;
        }
        return Math.round(v);
    }

    /** Весь опыт, набранный до начала уровня {@code level} (от стартового уровня). */
    public long totalBefore(final int level) {
        long sum = 0;
        for (int l = this.startLevel; l < Math.min(level, this.maxLevel); l++) {
            sum = LevelMath.sat(sum + this.need(l));
        }
        return sum;
    }
}
