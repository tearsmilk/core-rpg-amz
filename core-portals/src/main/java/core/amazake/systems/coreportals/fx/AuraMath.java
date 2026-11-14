package core.amazake.systems.coreportals.fx;

/**
 * Чистая математика «ауры» лазуритового разлома: насколько сильно светится экран у игрока рядом, как плавно
 * свечение появляется и гаснет, и как его затягивает. Без Bukkit.
 */
public final class AuraMath {

    /** Без прямой видимости (разлом за стеной) свечение слабее во столько раз; затягивания нет совсем. */
    public static final double NO_SIGHT_GLOW = 0.35;
    /** Плавность свечения за тик: нарастает и гаснет за ≈ 0,5 с. */
    public static final double RISE = 0.18;
    public static final double FALL = 0.22;
    /** Ниже этого свечение считается погасшим (дисплей убирается). */
    public static final double EPSILON = 0.01;
    /** Наибольший байт параметра в синем канале маркера (см. Markers: 251..255 заняты чужим маркером). */
    public static final int MAX_BYTE = 250;

    private AuraMath() {
    }

    /** Близость 0..1: 1 — вплотную к разлому, 0 — на границе радиуса и дальше. */
    public static double closeness(final double distance, final double radius) {
        if (!(radius > 0) || !(distance < radius)) {
            return 0;
        }
        return Math.max(0, Math.min(1, 1 - distance / radius));
    }

    /**
     * Целевая яркость свечения на экране, 0..1: растёт к разлому плавно (smoothstep по близости), умножается на
     * раскрытость разлома; без прямой видимости — {@link #NO_SIGHT_GLOW}.
     */
    public static double glowTarget(final double distance, final double radius, final double openness,
                                    final boolean sight) {
        final double c = closeness(distance, radius);
        final double s = c * c * (3 - 2 * c);
        return clamp01(openness) * s * (sight ? 1 : NO_SIGHT_GLOW);
    }

    /** Шаг к цели: нарастает со скоростью {@link #RISE}, гаснет — {@link #FALL} (доля разницы за тик). */
    public static double approach(final double level, final double target, final int ticks) {
        double v = level;
        for (int i = 0; i < ticks; i++) {
            v += (target - v) * (target > v ? RISE : FALL);
        }
        if (Math.abs(target - v) < 0.002) {
            v = target;
        }
        return clamp01(v);
    }

    /**
     * Толчок к разлому, блоков/тик: {@code strength × раскрытость × близость^1,5}; без видимости — 0.
     * Скорость к разлому после толчка не больше {@code maxSpeed} ({@link #cap}).
     */
    public static double pull(final double distance, final double radius, final double openness, final boolean sight,
                              final double strength) {
        if (!sight || !(strength > 0)) {
            return 0;
        }
        final double c = closeness(distance, radius);
        return strength * clamp01(openness) * Math.pow(c, 1.5);
    }

    /**
     * Сколько из толчка {@code nudge} можно добавить, чтобы скорость к разлому ({@code toward}, блоков/тик; может
     * быть отрицательной — игрок уходит) не превысила {@code maxSpeed}.
     */
    public static double cap(final double toward, final double nudge, final double maxSpeed) {
        if (!(nudge > 0)) {
            return 0;
        }
        return Math.max(0, Math.min(nudge, maxSpeed - toward));
    }

    /** Скорость по Y у стоящего на земле (гравитация 0,08 × 0,98): «не прыгает и не падает». */
    public static final double GROUND_VY = -0.0784;
    /** Смещение по Y за тик больше этого — игрок не стоит (прыжок, шаг на ступеньку, падение). */
    public static final double FLAT_DY = 0.01;
    /** Столько тиков подряд на земле и без смещения по Y — только тогда толчок (после прыжка — пауза). */
    public static final int GROUND_TICKS = 4;
    /** Трение на земле по умолчанию: скользкость обычного блока 0,6 × 0,91. */
    public static final double GROUND_FRICTION = 0.6 * 0.91;

    /** Игрок ничего не жмёт: ни вперёд/назад/вбок, ни прыжок. Только такого и толкаем (приседать можно). */
    public static boolean idle(final boolean forward, final boolean backward, final boolean left, final boolean right,
                               final boolean jump) {
        return !(forward || backward || left || right || jump);
    }

    /**
     * Можно ли сейчас толкать: на земле (по клиенту), {@link #GROUND_TICKS} тиков подряд без смещения по Y,
     * клавиша прыжка не нажата, скорость по Y не положительная. Иначе толчка нет совсем — прыжок не трогается.
     *
     * @param onGround    клиент говорит, что стоит на земле
     * @param groundTicks сколько тиков подряд он стоит (на земле и |dy| ≤ {@link #FLAT_DY})
     * @param dy          смещение по Y за последний тик
     * @param vy          скорость по Y, какую знает сервер
     * @param jumpKey     клавиша прыжка нажата (ввод клиента)
     */
    public static boolean grounded(final boolean onGround, final int groundTicks, final double dy, final double vy,
                                   final boolean jumpKey) {
        return onGround && !jumpKey && groundTicks >= GROUND_TICKS && Math.abs(dy) <= FLAT_DY && !(vy > 0);
    }

    /**
     * Новая скорость игрока после толчка к разлому — или {@code null}, если толкать не нужно. Толчок
     * <b>добавляется</b> к текущей горизонтальной скорости, вертикальная возвращается как есть:
     * <ul>
     *   <li>текущая горизонтальная скорость — смещение за прошлый тик × трение (столько у клиента после шага);</li>
     *   <li>к ней — {@code nudge} по направлению к разлому, но не больше, чем позволяет {@link #cap}: скорость к
     *       разлому (по смещению) не выше {@code maxSpeed}; от разлома игрок уходит свободно;</li>
     *   <li>{@code vy} не меняется; не на земле ({@code grounded == false}) или {@code vy > 0} — {@code null}.</li>
     * </ul>
     *
     * @param dx       смещение по X за прошлый тик, блоков
     * @param vy       текущая скорость по Y (возвращается без изменений)
     * @param dz       смещение по Z за прошлый тик, блоков
     * @param friction трение под ногами (скользкость × 0,91)
     * @param dirX     направление к разлому по X (горизонталь, нормируется здесь)
     * @param dirZ     направление к разлому по Z
     * @param nudge    толчок, блоков/тик ({@link #pull})
     * @param maxSpeed скорость к разлому, выше которой не разгоняет, блоков/тик
     * @param grounded {@link #grounded}
     * @return {vx, vy, vz} или {@code null}
     */
    public static double[] pullVelocity(final double dx, final double vy, final double dz, final double friction,
                                        final double dirX, final double dirZ, final double nudge,
                                        final double maxSpeed, final boolean grounded) {
        if (!grounded || vy > 0 || !(nudge > 0)) {
            return null;
        }
        final double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (!(len > 1e-6)) {
            return null;
        }
        final double ux = dirX / len;
        final double uz = dirZ / len;
        final double add = cap(dx * ux + dz * uz, nudge, maxSpeed);
        if (add <= 0) {
            return null;
        }
        final double f = Math.max(0, Math.min(1, friction));
        return new double[] {dx * f + ux * add, vy, dz * f + uz * add};
    }

    /** Значение 0..1 → байт параметра маркера 0..{@link #MAX_BYTE}. */
    public static int toByte(final double value) {
        return (int) Math.round(clamp01(value) * MAX_BYTE);
    }

    public static double clamp01(final double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : (Double.isNaN(v) ? 0 : v));
    }
}
