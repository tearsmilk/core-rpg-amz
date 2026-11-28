package ink.hoka.odyssey.oxygen;

/**
 * Запас воздуха одного игрока — чистая логика без Minestom (тестируется отдельно).
 * Воздух — в единицах ванильной шкалы: {@link #MAX} = 300 = 10 пузырьков (метаданные «air supply» игрока).
 */
final class Lungs {
    /** Полный запас воздуха у игрока в ванили (getMaxAirSupply) — по нему клиент рисует шкалу. */
    static final int MAX = 300;
    /**
     * Что отправлять клиенту, когда воздух кончился. Отрицательное значение (как в ванили при утоплении: -1…-20) —
     * шкала пустая, даже если клиент между пакетами сам прибавит себе немного воздуха.
     */
    static final int EMPTY = -10;

    private double air = MAX;
    /** Сколько тиков подряд без воздуха (для темпа урона). */
    private int choking;
    /** Сколько тиков подряд игроку нужно предупреждение (для темпа action bar). */
    private int warned;

    /**
     * Один тик.
     *
     * @param breathless тратится ли воздух (карта без воздуха, нет скафандра)
     * @param canHurt    может ли игрок получить урон (выживание/приключение)
     * @return true — в этот тик нанести урон от удушья
     */
    boolean tick(Settings s, boolean breathless, boolean canHurt) {
        if (!breathless) {
            choking = 0;
            air = Math.min(MAX, air + s.refillPerTick());
            return false;
        }
        if (air > 0) {                                          // ещё дышит; удушье считаем с тика после нуля
            air = Math.max(0, air - s.drainPerTick());
            choking = 0;
            return false;
        }
        if (!canHurt) {
            choking = 0;
            return false;
        }
        if (++choking >= s.damageInterval()) {
            choking = 0;
            return true;
        }
        return false;
    }

    /**
     * Тик предупреждения: true — пора показать его снова. Первое — через interval тиков после начала (чтобы не
     * перебить подсказку других модулей при входе на карту), дальше — раз в interval.
     */
    boolean warnTick(boolean need, int interval) {
        if (!need) {
            warned = 0;
            return false;
        }
        if (++warned >= interval) {
            warned = 0;
            return true;
        }
        return false;
    }

    /** Значение для метаданных «air supply»: 0…300, а если воздух кончился — {@link #EMPTY}. */
    int meta() {
        return air > 0 ? (int) Math.ceil(air) : EMPTY;
    }

    double air() {
        return air;
    }

    boolean full() {
        return air >= MAX;
    }

    /** Полный запас (после смерти/возрождения). */
    void reset() {
        air = MAX;
        choking = 0;
        warned = 0;
    }
}
