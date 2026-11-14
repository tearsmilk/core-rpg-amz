package core.amazake.systems.coreportals.anim;

/**
 * Расписание анимации портала по тикам (0 — тик появления). Неизменяемо: досрочное закрытие —
 * {@link #closeAt(long)}, новый объект.
 *
 * <pre>
 *   0..10   CRACK         трещина и угли, свечение земли растёт
 *  10..40   RISE          рама из-под земли до полной высоты (inOutCubic)
 *  40..60   OPEN          поверхность раскрывается от средней линии (outBack)
 *  60..C    IDLE          открыт; C = срок жизни − 40 тиков
 *   C..C+15 CLOSE_SURFACE поверхность схлопывается
 * C+15..C+40 CLOSE_FRAME  рама уходит в землю, огонь и трещина гаснут
 * </pre>
 *
 * <p>Закрытие начинается с тех значений, что были на момент {@code closeStart}: портал, закрытый на середине
 * подъёма, опускается с той же высоты, без скачка.</p>
 *
 * @param lifetimeTicks срок жизни целиком, от появления до исчезновения
 * @param closeStart    тик начала закрытия
 */
public record Timeline(long lifetimeTicks, long closeStart) {

    public static final int CRACK = 10;
    public static final int RISE = 30;
    public static final int OPEN = 20;
    public static final int CLOSE_SURFACE = 15;
    public static final int CLOSE_FRAME = 25;
    /** Тик, с которого портал открыт полностью. */
    public static final int OPENED = CRACK + RISE + OPEN;
    /** Длительность закрытия. */
    public static final int CLOSING = CLOSE_SURFACE + CLOSE_FRAME;
    /** Минимальный срок жизни: открытие, закрытие и хотя бы секунда открытым. */
    public static final int MIN_LIFETIME = OPENED + CLOSING + 20;
    /** Шаг ключевых кадров (тиков): столько же длится интерполяция дисплея. */
    public static final int KEY_STEP = 2;

    public Timeline {
        if (lifetimeTicks < MIN_LIFETIME) {
            throw new IllegalArgumentException("срок жизни " + lifetimeTicks + " < " + MIN_LIFETIME + " тиков");
        }
        if (closeStart < 0) {
            throw new IllegalArgumentException("closeStart < 0");
        }
    }

    /** Обычное расписание: закрытие в конце срока жизни. */
    public static Timeline of(final long lifetimeTicks) {
        final long life = Math.max(lifetimeTicks, MIN_LIFETIME);
        return new Timeline(life, life - CLOSING);
    }

    /** Закрыть досрочно с тика {@code tick} (если закрытие ещё не началось). */
    public Timeline closeAt(final long tick) {
        if (tick >= this.closeStart) {
            return this;
        }
        return new Timeline(this.lifetimeTicks, Math.max(0, tick));
    }

    /** Тик, когда всё убрано. */
    public long end() {
        return this.closeStart + CLOSING;
    }

    public boolean closing(final long t) {
        return t >= this.closeStart;
    }

    public Phase phase(final long t) {
        if (t >= this.closeStart) {
            final long d = t - this.closeStart;
            if (d < CLOSE_SURFACE) {
                return Phase.CLOSE_SURFACE;
            }
            return d < CLOSING ? Phase.CLOSE_FRAME : Phase.DONE;
        }
        if (t < CRACK) {
            return Phase.CRACK;
        }
        if (t < CRACK + RISE) {
            return Phase.RISE;
        }
        return t < OPENED ? Phase.OPEN : Phase.IDLE;
    }

    /** В портал можно войти. */
    public boolean enterable(final long t) {
        return this.phase(t) == Phase.IDLE;
    }

    /** Высота рамы: 0 — целиком под землёй, 1 — стоит. */
    public double rise(final long t) {
        if (t < this.closeStart) {
            return openingRise(t);
        }
        final long d = t - this.closeStart;
        final double from = openingRise(this.closeStart);
        if (d < CLOSE_SURFACE) {
            return from;
        }
        return from * (1 - Easing.inOutCubic((d - CLOSE_SURFACE) / (double) CLOSE_FRAME));
    }

    /** Раскрытие поверхности по ширине: 0 — линия, 1 — весь проём (на подходе к 1 — лёгкий перелёт). */
    public double surface(final long t) {
        if (t < this.closeStart) {
            return openingSurface(t);
        }
        final long d = t - this.closeStart;
        return openingSurface(this.closeStart) * (1 - Easing.inCubic(d / (double) CLOSE_SURFACE));
    }

    /** Размер светящейся трещины на земле: 0..1. */
    public double ground(final long t) {
        if (t < this.closeStart) {
            return Easing.outQuad(t / (double) CRACK);
        }
        return fadeOut(Easing.outQuad(this.closeStart / (double) CRACK), t - this.closeStart);
    }

    /** Высота языков пламени у основания: 0..1. */
    public double flames(final long t) {
        if (t < this.closeStart) {
            return openingFlames(t);
        }
        return fadeOut(openingFlames(this.closeStart), t - this.closeStart);
    }

    private static double fadeOut(final double from, final long d) {
        if (d < CLOSE_SURFACE) {
            return from;
        }
        return from * (1 - Easing.inQuad((d - CLOSE_SURFACE) / (double) CLOSE_FRAME));
    }

    static double openingRise(final long t) {
        return Easing.inOutCubic((t - CRACK) / (double) RISE);
    }

    static double openingSurface(final long t) {
        return Easing.outBack((t - CRACK - RISE) / (double) OPEN, 1.2);
    }

    static double openingFlames(final long t) {
        if (t < CRACK) {
            return 0.3 * Easing.outQuad(t / (double) CRACK);
        }
        return 0.3 + 0.7 * Easing.outCubic((t - CRACK) / (double) RISE);
    }
}
