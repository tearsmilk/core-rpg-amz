package core.amazake.systems.corerpg.progression;

/**
 * Чистая математика уровней: начисление опыта с повышением сразу на несколько уровней (в v3 плагина было не больше
 * одного за начисление), очки параметров за каждые N уровней, остановка на уровне профессии, если она не выбрана.
 */
public final class Leveling {

    /**
     * Итог начисления.
     *
     * @param level        уровень после начисления
     * @param exp          опыт внутри уровня после начисления
     * @param levelsGained сколько уровней получено
     * @param pointsGained сколько очков параметров получено
     * @param stoppedAtGate остановились на уровне профессии (дальше без профессии нельзя)
     */
    public record Result(int level, long exp, int levelsGained, int pointsGained, boolean stoppedAtGate) {
    }

    private Leveling() {
    }

    /**
     * Начислить {@code gain} опыта.
     *
     * @param gateLevel уровень, выше которого нельзя подняться (уровень профессии, если профессии нет); 0 — без
     *                  ограничения
     */
    public static Result apply(final ExpCurve curve, final int maxLevel, final int level, final long exp, final long gain,
                               final int gateLevel, final int pointsEvery, final int pointsPerStep) {
        int lvl = Math.max(1, Math.min(level, maxLevel));
        long cur = saturatedAdd(Math.max(0L, exp), Math.max(0L, gain));
        int levels = 0;
        int points = 0;
        boolean gated = false;
        while (lvl < maxLevel) {
            if (gateLevel > 0 && lvl >= gateLevel) {
                gated = true;
                break;
            }
            final long need = curve.need(lvl + 1);
            if (cur < need) {
                break;
            }
            cur -= need;
            lvl++;
            levels++;
            points += pointsAt(lvl, pointsEvery, pointsPerStep);
        }
        if (lvl >= maxLevel || gated) {
            // На максимуме и на уровне профессии (без профессии) остаток не копится: опыт выше не начисляется.
            cur = 0L;
        }
        return new Result(lvl, cur, levels, points, gated);
    }

    /** Очки за достижение уровня {@code level}. */
    public static int pointsAt(final int level, final int pointsEvery, final int pointsPerStep) {
        return pointsEvery > 0 && level > 1 && level % pointsEvery == 0 ? pointsPerStep : 0;
    }

    /** Все очки, положенные за уровни 2..{@code level}. */
    public static int pointsForLevel(final int level, final int pointsEvery, final int pointsPerStep) {
        if (pointsEvery <= 0) {
            return 0;
        }
        int sum = 0;
        for (int l = 2; l <= level; l++) {
            sum += pointsAt(l, pointsEvery, pointsPerStep);
        }
        return sum;
    }

    /** Прогресс уровня 0..100. */
    public static double progress(final long exp, final long need) {
        if (need <= 0) {
            return 100.0;
        }
        return Math.max(0.0, Math.min(100.0, exp * 100.0 / need));
    }

    static long saturatedAdd(final long a, final long b) {
        final long r = a + b;
        return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
    }
}
