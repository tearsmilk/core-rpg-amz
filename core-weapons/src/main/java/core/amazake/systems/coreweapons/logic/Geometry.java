package core.amazake.systems.coreweapons.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Чистая геометрия эффектов (без сервера): базис игрока по повороту тела, точки кольца частиц, дуга следа замаха,
 * столбцы блоков для волны дрожи. Мир: x — восток, y — вверх, z — юг; yaw Minecraft: 0 — на юг (+z), 90 — на запад.
 */
public final class Geometry {

    private Geometry() {
    }

    /** Вектор вперёд (горизонтальный) для yaw в градусах. */
    public static double[] forward(final float yaw) {
        final double r = Math.toRadians(yaw);
        return new double[] {-Math.sin(r), 0, Math.cos(r)};
    }

    /** Вектор вправо (горизонтальный) для yaw: вперёд × вверх. */
    public static double[] right(final float yaw) {
        final double[] f = forward(yaw);
        return new double[] {-f[2], 0, f[0]};
    }

    /** Смещение от ног: right/up/forward в мировые dx, dy, dz. */
    public static double[] local(final float yaw, final double right, final double up, final double fwd) {
        final double[] f = forward(yaw);
        final double[] r = right(yaw);
        return new double[] {r[0] * right + f[0] * fwd, up, r[2] * right + f[2] * fwd};
    }

    /**
     * Точки кольца вокруг лезвия на тике {@code tick}: {@code points} частиц через равные углы, кольцо наклонено на
     * {@code tilt} градусов к игроку и качается на {@code bob}. Возвращает смещения от центра (мировые dx, dy, dz).
     */
    public static List<double[]> orbit(final long tick, final int points, final double radius, final double degPerTick,
                                       final double tilt, final double bob, final float yaw) {
        final List<double[]> out = new ArrayList<>(points);
        final double[] f = forward(yaw);
        final double[] r = right(yaw);
        final double t = Math.toRadians(tilt);
        for (int i = 0; i < points; i++) {
            final double a = Math.toRadians(tick * degPerTick + 360.0 * i / points);
            final double ca = Math.cos(a) * radius;
            final double sa = Math.sin(a) * radius;
            // кольцо в горизонтальной плоскости (right, forward), наклонённое вокруг оси right
            final double up = sa * Math.sin(t) + bob * Math.sin(tick * 0.25 + i * 2.1);
            final double fw = sa * Math.cos(t);
            out.add(new double[] {r[0] * ca + f[0] * fw, up, r[2] * ca + f[2] * fw});
        }
        return out;
    }

    /**
     * Точка дуги следа: удар сверху справа через верх влево вниз, в плоскости перед глазами. {@code s} 0..1 по дуге.
     * Направление взгляда — {@code look} (единичный, с наклоном), {@code yaw} — для «вправо».
     */
    public static double[] arc(final double s, final double radius, final double distance, final double[] look,
                               final float yaw) {
        final double[] r = right(yaw);
        // вверх относительно взгляда: right × look
        final double[] up = {r[1] * look[2] - r[2] * look[1], r[2] * look[0] - r[0] * look[2], r[0] * look[1] - r[1] * look[0]};
        final double phi = Math.toRadians(60 + 180 * s);
        final double c = Math.cos(phi) * radius;
        final double sn = Math.sin(phi) * radius;
        final double d = distance + 0.25 * Math.sin(Math.PI * s); // середина дуги чуть дальше
        return new double[] {look[0] * d + r[0] * c + up[0] * sn, look[1] * d + r[1] * c + up[1] * sn,
            look[2] * d + r[2] * c + up[2] * sn};
    }

    /** Столбцы (dx, dz) от центра, у которых расстояние до центра в [from, to). */
    public static List<int[]> band(final double from, final double to) {
        final List<int[]> out = new ArrayList<>();
        final int m = (int) Math.ceil(to);
        for (int dx = -m; dx <= m; dx++) {
            for (int dz = -m; dz <= m; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                if (d >= from && d < to) {
                    out.add(new int[] {dx, dz});
                }
            }
        }
        return out;
    }

    /** Радиус волны на шаге {@code step} (0..duration−1): растёт от 0 до {@code radius}, к концу замедляется. */
    public static double waveRadius(final int step, final int duration, final double radius) {
        final double x = Math.max(0, Math.min(1, (step + 1.0) / duration));
        return radius * (1 - (1 - x) * (1 - x));
    }

    /** Высота подскока копии блока на тике её жизни: вверх за 2 тика, вниз за 3, потом 0. */
    public static double shake(final int age, final double height) {
        if (age <= 0 || age >= 5) {
            return 0;
        }
        return age <= 2 ? height * age / 2.0 : height * (5 - age) / 3.0;
    }
}
