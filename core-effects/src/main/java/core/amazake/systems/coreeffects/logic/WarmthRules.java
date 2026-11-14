package core.amazake.systems.coreeffects.logic;

import java.util.List;

/**
 * «Тепло» (чистая логика): {@code thirst} — тиков без молока или зелья лечения. Ступени предупреждения — проценты
 * от {@code deadline}; на 100% — смерть. {@code deadline} 0 — смерти нет (только лечение).
 */
public final class WarmthRules {

    private WarmthRules() {
    }

    /** Ступень 0..warnAt.size(): сколько порогов пройдено. */
    public static int stage(final long thirst, final int deadline, final List<Integer> warnAtPercent) {
        if (deadline <= 0) {
            return 0;
        }
        int st = 0;
        for (final int p : warnAtPercent) {
            if (thirst * 100 >= (long) deadline * p) {
                st++;
            }
        }
        return st;
    }

    public static boolean lethal(final long thirst, final int deadline) {
        return deadline > 0 && thirst >= deadline;
    }

    /** Доля 0..1 пути к смерти. */
    public static double fraction(final long thirst, final int deadline) {
        return deadline <= 0 ? 0 : Math.max(0, Math.min(1, (double) thirst / deadline));
    }

    /** Лечить ли на этом тике. */
    public static boolean healNow(final long elapsed, final int interval) {
        return interval > 0 && elapsed > 0 && elapsed % interval == 0;
    }
}
