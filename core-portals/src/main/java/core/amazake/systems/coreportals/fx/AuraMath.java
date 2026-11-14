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

    /** Значение 0..1 → байт параметра маркера 0..{@link #MAX_BYTE}. */
    public static int toByte(final double value) {
        return (int) Math.round(clamp01(value) * MAX_BYTE);
    }

    public static double clamp01(final double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : (Double.isNaN(v) ? 0 : v));
    }
}
