package core.amazake.systems.corerpg.progression;

/** Мелкая математика опыта: бонус exp_bonus и кулдаун сообщения о блокировке. */
public final class ExpMath {

    private ExpMath() {
    }

    /** Опыт с аспектом exp_bonus: ×(1 + bonus/100), не меньше 0, с округлением. */
    public static long withBonus(final long amount, final double bonusPercent) {
        if (amount <= 0) {
            return 0L;
        }
        final double mult = Math.max(0.0, 1.0 + (Double.isFinite(bonusPercent) ? bonusPercent : 0.0) / 100.0);
        final double scaled = amount * mult;
        return scaled >= (double) Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(scaled);
    }

    /** Прошёл ли кулдаун с момента {@code last} (мс; null — ещё не было). */
    public static boolean cooldownPassed(final Long last, final long now, final int cooldownSeconds) {
        return last == null || now - last >= cooldownSeconds * 1000L;
    }
}
