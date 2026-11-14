package core.amazake.systems.coreportals.anim;

/**
 * Расписание анимации изумрудного портала по тикам (0 — тик появления). Неизменяемо: досрочное закрытие —
 * {@link #closeAt(long)}, новый объект. Ключевые кадры перекрываются: следующая часть начинает двигаться, пока
 * предыдущая ещё не закончила.
 *
 * <pre>
 *   0..10   SPARK     точка света загорается и пульсирует
 *   8..40   FORM      кольцо растёт до полного размера (outBack, лёгкий перелёт) и делает оборот;
 *                     пустота открывается из центра с отставанием (16..40), туманность (14..44),
 *                     осколки вылетают (22..46); точка гаснет в пустоте (10..26)
 *  40..C    IDLE      открыт; C = срок жизни − 30 тиков. Вращение — в шейдере (GameTime), осколки кружат
 *   C..C+30 COLLAPSE  кольцо разгоняется (+1,5 оборота) и с замахом схлопывается (24 тика), пустота стягивается
 *                     в точку (20), осколки летят внутрь; вспышка на C+20..C+30
 * </pre>
 *
 * <p>Закрытие начинается с тех значений, что были на момент {@code closeStart}: кольцо, закрытое на середине
 * раскрытия, схлопывается с того размера, без скачка.</p>
 *
 * @param lifetimeTicks срок жизни целиком, от появления до исчезновения
 * @param closeStart    тик начала закрытия
 */
public record RingTimeline(long lifetimeTicks, long closeStart) {

    public static final int SPARK = 10;
    public static final int FORM = 30;
    /** Тик, с которого портал открыт полностью. */
    public static final int OPENED = SPARK + FORM;
    /** Длительность закрытия. */
    public static final int CLOSING = 30;
    /** Последние тики закрытия — вспышка в центре. */
    public static final int FLASH = 10;
    /** Кольцо схлопнулось к этому тику закрытия. */
    public static final int COLLAPSE = CLOSING - 6;
    /** Минимальный срок жизни: открытие, закрытие и хотя бы секунда открытым. */
    public static final int MIN_LIFETIME = OPENED + CLOSING + 20;
    /** Шаг ключевых кадров во время анимации (тиков). */
    public static final int KEY_STEP = 2;
    /** Сколько тиков после {@link #OPENED} ещё доходят туманность и осколки. */
    public static final int SETTLE = 8;
    /** Шаг ключевых кадров осколков, пока портал открыт. */
    public static final int IDLE_STEP = 4;
    /** Оборот кольца при раскрытии: ровно один, чтобы открытое кольцо стояло без поворота. */
    public static final double SPIN_OPEN = 2 * Math.PI;
    /** Дополнительный поворот при схлопывании (разгон). */
    public static final double SPIN_CLOSE = 3 * Math.PI;

    public RingTimeline {
        if (lifetimeTicks < MIN_LIFETIME) {
            throw new IllegalArgumentException("срок жизни " + lifetimeTicks + " < " + MIN_LIFETIME + " тиков");
        }
        if (closeStart < 0) {
            throw new IllegalArgumentException("closeStart < 0");
        }
    }

    /** Обычное расписание: закрытие в конце срока жизни. */
    public static RingTimeline of(final long lifetimeTicks) {
        final long life = Math.max(lifetimeTicks, MIN_LIFETIME);
        return new RingTimeline(life, life - CLOSING);
    }

    /** Закрыть досрочно с тика {@code tick} (если закрытие ещё не началось). */
    public RingTimeline closeAt(final long tick) {
        if (tick >= this.closeStart) {
            return this;
        }
        return new RingTimeline(this.lifetimeTicks, Math.max(0, tick));
    }

    /** Тик, когда всё убрано. */
    public long end() {
        return this.closeStart + CLOSING;
    }

    public boolean closing(final long t) {
        return t >= this.closeStart;
    }

    public RingPhase phase(final long t) {
        if (t >= this.closeStart) {
            return t - this.closeStart < CLOSING ? RingPhase.COLLAPSE : RingPhase.DONE;
        }
        if (t < SPARK) {
            return RingPhase.SPARK;
        }
        return t < OPENED ? RingPhase.FORM : RingPhase.IDLE;
    }

    /** В портал можно войти. */
    public boolean enterable(final long t) {
        return this.phase(t) == RingPhase.IDLE;
    }

    /** Размер кольца: 0 — точка, 1 — полный (при раскрытии — лёгкий перелёт, при закрытии — замах). */
    public double ring(final long t) {
        if (t < this.closeStart) {
            return openingRing(t);
        }
        final long d = t - this.closeStart;
        return openingRing(this.closeStart) * Math.max(0, 1 - Easing.inBack(d / (double) COLLAPSE, 1.4));
    }

    /** Пустота внутри: 0 — нет (сплошной свет), 1 — полный размер. */
    public double voidOpen(final long t) {
        if (t < this.closeStart) {
            return openingVoid(t);
        }
        final long d = t - this.closeStart;
        return openingVoid(this.closeStart) * (1 - Easing.inQuad(d / (double) (COLLAPSE - 4)));
    }

    /**
     * Фаза для шейдера (синий канал маркера кольца): 0 — кольцо сформировано, 1 — только яркая точка без пустоты.
     */
    public double shaderPhase(final long t) {
        return 1 - this.voidOpen(t);
    }

    /** Яркая точка в центре: 0..1 при появлении, до 2.5 во вспышке закрытия. */
    public double spark(final long t) {
        if (t < this.closeStart) {
            return openingSpark(t);
        }
        final long d = t - this.closeStart;
        final double fade = openingSpark(this.closeStart) * (1 - Easing.outQuad(d / 8.0));
        final double x = (d - (CLOSING - FLASH)) / (double) FLASH;
        double flash = 0;
        if (x > 0 && x < 1) {
            flash = x < 0.35 ? 2.5 * Easing.outQuad(x / 0.35) : 2.5 * (1 - Easing.inQuad((x - 0.35) / 0.65));
        }
        return Math.max(fade, flash);
    }

    /** Туманность вокруг кольца: 0..1. */
    public double glow(final long t) {
        if (t < this.closeStart) {
            return openingGlow(t);
        }
        final long d = t - this.closeStart;
        return openingGlow(this.closeStart) * (1 - Easing.inQuad(d / (double) COLLAPSE));
    }

    /** Размер осколков: 0..1 (вылетают с лёгким перелётом, исчезают в конце полёта внутрь). */
    public double shardSize(final long t) {
        if (t < this.closeStart) {
            return openingShards(t);
        }
        final long d = t - this.closeStart;
        return Math.min(1, openingShards(this.closeStart)) * (1 - Easing.inCubic(d / (double) COLLAPSE));
    }

    /** Радиус орбиты осколков: доля полного (вылетают от 0.55, при закрытии летят в центр). */
    public double shardRadius(final long t) {
        if (t < this.closeStart) {
            return openingShardRadius(t);
        }
        final long d = t - this.closeStart;
        return openingShardRadius(this.closeStart) * (1 - Easing.inQuad(d / (double) COLLAPSE));
    }

    /** Поворот кольца вокруг нормали (радианы): оборот при раскрытии, разгон при закрытии. */
    public double spin(final long t) {
        if (t < this.closeStart) {
            return openingSpin(t);
        }
        final long d = t - this.closeStart;
        return openingSpin(this.closeStart) + SPIN_CLOSE * Easing.inCubic(d / (double) CLOSING);
    }

    static double openingRing(final long t) {
        return Easing.outBack((t - (SPARK - 2)) / (double) (FORM + 2), 1.3);
    }

    static double openingVoid(final long t) {
        return Easing.outCubic((t - (SPARK + 6)) / (double) (FORM - 6));
    }

    static double openingSpark(final long t) {
        final double pulse = 0.8 + 0.2 * Math.sin(2 * Math.PI * t / 10.0);
        final double in = Easing.outQuad(t / 5.0) * pulse;
        return in * (1 - Easing.inOutSine((t - SPARK) / 16.0));
    }

    static double openingGlow(final long t) {
        return Easing.outCubic((t - (SPARK + 4)) / (double) FORM);
    }

    static double openingShards(final long t) {
        return Easing.outBack((t - (SPARK + 12)) / 24.0, 1.2);
    }

    static double openingShardRadius(final long t) {
        return 0.55 + 0.45 * Easing.outCubic((t - (SPARK + 12)) / 24.0);
    }

    static double openingSpin(final long t) {
        return SPIN_OPEN * Easing.inOutSine((t - (SPARK - 4)) / (double) (FORM + 4));
    }
}
