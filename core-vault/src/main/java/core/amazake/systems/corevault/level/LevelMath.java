package core.amazake.systems.corevault.level;

/** Опыт ↔ уровни: добавить/снять опыт с переходом через уровни, задать весь опыт или уровень. Чистая логика. */
public final class LevelMath {

    private LevelMath() {
    }

    /** Состояние: уровень и опыт внутри уровня (0 ≤ exp < need(level)). */
    public record State(int level, long exp) {
    }

    /** Насыщающее сложение для неотрицательных. */
    static long sat(final long v) {
        return v < 0 ? Long.MAX_VALUE / 4 : v;
    }

    /** Привести к правилам кривой: уровень в границах, опыт в пределах уровня (лишний — в следующие уровни). */
    public static State normalize(final LevelCurve c, final int level, final long exp) {
        int l = Math.max(c.startLevel(), Math.min(c.maxLevel(), level));
        long e = Math.max(0, exp);
        while (l < c.maxLevel() && e >= c.need(l)) {
            e -= c.need(l);
            l++;
        }
        if (l >= c.maxLevel()) {
            return new State(c.maxLevel(), 0);
        }
        return new State(l, e);
    }

    /** Добавить опыт (delta ≥ 0) или снять (delta < 0; ниже стартового уровня с нулём опыта не опускается). */
    public static State addExp(final LevelCurve c, final State s, final long delta) {
        if (delta >= 0) {
            return normalize(c, s.level(), sat(s.exp() + delta));
        }
        int l = s.level();
        long e = s.exp();
        long take = -delta;
        while (take > 0) {
            if (e >= take) {
                e -= take;
                take = 0;
            } else {
                take -= e;
                if (l <= c.startLevel()) {
                    e = 0;
                    break;
                }
                l--;
                e = c.need(l);
            }
        }
        return new State(l, e);
    }

    /** Весь опыт с начала (стартовый уровень, 0 опыта). */
    public static long total(final LevelCurve c, final State s) {
        return sat(c.totalBefore(s.level()) + s.exp());
    }

    /** Задать весь опыт с начала. */
    public static State fromTotal(final LevelCurve c, final long total) {
        return normalize(c, c.startLevel(), Math.max(0, total));
    }

    /** Сдвинуть уровень на delta, сохраняя опыт внутри уровня (урезается, если новому уровню нужно меньше). */
    public static State addLevels(final LevelCurve c, final State s, final long delta) {
        final long target = Math.max(c.startLevel(), Math.min(c.maxLevel(), (long) s.level() + delta));
        return setLevel(c, (int) target, s.exp());
    }

    /** Уровень с опытом внутри уровня. */
    public static State setLevel(final LevelCurve c, final int level, final long exp) {
        final int l = Math.max(c.startLevel(), Math.min(c.maxLevel(), level));
        if (l >= c.maxLevel()) {
            return new State(l, 0);
        }
        return new State(l, Math.max(0, Math.min(exp, c.need(l) - 1)));
    }

    /** Прогресс уровня 0..1. */
    public static double progress(final LevelCurve c, final State s) {
        final long need = c.need(s.level());
        return need <= 0 ? 1.0 : Math.min(1.0, (double) s.exp() / need);
    }
}
