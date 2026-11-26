package ink.hoka.odyssey.guns;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Полёт пули без сущности: каждый тик — отрезок от текущей точки на скорость (блоков/тик); на отрезке ищем ближайший
 * блок (проход по клеткам 3D-DDA и пересечение луча с рамками формы блока) и цели (луч против рамок сущностей).
 * Цели ближе блока поражаются по порядку (пробитие pierce), блок останавливает пулю. Потом трение и тяжесть
 * (в вакууме — нет). Чистая математика: мир подставляется через {@link World} (в тестах — свой).
 */
final class Ballistics {
    private Ballistics() {
    }

    /** Пересечение луча с рамкой: t — расстояние вдоль единичного направления, n — нормаль грани входа. */
    record Hit(double t, double nx, double ny, double nz) {}

    /**
     * Луч o + t·d (d — единичный), 0 ≤ t ≤ maxT, против рамки [min; max]. null — мимо. Если начало внутри рамки —
     * попадание при t = 0 с нормалью против движения.
     */
    static Hit rayBox(double ox, double oy, double oz, double dx, double dy, double dz,
                      double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double maxT) {
        double tNear = Double.NEGATIVE_INFINITY;
        double tFar = Double.POSITIVE_INFINITY;
        int axis = -1;
        double[] o = {ox, oy, oz};
        double[] d = {dx, dy, dz};
        double[] lo = {minX, minY, minZ};
        double[] hi = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < lo[i] || o[i] > hi[i]) {
                    return null;
                }
                continue;
            }
            double t1 = (lo[i] - o[i]) / d[i];
            double t2 = (hi[i] - o[i]) / d[i];
            if (t1 > t2) {
                double s = t1;
                t1 = t2;
                t2 = s;
            }
            if (t1 > tNear) {
                tNear = t1;
                axis = i;
            }
            tFar = Math.min(tFar, t2);
            if (tNear > tFar || tFar < 0) {
                return null;
            }
        }
        if (tNear > maxT) {
            return null;
        }
        double t = Math.max(0, tNear);
        double nx = 0;
        double ny = 0;
        double nz = 0;
        if (tNear < 0 || axis < 0) {                 // начали внутри — нормаль против движения
            return new Hit(0, -dx, -dy, -dz);
        }
        double s = -Math.signum(d[axis]);
        if (axis == 0) {
            nx = s;
        } else if (axis == 1) {
            ny = s;
        } else {
            nz = s;
        }
        return new Hit(t, nx, ny, nz);
    }

    /** Мир для пули. */
    interface World {
        /**
         * Рамки формы блока в клетке (x, y, z), координаты внутри клетки 0..1: {minX, minY, minZ, maxX, maxY, maxZ}.
         * Пустой список — проходимо; null — клетка не прогружена (пуля исчезает).
         */
        List<double[]> blockBoxes(int x, int y, int z);

        /** Цели около отрезка: центр и радиус. */
        List<Target> targets(double cx, double cy, double cz, double radius);
    }

    /** Цель: рамка в мире и уровень глаз (для попадания в голову). */
    interface Target {
        double minX();

        double minY();

        double minZ();

        double maxX();

        double maxY();

        double maxZ();

        double eyeY();

        Object handle();
    }

    /** Попадание в блок на отрезке. unloaded — дошли до непрогруженной клетки (t — до её границы). */
    record BlockHit(int x, int y, int z, Hit hit, boolean unloaded) {}

    /** Пройти клетки по лучу (единичное d) на длину len, первая клетка с пересечением формы — результат (или null). */
    static BlockHit traceBlocks(World w, double ox, double oy, double oz, double dx, double dy, double dz, double len) {
        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int sy = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tdy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tdz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tmx = sx == 0 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 - ox : ox - x) * tdx);
        double tmy = sy == 0 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 - oy : oy - y) * tdy);
        double tmz = sz == 0 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 - oz : oz - z) * tdz);
        double tCell = 0;
        int guard = (int) Math.ceil(len) * 3 + 8;
        while (tCell <= len && guard-- > 0) {
            List<double[]> boxes = w.blockBoxes(x, y, z);
            if (boxes == null) {
                return new BlockHit(x, y, z, new Hit(tCell, -dx, -dy, -dz), true);
            }
            Hit best = null;
            for (double[] b : boxes) {
                Hit h = rayBox(ox, oy, oz, dx, dy, dz, x + b[0], y + b[1], z + b[2], x + b[3], y + b[4], z + b[5], len);
                if (h != null && (best == null || h.t() < best.t())) {
                    best = h;
                }
            }
            if (best != null) {
                return new BlockHit(x, y, z, best, false);
            }
            if (tmx < tmy && tmx < tmz) {
                tCell = tmx;
                tmx += tdx;
                x += sx;
            } else if (tmy < tmz) {
                tCell = tmy;
                tmy += tdy;
                y += sy;
            } else {
                tCell = tmz;
                tmz += tdz;
                z += sz;
            }
        }
        return null;
    }

    /** Что случилось с пулей за шаг. */
    interface Listener {
        /** Попадание в цель: точка, нормаль, расстояние от вылета (блоков), в голову ли. */
        void target(Target t, double x, double y, double z, double nx, double ny, double nz, double fromStart, boolean head);

        /** Попадание в блок (bx, by, bz) в точке (x, y, z). */
        void block(int bx, int by, int bz, double x, double y, double z, double nx, double ny, double nz);
    }

    /** Одна пуля. Скорость — блоков/тик. */
    static final class Bullet {
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        int ticksLeft;
        int pierceLeft;
        double traveled;
        final double gravity;
        final double friction;
        final Set<Object> hit = new HashSet<>();
        /** Кого не трогать (стрелок). */
        final Object ignore;
        boolean dead;

        Bullet(double x, double y, double z, double vx, double vy, double vz, int ticks, int pierce,
               double gravity, double friction, Object ignore) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.ticksLeft = ticks;
            this.pierceLeft = Math.max(1, pierce);
            this.gravity = gravity;
            this.friction = friction;
            this.ignore = ignore;
        }

        /** Один тик полёта. false — пуля кончилась (попала в блок, пробила всё, время вышло, мир не прогружен). */
        boolean step(World w, Listener l) {
            if (dead || ticksLeft-- <= 0) {
                dead = true;
                return false;
            }
            double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (len < 1e-6) {
                dead = true;
                return false;
            }
            double dx = vx / len;
            double dy = vy / len;
            double dz = vz / len;
            BlockHit bh = traceBlocks(w, x, y, z, dx, dy, dz, len);
            double limit = bh == null ? len : bh.hit().t();
            // цели на отрезке до блока — по порядку
            List<Target> cands = w.targets(x + dx * len / 2, y + dy * len / 2, z + dz * len / 2, len / 2 + 3);
            List<Object[]> hits = new ArrayList<>();
            for (Target t : cands) {
                if (t.handle() == ignore || hit.contains(t.handle())) {
                    continue;
                }
                Hit h = rayBox(x, y, z, dx, dy, dz, t.minX(), t.minY(), t.minZ(), t.maxX(), t.maxY(), t.maxZ(), limit);
                if (h != null) {
                    hits.add(new Object[] {t, h});
                }
            }
            hits.sort((a, b) -> Double.compare(((Hit) a[1]).t(), ((Hit) b[1]).t()));
            for (Object[] o : hits) {
                Target t = (Target) o[0];
                Hit h = (Hit) o[1];
                double hx = x + dx * h.t();
                double hy = y + dy * h.t();
                double hz = z + dz * h.t();
                hit.add(t.handle());
                l.target(t, hx, hy, hz, h.nx(), h.ny(), h.nz(), traveled + h.t(), isHead(hy, t.eyeY()));
                if (--pierceLeft <= 0) {
                    dead = true;
                    return false;
                }
            }
            if (bh != null) {
                double t = bh.hit().t();
                if (!bh.unloaded()) {
                    l.block(bh.x(), bh.y(), bh.z(), x + dx * t, y + dy * t, z + dz * t,
                            bh.hit().nx(), bh.hit().ny(), bh.hit().nz());
                }
                x += dx * t;
                y += dy * t;
                z += dz * t;
                traveled += t;
                dead = true;
                return false;
            }
            x += vx;
            y += vy;
            z += vz;
            traveled += len;
            double keep = 1 - friction;
            vx *= keep;
            vy = vy * keep - gravity;
            vz *= keep;
            return ticksLeft > 0;
        }
    }

    /** В голову: точка попадания не ниже уровня глаз − 0,25 блока. */
    static boolean isHead(double hitY, double eyeY) {
        return hitY >= eyeY - 0.25;
    }
}
