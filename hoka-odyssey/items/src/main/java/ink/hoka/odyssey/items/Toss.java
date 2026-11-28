package ink.hoka.odyssey.items;

import java.util.random.RandomGenerator;

/**
 * Чистая математика броска — как {@code Player.drop} в ванили: предмет появляется на 0,3 блока ниже глаз и летит по
 * взгляду со скоростью {@code speed} блоков/тик, чуть вверх (+0,1) и с небольшим случайным разбросом.
 */
final class Toss {
    /** Насколько ниже глаз появляется предмет (ваниль). */
    static final double BELOW_EYES = 0.3;
    /** Случайный разброс в стороны, блоков/тик (ваниль: до 0,02). */
    static final double SIDE_SPREAD = 0.02;
    /** Случайный разброс по вертикали, блоков/тик (ваниль: ±0,1). */
    static final double UP_SPREAD = 0.1;
    /** Подброс вверх, блоков/тик (ваниль). */
    static final double LIFT = 0.1;

    private Toss() {
    }

    /**
     * Скорость брошенного предмета, блоков/тик.
     *
     * @param yaw    поворот игрока, градусы (Minecraft: 0 — на +Z, 90 — на −X)
     * @param pitch  наклон, градусы (−90 — вверх, 90 — вниз)
     * @param speed  скорость броска, блоков/тик (ваниль 0,3)
     * @param random null — без разброса
     * @return {x, y, z}
     */
    static double[] velocity(float yaw, float pitch, double speed, RandomGenerator random) {
        double ry = Math.toRadians(yaw);
        double rp = Math.toRadians(pitch);
        double sinP = Math.sin(rp);
        double cosP = Math.cos(rp);
        double x = -Math.sin(ry) * cosP * speed;
        double y = -sinP * speed + LIFT;
        double z = Math.cos(ry) * cosP * speed;
        if (random != null) {
            double angle = random.nextDouble() * Math.PI * 2;
            double side = SIDE_SPREAD * random.nextDouble();
            x += Math.cos(angle) * side;
            z += Math.sin(angle) * side;
            y += (random.nextDouble() - random.nextDouble()) * UP_SPREAD;
        }
        return new double[] {x, y, z};
    }
}
