package ink.hoka.odyssey.spacephys;

/**
 * Сверка скорости, которую клиентский модуль сам ведёт, со скоростью после vanilla move (мод spaceflight).
 * <p>
 * Модуль каждый тик ставит игроку скорость {@code expected} (блоки/тик), а vanilla до следующего тика её портит:
 * <ul>
 *   <li>обнуляет компоненту меньше {@link #NEGLIGIBLE} (0,003 блока/тик = 0,06 блока/с) — при слабой тяге
 *       (0,8 б/с² = 0,002 блока/тик за тик) игрок из покоя не сдвинулся бы никогда: каждый тик прибавка
 *       стиралась бы;</li>
 *   <li>прибавляет ввод WASD (moveRelative, до 0,026 блока/тик — 10 б/с² «бесплатной» тяги), если клавиша успела
 *       нажаться до нашего тика (нажатие, автоповтор клавиши);</li>
 *   <li>обнуляет ось, по которой упёрся в блок (это нужно оставить: удар);</li>
 *   <li>сервер может прибавить толчок (взрыв — gravzones, корабли) или задать скорость (пакет скорости, телепорт).</li>
 * </ul>
 * Поэтому основа — своя скорость, а от наблюдаемой берём только удар (ось обнулилась при столкновении) и заметное
 * внешнее изменение (больше допуска на ввод клавиш).
 */
public final class VanillaMotion {
    /** Vanilla обнуляет компоненту скорости меньше этой, блоки/тик (LivingEntity.aiStep). */
    public static final double NEGLIGIBLE = 0.003;
    /** Наибольший ввод клавиш в воздухе за тик (с бегом 0,026), с запасом, блоки/тик. */
    public static final double INPUT_TOLERANCE = 0.03;
    /** По вертикали vanilla в воздухе ничего не прибавляет — допуск на округление, блоки/тик. */
    public static final double VERTICAL_TOLERANCE = 1.0e-4;

    private VanillaMotion() {}

    /**
     * Скорость, с которой продолжить этот тик, блоки/тик.
     *
     * @param expected   скорость, поставленная в прошлый тик, блоки/тик
     * @param observed   скорость после vanilla move, блоки/тик
     * @param horizontal vanilla упёрся по горизонтали (horizontalCollision)
     * @param vertical   vanilla упёрся по вертикали (verticalCollision)
     */
    public static Vec3d reconcile(Vec3d expected, Vec3d observed, boolean horizontal, boolean vertical) {
        return new Vec3d(
            axis(expected.x(), observed.x(), horizontal, INPUT_TOLERANCE),
            axis(expected.y(), observed.y(), vertical, VERTICAL_TOLERANCE),
            axis(expected.z(), observed.z(), horizontal, INPUT_TOLERANCE));
    }

    static double axis(double expected, double observed, boolean collided, double tolerance) {
        if (!Double.isFinite(observed)) {
            return Double.isFinite(expected) ? expected : 0;
        }
        if (collided && observed == 0 && expected != 0) {
            return 0;                                          // упёрся в блок — ось обнулена
        }
        if (observed == 0 && Math.abs(expected) < NEGLIGIBLE * 1.01) {
            return expected;                                   // vanilla стёр мелочь (< 0,003 блока/тик): по
        }                                                      // вертикали без этого слабая тяга не разгоняла с нуля
        if (Math.abs(observed - expected) <= tolerance) {
            return expected;                                   // ввод клавиш, «обнуление мелочи», округление
        }
        return observed;                                       // толчок или скорость от сервера
    }
}
