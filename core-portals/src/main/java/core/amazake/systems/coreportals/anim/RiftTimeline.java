package core.amazake.systems.coreportals.anim;

/**
 * Расписание анимации лазуритового разлома по тикам (0 — тик появления). Неизменяемо: досрочное закрытие —
 * {@link #closeAt(long)}, новый объект.
 *
 * <p>Весь вид разлома рисует шейдер по одному числу — {@link #tear(long)} (0..1, синий канал маркера):</p>
 * <pre>
 *   0    .. 0,30   трещина растёт от центра вверх и вниз (длина = tear / 0,30)
 *   0,30 .. 0,45   трещина раздваивается: щель до 5 % ширины, вокруг бегут трещинки пространства
 *   0,45 .. 1      края рвутся в стороны до полного круглого разлома, внутри раскручивается вихрь
 * </pre>
 * <pre>
 *   0..14    CRACK  трещина появляется и растёт (outQuad)
 *  14..26    SPLIT  трещина раздваивается (inOutSine)
 *  26..60    TEAR   края рвутся в стороны (outCubic)
 *  60..C     IDLE   открыт; C = срок жизни − 40 тиков
 *   C..C+24  SEAL   края сходятся обратно в трещину (inOutCubic)
 * C+24..C+40        трещина стягивается к центру и гаснет (inQuad), в конце — вспышка
 * </pre>
 *
 * <p>Закрытие начинается с того вида, что был на {@code closeStart}: разлом, закрытый на середине раскрытия,
 * сходится с того места, без скачка.</p>
 *
 * @param lifetimeTicks срок жизни целиком, от появления до исчезновения
 * @param closeStart    тик начала закрытия
 */
public record RiftTimeline(long lifetimeTicks, long closeStart) {

    public static final int CRACK = 14;
    public static final int SPLIT = 12;
    public static final int TEAR = 34;
    /** Тик, с которого разлом открыт полностью. */
    public static final int OPENED = CRACK + SPLIT + TEAR;
    /** Длительность закрытия. */
    public static final int CLOSING = 40;
    /** За столько тиков закрытия края сходятся в трещину; остальное — трещина гаснет. */
    public static final int SEAL = 24;
    /** Минимальный срок жизни: открытие, закрытие и хотя бы секунда открытым. */
    public static final int MIN_LIFETIME = OPENED + CLOSING + 20;

    /** Значение {@link #tear(long)}, при котором трещина доросла до полной длины. */
    public static final double P_CRACK = 0.30;
    /** Значение {@link #tear(long)}, при котором трещина раздвоилась и края начинают рваться. */
    public static final double P_SPLIT = 0.45;

    public RiftTimeline {
        if (lifetimeTicks < MIN_LIFETIME) {
            throw new IllegalArgumentException("срок жизни " + lifetimeTicks + " < " + MIN_LIFETIME + " тиков");
        }
        if (closeStart < 0) {
            throw new IllegalArgumentException("closeStart < 0");
        }
    }

    /** Обычное расписание: закрытие в конце срока жизни. */
    public static RiftTimeline of(final long lifetimeTicks) {
        final long life = Math.max(lifetimeTicks, MIN_LIFETIME);
        return new RiftTimeline(life, life - CLOSING);
    }

    /** Закрыть досрочно с тика {@code tick} (если закрытие ещё не началось). */
    public RiftTimeline closeAt(final long tick) {
        if (tick >= this.closeStart) {
            return this;
        }
        return new RiftTimeline(this.lifetimeTicks, Math.max(0, tick));
    }

    /** Тик, когда всё убрано. */
    public long end() {
        return this.closeStart + CLOSING;
    }

    public boolean closing(final long t) {
        return t >= this.closeStart;
    }

    public RiftPhase phase(final long t) {
        if (t >= this.closeStart) {
            return t - this.closeStart < CLOSING ? RiftPhase.SEAL : RiftPhase.DONE;
        }
        if (t < CRACK) {
            return RiftPhase.CRACK;
        }
        if (t < CRACK + SPLIT) {
            return RiftPhase.SPLIT;
        }
        return t < OPENED ? RiftPhase.TEAR : RiftPhase.IDLE;
    }

    /** В разлом можно войти. */
    public boolean enterable(final long t) {
        return this.phase(t) == RiftPhase.IDLE;
    }

    /** Вид разлома для шейдера, 0..1 (см. описание класса). После конца — 0. */
    public double tear(final long t) {
        if (t < this.closeStart) {
            return opening(t);
        }
        final long d = t - this.closeStart;
        final double v0 = opening(this.closeStart);
        if (d < SEAL) {
            return v0 > P_CRACK ? P_CRACK + (v0 - P_CRACK) * (1 - Easing.inOutCubic(d / (double) SEAL)) : v0;
        }
        return Math.min(v0, P_CRACK) * (1 - Easing.inQuad((d - SEAL) / (double) (CLOSING - SEAL)));
    }

    /**
     * Насколько разлом раскрыт, 0..1: 0 — пока это трещина, 1 — полный круг. От этого зависят дымка, свечение
     * на экране у игроков рядом и затягивание.
     */
    public double openness(final long t) {
        return Easing.clamp01((this.tear(t) - P_SPLIT) / (1 - P_SPLIT));
    }

    /**
     * Трещины на экране у игроков рядом: доля раскрытия 0..1 (растут от краёв экрана к середине и гаснут в конце),
     * 0 — трещин нет (разлом открыт или закрывается).
     */
    public double screenCracks(final long t) {
        if (t >= this.closeStart || t >= OPENED || t < 0) {
            return 0;
        }
        return (t + 1) / (double) OPENED;
    }

    /** Вспышка в конце закрытия, 0..1 (последние 8 тиков). */
    public double flash(final long t) {
        if (t < this.closeStart) {
            return 0;
        }
        final double x = (t - this.closeStart - (CLOSING - 8)) / 8.0;
        if (x <= 0 || x >= 1) {
            return 0;
        }
        return x < 0.3 ? Easing.outQuad(x / 0.3) : 1 - Easing.inQuad((x - 0.3) / 0.7);
    }

    static double opening(final long t) {
        if (t < CRACK) {
            return P_CRACK * Easing.outQuad(t / (double) CRACK);
        }
        if (t < CRACK + SPLIT) {
            return P_CRACK + (P_SPLIT - P_CRACK) * Easing.inOutSine((t - CRACK) / (double) SPLIT);
        }
        return P_SPLIT + (1 - P_SPLIT) * Easing.outCubic((t - CRACK - SPLIT) / (double) TEAR);
    }
}
