package core.amazake.systems.coreeffects.logic;

/**
 * Часы кровотечения (чистая логика). Состояние: {@code untreated} — тиков без перевязки, {@code faint} — сколько ещё
 * тиков без сознания (0 — в сознании), {@code elapsed} — тиков с наложения.
 *
 * <p>Обморок наступает, когда {@code untreated} доходит до {@code faintAfter} (0 — никогда). После пробуждения счётчик
 * ставится на {@code faintAfter - refaintAfter}: следующий обморок — через {@code refaintAfter}, если рана так и не
 * перевязана.</p>
 */
public final class BleedingRules {

    /** Что случилось за тик. */
    public enum Step { NONE, DAMAGE, FAINT, WAKE }

    private BleedingRules() {
    }

    /** Новое состояние после одного тика и событие. */
    public record Result(long untreated, long faint, Step step) {
    }

    public static Result tick(final long untreated, final long faint, final long elapsed, final int damageInterval,
                              final int faintAfter, final int refaintAfter, final int faintTicks) {
        if (faint > 0) {
            final long left = faint - 1;
            if (left == 0) {
                final long back = Math.max(0, (long) faintAfter - refaintAfter);
                return new Result(back, 0, Step.WAKE);
            }
            return new Result(untreated, left, damageInterval > 0 && elapsed > 0 && elapsed % damageInterval == 0 ? Step.DAMAGE : Step.NONE);
        }
        final long u = untreated + 1;
        if (faintAfter > 0 && u >= faintAfter) {
            return new Result(u, faintTicks, Step.FAINT);
        }
        return new Result(u, 0, damageInterval > 0 && elapsed > 0 && elapsed % damageInterval == 0 ? Step.DAMAGE : Step.NONE);
    }

    /** Урон, который можно нанести, не опустив здоровье ниже {@code minHealth} (0 — может убить). */
    public static double allowedDamage(final double health, final double damage, final double minHealth) {
        if (damage <= 0 || health <= 0) {
            return 0;
        }
        if (minHealth <= 0) {
            return Math.min(health, damage);
        }
        return Math.max(0, Math.min(damage, health - minHealth));
    }

    /** Сила крови на экране 1..3 по доле времени до обморока. */
    public static int bloodLevel(final long untreated, final int faintAfter) {
        if (faintAfter <= 0) {
            return 1;
        }
        final double f = (double) untreated / faintAfter;
        return f < 0.4 ? 1 : f < 0.75 ? 2 : 3;
    }
}
