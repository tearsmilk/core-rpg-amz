package core.amazake.systems.coreportals.anim;

/**
 * Кривые сглаживания для анимации портала. Аргумент обрезается до 0..1, результат: f(0) = 0, f(1) = 1.
 *
 * <p>Клиент интерполирует трансформацию дисплея линейно, поэтому плавность задаётся здесь: сервер шлёт ключевой
 * кадр каждые {@link Timeline#KEY_STEP} тика, а значения берёт с этих кривых.</p>
 */
public final class Easing {

    private Easing() {
    }

    public static double clamp01(final double x) {
        return x < 0 ? 0 : (x > 1 ? 1 : x);
    }

    public static double linear(final double x) {
        return clamp01(x);
    }

    public static double inQuad(final double x) {
        final double t = clamp01(x);
        return t * t;
    }

    public static double outQuad(final double x) {
        final double t = clamp01(x);
        return 1 - (1 - t) * (1 - t);
    }

    public static double inCubic(final double x) {
        final double t = clamp01(x);
        return t * t * t;
    }

    public static double outCubic(final double x) {
        final double t = 1 - clamp01(x);
        return 1 - t * t * t;
    }

    public static double inOutCubic(final double x) {
        final double t = clamp01(x);
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    public static double inOutSine(final double x) {
        return -(Math.cos(Math.PI * clamp01(x)) - 1) / 2;
    }

    /** С лёгким перелётом за 1 в конце (≈ +6 % при s = 1.2). */
    public static double outBack(final double x, final double s) {
        final double t = clamp01(x) - 1;
        return 1 + (s + 1) * t * t * t + s * t * t;
    }
}
