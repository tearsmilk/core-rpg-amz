package core.amazake.systems.coreportals.anim;

/**
 * Расписание анимации «Врат междумирья» по тикам (0 — тик появления). Неизменяемо: досрочное закрытие —
 * {@link #closeAt(long)}, новый объект.
 *
 * <pre>
 *    0..40   MATERIALIZE  рама поднимается из-под земли на 10 блоков ({@link #reveal(long)}), появляются облака
 *   24..100  BLOOM        энергетическое кольцо растёт от земли до полного проёма ({@link #aperture(long)})
 *  100..C    OPEN         открыты, можно войти; C = срок жизни − {@link #CLOSING}
 *    C..C+72 COLLAPSE     кольцо сворачивается (58 тиков), с C+28 рама уходит обратно под землю (44 тика)
 * </pre>
 *
 * <p>Обе кривые — «smootherstep» (6x⁵ − 15x⁴ + 10x³): мягкий старт и мягкая остановка. Закрытие начинается с
 * тех значений, что были на момент {@code closeStart}: врата, закрытые на середине раскрытия, сворачиваются с
 * того размера, без скачка.</p>
 *
 * @param closeStart тик начала закрытия
 */
public record GateTimeline(long closeStart) {

    /** Тик, с которого врата открыты полностью и в них можно войти. */
    public static final int OPENED = 100;
    /** Длительность закрытия, тиков. */
    public static final int CLOSING = 72;
    /** Шаг ключевых кадров рамы и кольца (тиков): каждый тик, движение сглаживает интерполяция дисплеев. */
    public static final int KEY_STEP = 1;

    /** Обычное расписание: закрытие в конце срока жизни, но не раньше 120-го тика (врата успевают раскрыться). */
    public static GateTimeline of(final long lifetime) {
        return new GateTimeline(Math.max(120L, lifetime - CLOSING));
    }

    /** Закрыть досрочно с тика {@code tick} (если закрытие ещё не началось). */
    public GateTimeline closeAt(final long tick) {
        return tick >= this.closeStart ? this : new GateTimeline(Math.max(0L, tick));
    }

    /** Всё убрано: закрытие закончилось. */
    public boolean done(final long tick) {
        return tick >= this.closeStart + CLOSING;
    }

    /** Можно войти: врата раскрылись полностью и ещё не закрываются. */
    public boolean enterable(final long tick) {
        return tick >= OPENED && tick < this.closeStart;
    }

    /** Стадия для /portals list. */
    public String stage(final long tick) {
        if (this.done(tick)) {
            return "DONE";
        }
        if (tick >= this.closeStart) {
            return "COLLAPSE";
        }
        return tick < 40L ? "MATERIALIZE" : (tick < OPENED ? "BLOOM" : "OPEN");
    }

    /** «Smootherstep» 6x⁵ − 15x⁴ + 10x³ для x, зажатого в 0..1. */
    private static double smooth(final double value) {
        final double x = Easing.clamp01(value);
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
    }

    /**
     * Насколько поднялась рама, 0..1: подъём за 40 тиков; при закрытии — с 28-го тика закрытия опускается за
     * 44 тика (сначала сворачивается кольцо, потом уходит рама).
     */
    public double reveal(final long tick) {
        if (tick < this.closeStart) {
            return smooth((double) tick / 40.0);
        }
        return smooth((double) this.closeStart / 40.0) * (1.0 - smooth((double) (tick - this.closeStart - 28L) / 44.0));
    }

    /**
     * Раскрытие энергетического кольца, 0..1: растёт с 24-го тика до {@link #OPENED} (76 тиков); при закрытии
     * сворачивается за 58 тиков.
     */
    public double aperture(final long tick) {
        if (tick < this.closeStart) {
            return smooth((double) (tick - 24L) / 76.0);
        }
        return smooth((double) (this.closeStart - 24L) / 76.0) * (1.0 - smooth((double) (tick - this.closeStart) / 58.0));
    }
}
