package core.amazake.systems.coreskyrift.geo;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Форма разлома на его плоскости (координаты x, z относительно центра события, блоки) — та же, что рисует шейдер
 * ({@code sr_rift_shape} в {@code skyrift_fragment.glsl}), но без рваности краёв. Нужна, чтобы мобы падали
 * из-под разлома: точка на земле под разломом — та, чьи x, z лежат внутри полосы.
 *
 * <p>Полоса вдоль оси {@link #axisX()}, {@link #axisZ()}: длина {@link #length()}, наибольшая полуширина
 * {@link #halfWidth()}. Раскрытость {@code r}: 0..0,2 — трещина растёт от центра (длина от 0 до полной, полуширина
 * {@link #THIN}), 0,2..1 — края рвутся по профилю {@code (1 − s²)^0,65}.</p>
 *
 * <p>Ось и размер шейдер получает из нормали квадрата дисплея: поворот дисплея {@link #rotation} =
 * {@code rotY(yaw) · rotX(tilt)}, нормаль модели после встроенного поворота ItemDisplay на 180° — (0, 0, 1). Ось —
 * горизонталь нормали, размер — её Y ({@link #tiltY}).</p>
 */
public record RiftBand(double length, double halfWidth, double axisX, double axisZ) {

    /** Длина и полуширина при size = 1 (как в шейдере: SR_LENGTH, SR_HALF_WIDTH). */
    public static final double BASE_LENGTH = 480;
    public static final double BASE_HALF_WIDTH = 110;
    /** Полуширина тонкой трещины, блоки (шейдер: SR_THIN). */
    public static final double THIN = 1.5;
    public static final double MIN_SIZE = 0.25;
    public static final double MAX_SIZE = 2.0;
    /** Наибольший Y нормали (наклон), которым кодируется размер (шейдер: SR_TILT_MAX). */
    public static final double TILT_MAX = 0.9;
    /** Нормаль приходит в шейдер байтом со знаком: шаг 1/127. */
    public static final int NORMAL_STEPS = 127;
    public static final double CRACK_END = 0.2;
    /** Показатель профиля краёв (шейдер: SR_PROFILE). */
    public static final double PROFILE = 0.65;

    /** Полоса для размера {@code size} (будет округлён, как его увидит шейдер) и направления {@code yawDeg}. */
    public static RiftBand of(final double size, final double yawDeg) {
        final double s = quantizedSize(size);
        final Vector3f n = rotation(yawDeg, size).transform(new Vector3f(0, 0, 1));
        final double len = Math.hypot(n.x, n.z);
        return new RiftBand(BASE_LENGTH * s, BASE_HALF_WIDTH * s, n.x / len, n.z / len);
    }

    /** Номер шага размера 0..114: столько 1/127 будет в Y нормали, который увидит шейдер. */
    public static int tiltIndex(final double size) {
        final double clamped = Math.max(MIN_SIZE, Math.min(MAX_SIZE, Double.isNaN(size) ? 1 : size));
        return (int) Math.round(TILT_MAX * NORMAL_STEPS * (clamped - MIN_SIZE) / (MAX_SIZE - MIN_SIZE));
    }

    /**
     * Y нормали, который ставит сервер: (k + 0,5) / 127. Клиент упаковывает нормаль в байт отбрасыванием дробной
     * части ({@code (int) (y · 127)}), так что в шейдер приходит ровно k / 127 и с запасом от ошибок float.
     */
    public static double tiltY(final double size) {
        return (tiltIndex(size) + 0.5) / NORMAL_STEPS;
    }

    /** Размер, который прочитает шейдер: {@code 0,25 + 1,75 · (k / 127) / 0,9}. */
    public static double quantizedSize(final double size) {
        return MIN_SIZE + (MAX_SIZE - MIN_SIZE) * (tiltIndex(size) / (double) NORMAL_STEPS) / TILT_MAX;
    }

    /** Поворот дисплея неба: направление разлома и наклон нормали (размер). */
    public static Quaternionf rotation(final double yawDeg, final double size) {
        final float tilt = (float) Math.asin(tiltY(size));
        return new Quaternionf().rotateY((float) Math.toRadians(yawDeg)).rotateX(-tilt);
    }

    /** Доля длины трещины 0..1. */
    public static double lengthFrac(final double r) {
        return r >= CRACK_END ? 1 : Math.max(0, r / CRACK_END);
    }

    /** Доля раскрытия краёв 0..1. */
    public static double openFrac(final double r) {
        return r <= CRACK_END ? 0 : Math.min(1, (r - CRACK_END) / (1 - CRACK_END));
    }

    /** Координата вдоль оси. */
    public double along(final double x, final double z) {
        return x * this.axisX + z * this.axisZ;
    }

    /** Координата поперёк оси. */
    public double across(final double x, final double z) {
        return -x * this.axisZ + z * this.axisX;
    }

    /** Полудлина при раскрытости {@code r}. */
    public double halfLength(final double r) {
        return this.length / 2 * lengthFrac(r);
    }

    /** Полуширина в точке {@code s} (−1..1 вдоль текущей длины) при раскрытости {@code r}. */
    public double halfWidthAt(final double s, final double r) {
        final double q = Math.max(0, 1 - s * s);
        final double thin = THIN * Math.min(1, 0.4 + q);
        return thin + this.halfWidth * openFrac(r) * Math.pow(q, PROFILE);
    }

    /** Точка (x, z от центра) под разломом, с запасом {@code inset} (0..1 — доля полуширины). */
    public boolean contains(final double x, final double z, final double r, final double inset) {
        final double hl = this.halfLength(r);
        if (hl <= 0) {
            return false;
        }
        final double u = this.along(x, z);
        if (Math.abs(u) >= hl * inset) {
            return false;
        }
        return Math.abs(this.across(x, z)) < this.halfWidthAt(u / hl, r) * inset;
    }

    /** Точка разлома (x, z от центра) по долям: {@code fu} −1..1 вдоль, {@code fv} −1..1 поперёк. */
    public double[] point(final double fu, final double fv, final double r) {
        final double hl = this.halfLength(r);
        final double u = fu * hl;
        final double v = fv * this.halfWidthAt(fu, r);
        return new double[] {u * this.axisX - v * this.axisZ, u * this.axisZ + v * this.axisX};
    }
}
