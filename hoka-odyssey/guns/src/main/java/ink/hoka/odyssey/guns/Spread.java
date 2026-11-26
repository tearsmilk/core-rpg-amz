package ink.hoka.odyssey.guns;

import java.util.random.RandomGenerator;

/**
 * Разброс: «неточность» в градусах по состоянию стрелка и случайное отклонение направления. Отклонение — к каждой оси
 * единичного направления прибавляется треугольно распределённая случайная величина в пределах ±K·неточность
 * (K = 0,0172275 — так ванильные снаряды переводят «неточность» в смещение: примерно ±неточность градусов), потом
 * вектор нормируется. Без Minestom.
 */
final class Spread {
    /** Смещение оси на единицу неточности (≈ tan 1° ≈ 0,01745; ванильная константа снарядов). */
    static final double K = 0.0172275;

    private Spread() {
    }

    /** Состояние стрелка для разброса. */
    record Stance(boolean aiming, boolean vacuum, boolean sneaking, boolean moving) {}

    /** Неточность, градусы: прицел → aim; вакуум → vacuum; присел → sneak; движется/в прыжке → move; иначе stand. */
    static double inaccuracy(GunDef g, Stance s) {
        if (s.aiming()) {
            return g.spreadAim();
        }
        if (s.vacuum()) {
            return g.spreadVacuum();
        }
        if (s.sneaking()) {
            return g.spreadSneak();
        }
        if (s.moving()) {
            return g.spreadMove();
        }
        return g.spreadStand();
    }

    /** Треугольное распределение на [−1; 1] (сумма двух равномерных). */
    static double triangle(RandomGenerator r) {
        return r.nextDouble() - r.nextDouble();
    }

    /** Отклонить единичное направление d на неточность inacc (градусы). Возвращает новый единичный вектор. */
    static double[] apply(double dx, double dy, double dz, double inacc, RandomGenerator r) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) {
            return new double[] {0, 0, 1};
        }
        double x = dx / len;
        double y = dy / len;
        double z = dz / len;
        if (inacc > 0) {
            double k = K * inacc;
            x += triangle(r) * k;
            y += triangle(r) * k;
            z += triangle(r) * k;
            double l2 = Math.sqrt(x * x + y * y + z * z);
            x /= l2;
            y /= l2;
            z /= l2;
        }
        return new double[] {x, y, z};
    }

    /** Наибольший угол отклонения (градусы), который может дать {@link #apply} при неточности inacc. */
    static double maxAngle(double inacc) {
        double k = K * inacc;
        // худший случай: смещение k по каждой оси перпендикулярно (|смещение| ≤ k·√3)
        return Math.toDegrees(Math.atan(k * Math.sqrt(3) / Math.max(1e-9, 1 - k * Math.sqrt(3))));
    }
}
