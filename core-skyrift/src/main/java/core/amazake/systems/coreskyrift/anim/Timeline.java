package core.amazake.systems.coreskyrift.anim;

import core.amazake.systems.coreskyrift.Settings;

/**
 * Время события разлома: стадии и два числа, которые рисует шейдер — {@link #fog} (плотность чёрного тумана,
 * маркер 96) и {@link #rift} (раскрытость разлома, маркер 97: 0..0,2 — трещина растёт, 0,2..1 — края рвутся),
 * плюс {@link #flash} (красная вспышка на экране, маркер 98). Чистая функция тика: её считают и глобальный поток
 * (волны мобов), и потоки игроков (байты дисплея, звуки), каждый по общему тику события.
 *
 * <p>Стадии (тики): каст 0 → туман {@code fogIn} → трещина {@code crack} → раскрытие {@code opening} → открыт
 * {@code active} → закрытие {@code closing} (с {@link #closeTick}) → туман уходит {@code fogOut}. Досрочное
 * закрытие ({@link #closeAt}) переносит {@link #closeTick} на «сейчас»: разлом сходится из того вида, какой был.</p>
 */
public record Timeline(long fogIn, long crack, long opening, long active, long closing, long fogOut,
                       long closeTick, double riftAtClose, double fogAtClose) {

    /** Раскрытость, на которой трещина дорастает во всю длину и начинают рваться края. */
    public static final double CRACK_END = 0.2;
    /** Шаг гула при раскрытии, тики. */
    public static final long RUMBLE_STEP = 60;
    /** Сколько рывков при раскрытии и их сила (0..1, меньше 1 — раскрытость не убывает). */
    static final int TEAR_PULSES = 6;
    static final double TEAR_AMOUNT = 0.7;

    /** Стадия (для /skyrift list и звуков). */
    public enum Stage { FOG_IN, CRACK, OPENING, ACTIVE, CLOSING, FOG_OUT, DONE }

    /** Звуковые/визуальные моменты. */
    public enum Cue { CAST, CRACK, RUMBLE, OPEN, CLOSE, FADE }

    public static Timeline of(final Settings.Times t) {
        return of(Settings.ticks(t.fogIn()), Settings.ticks(t.crack()), Settings.ticks(t.opening()),
            Math.max(0, Math.round(t.active() * 20)), Settings.ticks(t.closing()), Settings.ticks(t.fogOut()));
    }

    public static Timeline of(final long fogIn, final long crack, final long opening, final long active,
                              final long closing, final long fogOut) {
        final long close = fogIn + crack + opening + active;
        return new Timeline(fogIn, crack, opening, active, closing, fogOut, close, 1.0, 1.0);
    }

    public long crackStart() {
        return this.fogIn;
    }

    public long openingStart() {
        return this.fogIn + this.crack;
    }

    public long activeStart() {
        return this.fogIn + this.crack + this.opening;
    }

    public long fogOutStart() {
        return this.closeTick + this.closing;
    }

    /** Последний тик события (после него всё убирается). */
    public long total() {
        return this.fogOutStart() + this.fogOut;
    }

    public boolean done(final long t) {
        return t >= this.total();
    }

    public boolean closing(final long t) {
        return t >= this.closeTick;
    }

    /** Досрочное закрытие с тика {@code t}: из текущего вида. Уже закрывается — без изменений. */
    public Timeline closeAt(final long t) {
        if (t >= this.closeTick) {
            return this;
        }
        final long at = Math.max(0, t);
        return new Timeline(this.fogIn, this.crack, this.opening, this.active, this.closing, this.fogOut, at,
            this.naturalRift(at), this.naturalFog(at));
    }

    public Stage stage(final long t) {
        if (t >= this.total()) {
            return Stage.DONE;
        }
        if (t >= this.fogOutStart()) {
            return Stage.FOG_OUT;
        }
        if (t >= this.closeTick) {
            return Stage.CLOSING;
        }
        if (t >= this.activeStart()) {
            return Stage.ACTIVE;
        }
        if (t >= this.openingStart()) {
            return Stage.OPENING;
        }
        if (t >= this.crackStart()) {
            return Stage.CRACK;
        }
        return Stage.FOG_IN;
    }

    // ------------------------------------------------------------------------------------------- значения

    /** Плотность чёрного тумана 0..1. */
    public double fog(final long t) {
        if (t < this.fogOutStart()) {
            return t < this.closeTick ? this.naturalFog(t) : this.fogAtClose;
        }
        final double x = (double) (t - this.fogOutStart()) / this.fogOut;
        return this.fogAtClose * (1 - Easing.inOutSine(x));
    }

    /** Раскрытость разлома 0..1 (0..{@link #CRACK_END} — трещина растёт, дальше — края рвутся). */
    public double rift(final long t) {
        if (t < this.closeTick) {
            return this.naturalRift(t);
        }
        final double from = this.riftAtClose;
        final double x = (double) (t - this.closeTick) / this.closing;
        if (x >= 1) {
            return 0;
        }
        if (from <= CRACK_END) {
            return from * (1 - Easing.inQuad(x));
        }
        // 60 % времени — края сходятся в трещину, 40 % — трещина стягивается к центру и гаснет.
        if (x < 0.6) {
            return CRACK_END + (from - CRACK_END) * (1 - Easing.inOutCubic(x / 0.6));
        }
        return CRACK_END * (1 - Easing.inQuad((x - 0.6) / 0.4));
    }

    /** Красная вспышка на экране 0..1: удар в момент трещины и слабее — когда разлом схлопывается. */
    public double flash(final long t) {
        final double a = pulse(t - this.crackStart(), 30);
        final long snap = this.closeTick + Math.round(this.closing * 0.9);
        final double b = this.riftAtClose > CRACK_END || this.closeTick >= this.activeStart()
            ? 0.6 * pulse(t - snap, 24) : 0;
        return Math.min(1, Math.max(a, b));
    }

    /** Остаток события для боссбара 1..0. */
    public double remaining(final long t) {
        final long total = this.total();
        return total <= 0 ? 0 : Easing.clamp01(1 - (double) t / total);
    }

    /** Волны мобов идут: до закрытия и когда разлом раскрыт не меньше {@code startAtOpen}. */
    public boolean wavesActive(final long t, final double startAtOpen) {
        return t < this.closeTick && t >= this.openingStart() && this.rift(t) >= Math.max(startAtOpen, 0.001);
    }

    /** Момент на тике {@code t} (или {@code null}). */
    public Cue cue(final long t) {
        if (t == 0) {
            return Cue.CAST;
        }
        if (t == this.closeTick) {
            return Cue.CLOSE;
        }
        if (t == this.fogOutStart()) {
            return Cue.FADE;
        }
        if (t >= this.closeTick) {
            return null;
        }
        if (t == this.crackStart()) {
            return Cue.CRACK;
        }
        if (t == this.activeStart()) {
            return Cue.OPEN;
        }
        if (t > this.openingStart() && t < this.activeStart() && (t - this.openingStart()) % RUMBLE_STEP == 0) {
            return Cue.RUMBLE;
        }
        return null;
    }

    private double naturalFog(final long t) {
        return t >= this.fogIn ? 1 : Easing.outCubic((double) t / this.fogIn);
    }

    private double naturalRift(final long t) {
        if (t < this.crackStart()) {
            return 0;
        }
        if (t < this.openingStart()) {
            return CRACK_END * Easing.outQuad((double) (t - this.crackStart()) / this.crack);
        }
        if (t < this.activeStart()) {
            final double x = (double) (t - this.openingStart()) / this.opening;
            // Рывки: края рвутся толчками. x - a·sin(2πkx)/(2πk) при a < 1 строго растёт и даёт 0 и 1 на концах,
            // так что раскрытость никогда не убывает, а скорость «пульсирует» k раз.
            final double k = TEAR_PULSES;
            final double inner = x - TEAR_AMOUNT * Math.sin(2 * Math.PI * k * x) / (2 * Math.PI * k);
            return CRACK_END + (1 - CRACK_END) * Easing.inOutSine(inner);
        }
        return 1;
    }

    private static double pulse(final long dt, final long length) {
        if (dt < 0 || dt >= length) {
            return 0;
        }
        final double x = (double) dt / length;
        return x < 0.12 ? x / 0.12 : Math.pow(1 - (x - 0.12) / 0.88, 2);
    }
}
