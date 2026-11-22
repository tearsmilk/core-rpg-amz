package ink.hoka.odyssey.spacephys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Числа на тиках: модуль spaceflight ставит скорость, vanilla её «портит», сверка возвращает настоящую. */
class VanillaMotionTest {

    /** Vanilla в воздухе без гравитации и без затухания (discardFriction): мелочь — в ноль, плюс ввод. */
    private static Vec3d vanilla(Vec3d v, double inputX) {
        double x = Math.abs(v.x()) < VanillaMotion.NEGLIGIBLE ? 0 : v.x();
        double y = Math.abs(v.y()) < VanillaMotion.NEGLIGIBLE ? 0 : v.y();
        double z = Math.abs(v.z()) < VanillaMotion.NEGLIGIBLE ? 0 : v.z();
        return new Vec3d(x + inputX, y, z);
    }

    @Test
    void weakThrustStartsFromRest() {
        double thrust = 0.8;                                    // б/с² — новая тяга по умолчанию
        assertTrue(Units.toPerTick(thrust * Units.TICK) < VanillaMotion.NEGLIGIBLE,
            "прибавка за тик меньше порога обнуления vanilla");
        // как было: основа — скорость после vanilla move → прибавка стирается каждый тик, игрок стоит
        Vec3d naive = Vec3d.ZERO;
        for (int t = 0; t < 40; t++) {
            Vec3d set = new Vec3d(naive.x() + Units.toPerTick(thrust * Units.TICK), 0, 0);
            naive = vanilla(set, 0);
        }
        assertEquals(0, naive.x(), 0, "без сверки слабая тяга не разгоняет вообще");
        // со сверкой: 2 с тяги — 1,6 б/с
        Vec3d set = Vec3d.ZERO;
        for (int t = 0; t < 40; t++) {
            Vec3d base = VanillaMotion.reconcile(set, vanilla(set, 0), false, false);
            set = new Vec3d(base.x() + Units.toPerTick(thrust * Units.TICK), base.y(), base.z());
        }
        assertEquals(1.6, Units.fromPerTick(set.x()), 1e-9);
    }

    @Test
    void vanillaKeyInputIsNotExtraThrust() {
        // клавиша «прошла» в vanilla (автоповтор): +0,02 блока/тик каждый тик = 8 б/с² сверх ранца
        Vec3d set = Vec3d.ZERO;
        for (int t = 0; t < 20; t++) {
            set = VanillaMotion.reconcile(set, vanilla(set, 0.0196), false, false);
        }
        assertEquals(0, set.x(), 0, "ввод vanilla не копится");
    }

    @Test
    void collisionAndServerPushAreKept() {
        Vec3d set = new Vec3d(0.5, 0.1, 0);                     // 10 б/с в стену по x
        Vec3d hit = VanillaMotion.reconcile(set, new Vec3d(0, 0.1, 0), true, false);
        assertEquals(0, hit.x(), 0, "удар — ось в ноль");
        assertEquals(0.1, hit.y(), 0);
        Vec3d push = VanillaMotion.reconcile(Vec3d.ZERO, new Vec3d(0, 0.25, 0), false, false);
        assertEquals(0.25, push.y(), 0, "толчок сервера (взрыв) принят");
        Vec3d stop = VanillaMotion.reconcile(new Vec3d(1, 0, 0), Vec3d.ZERO, false, false);
        assertEquals(0, stop.x(), 0, "скорость задана сервером (пакет скорости 0) — принята");
    }

    @Test
    void dampStopsWithoutOvershoot() {
        Vec3d v = new Vec3d(3, 0, 4);                           // 5 б/с
        Vec3d a = Thrust.damp(v, 1.5, Units.TICK);
        assertEquals(1.5, a.length(), 1e-12);
        assertTrue(a.dot(v) < 0);
        State s = new State(0, 0, 0, 3, 0, 4);
        int ticks = 0;
        while (s.speed() > 0 && ticks < 1000) {
            Vec3d d = Thrust.damp(s.velocity(), 1.5, Units.TICK);
            s.setVelocity(s.vx + d.x() * Units.TICK, s.vy + d.y() * Units.TICK, s.vz + d.z() * Units.TICK);
            ticks++;
        }
        assertEquals(67, ticks, 1, "5 б/с при 1,5 б/с² — за ~3,3 с");
        assertEquals(0, s.speed(), 1e-9);
        assertEquals(Vec3d.ZERO, Thrust.damp(v, 0, Units.TICK), "0 — выключено");
        assertEquals(20, Thrust.capSpeed(new Vec3d(0, 30, 0), 20).length(), 1e-12);
        assertEquals(10, Thrust.capSpeed(new Vec3d(0, 10, 0), 20).length(), 1e-12);
    }
}
