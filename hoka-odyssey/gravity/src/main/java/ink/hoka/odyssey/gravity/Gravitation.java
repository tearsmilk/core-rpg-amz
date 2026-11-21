package ink.hoka.odyssey.gravity;

import java.util.List;

/**
 * Чистая математика притяжения чёрных дыр и дрейфа (без Minestom).
 * <p>
 * Закон притяжения: у края горизонта ускорение = pull, дальше ослабевает как pull·(horizon/d)^falloff, но на всём
 * радиусе действия не меньше minPull (чтобы в пределах reach всё медленно сносило к дыре); за reach — 0.
 * <p>
 * Шаг скорости ({@link #step}): составляющая к дыре разгоняется на a за шаг до предела cap и не гасится (падение),
 * летящая от дыры — гасится и разворачивается, а поперечная — гасится как обычная инерция (keep). Поэтому дрейф
 * плавный и не разгоняется бесконечно.
 */
final class Gravitation {
    /** Тормозной путь игрока у горизонта (режим none): скорость к дыре не больше BRAKE·(d − horizon), блоков/с на блок. */
    static final double BRAKE = 0.5;

    private Gravitation() {}

    /** Ускорение притяжения (блоков/с²) на расстоянии d от центра; 0 — дальше reach. */
    static double accel(BlackHole h, double d, double falloff, double minPull) {
        if (!(d <= h.reach()) || h.pull() <= 0) {
            return 0;
        }
        if (d <= h.horizon() || h.horizon() <= 0) {
            return h.pull();
        }
        double a = h.pull() * Math.pow(h.horizon() / d, Math.max(0, falloff));
        return Math.max(a, Math.min(minPull, h.pull()));
    }

    /** Ближайшая дыра, в радиус действия которой попадает точка; null — ни одной. */
    static BlackHole nearest(List<BlackHole> holes, double x, double y, double z) {
        BlackHole best = null;
        double bestD = Double.MAX_VALUE;
        for (BlackHole h : holes) {
            double d = h.distance(x, y, z);
            if (d <= h.reach() && d < bestD) {
                best = h;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * Сила дрейфа: единичное направление n, ускорение a за шаг и предел скорости cap вдоль n (единицы — какие
     * передали: у игрока блоки/тик² и блоки/тик).
     */
    record Force(double nx, double ny, double nz, double a, double cap) {}

    /** Сила к дыре от точки; null — точка в самом центре. */
    static Force toward(BlackHole h, double x, double y, double z, double a, double cap) {
        double[] n = unit(h.x() - x, h.y() - y, h.z() - z);
        return n == null ? null : new Force(n[0], n[1], n[2], a, cap);
    }

    /**
     * Сила дрейфа игрока без клавиш, блоки/тик. Дыра в пределах reach — к ней (ускорение pull·scale, предел
     * maxDrift; если keepOut — у горизонта тормозит до нуля и внутрь не тянет). Иначе — постоянный дрейф
     * driftDir до скорости driftSpeed с ускорением driftAccel (driftSpeed 0 — нет дрейфа, null). Вход — в секундах.
     */
    static Force playerForce(List<BlackHole> holes, double x, double y, double z, double falloff, double minPull,
                             double scale, double maxDrift, boolean keepOut, double[] driftDir, double driftSpeed,
                             double driftAccel) {
        BlackHole h = nearest(holes, x, y, z);
        double t2 = Settings.TPS * Settings.TPS;
        if (h != null) {
            double d = h.distance(x, y, z);
            double cap = maxDrift;
            if (keepOut) {
                cap = Math.min(cap, Math.max(0, BRAKE * (d - h.horizon())));
            }
            double a = accel(h, d, falloff, minPull) * scale;
            if (a <= 0 && cap <= 0) {
                return null;
            }
            return toward(h, x, y, z, a / t2, cap / Settings.TPS);
        }
        if (driftSpeed <= 0 || driftDir == null) {
            return null;
        }
        return new Force(driftDir[0], driftDir[1], driftDir[2], driftAccel / t2, driftSpeed / Settings.TPS);
    }

    /**
     * Скорость после шага дрейфа.
     *
     * @param v     текущая скорость {x, y, z}
     * @param f     сила
     * @param keep  какая доля скорости остаётся за шаг по осям {x, y, z} (инерция)
     * @param start наименьшая скорость к цели, с которой начинать (клиент Minecraft обнуляет скорость меньше
     *              0,003 блока/тик — с меньшей дрейф не сдвинулся бы с места); 0 — без этого
     */
    static double[] step(double[] v, Force f, double[] keep, double start) {
        double vn = v[0] * f.nx() + v[1] * f.ny() + v[2] * f.nz();
        double kn = keep[0] * f.nx() * f.nx() + keep[1] * f.ny() * f.ny() + keep[2] * f.nz() * f.nz();
        double px = (v[0] - vn * f.nx()) * keep[0];
        double py = (v[1] - vn * f.ny()) * keep[1];
        double pz = (v[2] - vn * f.nz()) * keep[2];
        double cap = Math.max(0, f.cap());
        double a = Math.max(0, f.a());
        double next;
        if (vn > cap) {
            next = Math.max(cap, Math.min(vn * kn, vn - a));     // быстрее предела — тормозим к пределу
        } else if (vn < 0) {
            next = Math.min(cap, vn * kn + a);                   // летит от дыры — гасим и разворачиваем
        } else {
            next = Math.min(cap, vn + a);                        // падает к дыре — разгон до предела
            if (next < start && a > 0) {
                next = Math.min(cap, start);
            }
        }
        return new double[] {px + next * f.nx(), py + next * f.ny(), pz + next * f.nz()};
    }

    /**
     * Скорость предмета (блоки/тик) через ticks тиков: без гравитации, затухание keep за тик, в радиусе дыры h
     * (null — нет) — падение к ней с ускорением по {@link #accel} до её max-speed.
     */
    static double[] itemVelocity(double[] v, BlackHole h, double x, double y, double z, double falloff,
                                 double minPull, double keepPerTick, int ticks) {
        double k = Math.pow(keepPerTick, ticks);
        Force f = null;
        if (h != null) {
            double a = accel(h, h.distance(x, y, z), falloff, minPull) / (Settings.TPS * Settings.TPS) * ticks;
            f = toward(h, x, y, z, a, h.maxSpeed() / Settings.TPS);
        }
        if (f == null) {
            return new double[] {v[0] * k, v[1] * k, v[2] * k};
        }
        return step(v, f, new double[] {k, k, k}, 0);
    }

    /** Единичный вектор; нулевой — null. */
    static double[] unit(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return l < 1e-9 ? null : new double[] {x / l, y / l, z / l};
    }
}
