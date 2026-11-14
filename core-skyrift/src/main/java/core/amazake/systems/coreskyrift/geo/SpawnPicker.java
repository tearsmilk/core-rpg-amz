package core.amazake.systems.coreskyrift.geo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Места спавна мобов из разлома (x, z относительно центра события). Чистая математика — проверку земли и неба
 * над ней делает вызывающий в потоке региона.
 */
public final class SpawnPicker {

    /** Запас от краёв разлома: точка должна лежать в этой доле полуширины и полудлины. */
    public static final double INSET = 0.85;

    private SpawnPicker() {
    }

    /**
     * Кандидаты рядом с игроком ({@code px}, {@code pz} — его x, z от центра): кольцо {@code minD..maxD}. Сначала —
     * точки под разломом (в случайном порядке), потом остальные. Всегда {@code count} штук.
     */
    public static List<double[]> nearPlayer(final double px, final double pz, final RiftBand band, final double r,
                                            final double minD, final double maxD, final RandomGenerator rnd,
                                            final int count) {
        final List<double[]> inside = new ArrayList<>();
        final List<double[]> outside = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final double a = rnd.nextDouble() * Math.PI * 2;
            // Равномерно по площади кольца.
            final double d = Math.sqrt(minD * minD + rnd.nextDouble() * (maxD * maxD - minD * minD));
            final double[] p = {px + Math.cos(a) * d, pz + Math.sin(a) * d};
            (band.contains(p[0], p[1], r, INSET) ? inside : outside).add(p);
        }
        inside.addAll(outside);
        return inside;
    }

    /**
     * Точки строго под разломом и не дальше {@code radius} от центра (чтобы мобы падали туда, где их видят).
     * Пусто, если разлом ещё трещина.
     */
    public static List<double[]> underRift(final RiftBand band, final double r, final double radius,
                                           final RandomGenerator rnd, final int count) {
        final List<double[]> out = new ArrayList<>();
        if (RiftBand.openFrac(r) <= 0) {
            return out;
        }
        for (int i = 0; i < count * 4 && out.size() < count; i++) {
            final double fu = (rnd.nextDouble() * 2 - 1) * INSET;
            final double fv = (rnd.nextDouble() * 2 - 1) * INSET;
            final double[] p = band.point(fu, fv, r);
            if (p[0] * p[0] + p[1] * p[1] <= radius * radius && band.contains(p[0], p[1], r, 0.999)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Случайное число из {@code min..max} включительно. */
    public static int between(final int min, final int max, final RandomGenerator rnd) {
        return max <= min ? min : min + rnd.nextInt(max - min + 1);
    }

    /** Взвешенный выбор: {@code weights} — веса по порядку; −1, если все нули. */
    public static int weighted(final int[] weights, final RandomGenerator rnd) {
        long total = 0;
        for (final int w : weights) {
            total += Math.max(0, w);
        }
        if (total <= 0) {
            return -1;
        }
        long pick = (long) (rnd.nextDouble() * total);
        for (int i = 0; i < weights.length; i++) {
            pick -= Math.max(0, weights[i]);
            if (pick < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /** Для тестов: генератор с зерном. */
    public static RandomGenerator seeded(final long seed) {
        return new Random(seed);
    }
}
