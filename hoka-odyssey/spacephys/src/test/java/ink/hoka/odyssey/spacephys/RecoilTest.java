package ink.hoka.odyssey.spacephys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecoilTest {

    /** Сумма сдвигов по тикам (скорость·dt), пока отдача не кончится. */
    private static Vec3d travel(Recoil r) {
        Vec3d s = Vec3d.ZERO;
        for (int i = 0; i < 2000 && r.active(); i++) {
            s = s.addScaled(r.velocity(), Units.TICK);
            r.step();
        }
        assertFalse(r.active());
        return s;
    }

    @Test
    void integralIsTheDistance() {
        for (double d : new double[] {0.5, 1.0, 3.0}) {         // Glock, АК, AWP
            Recoil r = new Recoil();
            r.add(-d, 0, 0);
            assertEquals(d, r.remaining(), 1e-12);
            Vec3d s = travel(r);
            assertEquals(-d, s.x(), 1e-9, "путь отдачи " + d);
            assertEquals(0, s.y(), 1e-12);
        }
    }

    @Test
    void diagonalShotKeepsDirectionAndLength() {
        Recoil r = new Recoil();
        Vec3d dir = new Vec3d(1, -2, 0.5).normalize();
        r.add(dir.x() * 3, dir.y() * 3, dir.z() * 3);
        Vec3d s = travel(r);
        assertEquals(3, s.length(), 1e-9);
        assertEquals(1, s.normalize().dot(dir), 1e-9);
    }

    @Test
    void burstAccumulates() {
        Recoil r = new Recoil();
        Vec3d s = Vec3d.ZERO;
        for (int shot = 0; shot < 10; shot++) {                 // очередь АК 600 в минуту: выстрел каждые 2 тика
            r.add(0, 0, 1.0);
            for (int t = 0; t < 2; t++) {
                s = s.addScaled(r.velocity(), Units.TICK);
                r.step();
            }
        }
        s = s.add(travel(r));
        assertEquals(10, s.z(), 1e-9);
    }

    @Test
    void vanillaDragDecay() {
        // сервер для обычного клиента: затухание как у клиента в воздухе; путь тот же
        for (double d : new double[] {0.5, 1.0, 3.0}) {
            Recoil r = new Recoil(0.91);
            r.add(0, 0, d);
            assertEquals(d, r.remaining(), 1e-12);
            assertEquals(d, travel(r).z(), 1e-9);
        }
        // пакет опоздал на тик — клиент сам погасил ×0,91: сдвиг тот же, что дал бы пакет
        Recoil r = new Recoil(0.91);
        r.add(0, 0, 3);
        double v0 = r.velocity().z();
        r.step();
        assertEquals(v0 * 0.91, r.velocity().z(), 1e-12);
    }

    @Test
    void noStepBelowVanillaNegligible() {
        Recoil r = new Recoil();
        r.add(0.5, 0, 0);                                       // даже у Glock каждый тик — больше 0,003 блока
        while (r.active()) {
            assertTrue(r.velocity().x() * Units.TICK > VanillaMotion.NEGLIGIBLE, "" + r.velocity().x());
            r.step();
        }
    }

    @Test
    void shotDuringTail() {
        Recoil r = new Recoil();
        r.add(1, 0, 0);
        double s = 0;
        while (r.remaining() > 0.03) {
            s += r.velocity().x() * Units.TICK;
            r.step();
        }
        r.add(1, 0, 0);
        s += travel(r).x();
        assertEquals(2, s, 1e-9);
    }

    @Test
    void mostOfTheWayWithinASecond() {
        Recoil r = new Recoil();
        r.add(3, 0, 0);
        double s = 0;
        for (int i = 0; i < 20; i++) {
            s += r.velocity().x() * Units.TICK;
            r.step();
        }
        assertTrue(s > 2.8 && s < 3, "за секунду " + s);
        assertTrue(Recoil.startSpeed(3) < 10, "начальная скорость AWP " + Recoil.startSpeed(3));
    }

    @Test
    void ownVelocityWithoutRecoil() {
        // поставили 0,1 своей + 0,4 отдачи (блоки/тик); сверка вернула то же — своя 0,1
        Vec3d expected = new Vec3d(0.5, 0, 0);
        Vec3d base = VanillaMotion.reconcile(expected, expected, false, false);
        assertEquals(0.1, Recoil.own(base.x(), expected.x(), 0.4), 1e-12);
        assertEquals(0, Recoil.own(0, 0.5, 0.4), "упёрся в блок — ноль");
        assertEquals(0.3, Recoil.own(0.7, 0.5, 0.4), 1e-12, "толчок сервера остаётся");
        assertEquals(0.2, Recoil.own(0.2, 0.2, 0), 1e-12);
    }
}
