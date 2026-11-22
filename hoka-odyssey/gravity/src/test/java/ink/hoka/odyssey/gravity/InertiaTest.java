package ink.hoka.odyssey.gravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.spacephys.Body;
import ink.hoka.odyssey.spacephys.Field;
import ink.hoka.odyssey.spacephys.Integrator;
import ink.hoka.odyssey.spacephys.State;
import ink.hoka.odyssey.spacephys.Units;
import ink.hoka.odyssey.spacephys.Vec3d;
import org.junit.jupiter.api.Test;

class InertiaTest {

    /** Vanilla-клиент в воздухе (гравитация 0): обнулить мелочь, прибавить ввод, сдвинуться, погасить скорость. */
    static final class Client {
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;

        void packet(Vec3d v) {
            vx = v.x();
            vy = v.y();
            vz = v.z();
        }

        void tick(double[] input) {
            if (Math.abs(vx) < Inertia.CLIENT_NEGLIGIBLE) {
                vx = 0;
            }
            if (Math.abs(vy) < Inertia.CLIENT_NEGLIGIBLE) {
                vy = 0;
            }
            if (Math.abs(vz) < Inertia.CLIENT_NEGLIGIBLE) {
                vz = 0;
            }
            vx += input[0];
            vz += input[1];
            x += vx;
            y += vy;
            z += vz;
            vx *= Inertia.CLIENT_H_DRAG;
            vy *= Inertia.CLIENT_V_DRAG;
            vz *= Inertia.CLIENT_H_DRAG;
        }
    }

    @Test
    void compensationMakesDisplacementExactForAnyPacketInterval() {
        double w = 3.0;                                    // блоки/с по всем осям
        for (int n = 1; n <= 5; n++) {
            Client c = new Client();
            for (int t = 0; t < 240; t++) {
                if (t % n == 0) {
                    c.packet(Inertia.toClient(w, w, w, null, n));
                }
                c.tick(new double[] {0, 0});
            }
            double want = w * 240 / Settings.TPS;
            assertEquals(want, c.x, 1e-9, "x при пакете раз в " + n + " тиков");
            assertEquals(want, c.y, 1e-9, "y при пакете раз в " + n + " тиков");
            assertEquals(want, c.z, 1e-9, "z при пакете раз в " + n + " тиков");
        }
        assertEquals(1, Inertia.compensation(0.91, 1));
        assertEquals(2 / 1.91, Inertia.compensation(0.91, 2), 1e-12);
    }

    @Test
    void withoutCompensationClientWouldSlowDown() {
        Client c = new Client();
        for (int t = 0; t < 100; t++) {
            if (t % 4 == 0) {
                c.packet(new Vec3d(0.2, 0, 0));          // та же скорость без поправки
            }
            c.tick(new double[] {0, 0});
        }
        assertTrue(c.x < 0.2 * 100 * 0.9, "затухание клиента съедает путь: " + c.x);
    }

    @Test
    void airInputIsCancelled() {
        for (float yaw : new float[] {0, 37, 90, -135, 180}) {
            for (int f = -1; f <= 1; f++) {
                for (int s = -1; s <= 1; s++) {
                    for (boolean sprint : new boolean[] {false, true}) {
                        double[] in = Inertia.airInput(yaw, f, s, sprint);
                        Client c = new Client();
                        for (int t = 0; t < 40; t++) {
                            c.packet(Inertia.toClient(2, 0, -1, in, 1));
                            c.tick(in);
                        }
                        assertEquals(2 * 2.0, c.x, 1e-9);
                        assertEquals(-1 * 2.0, c.z, 1e-9);
                    }
                }
            }
        }
        double[] diag = Inertia.airInput(0, 1, 1, false);
        assertEquals(Inertia.AIR_INPUT, Math.hypot(diag[0], diag[1]), 1e-12);   // диагональ нормирована
        double[] fwd = Inertia.airInput(0, 1, 0, false);
        assertEquals(0, fwd[0], 1e-12);
        assertEquals(Inertia.AIR_INPUT * Inertia.INPUT_SCALE, fwd[1], 1e-12);   // yaw 0 — на юг, +z
        double[] left = Inertia.airInput(0, 0, 1, false);
        assertTrue(left[0] > 0, "A при взгляде на юг — на восток (+x)");
    }

    @Test
    void clientFollowsServerOrbit() {
        // круговая орбита вокруг планеты: клиент, получая скорость каждый тик, идёт по той же траектории
        Field field = Field.of(Body.planet("p", 0, 0, 0, 1371, 10));
        double r = 50;
        double v = Math.sqrt(1371 / r);
        State s = new State(r, 0, 0, 0, 0, v);
        Client c = new Client();
        c.x = r;
        for (int t = 0; t < 20 * 60; t++) {                // период ≈ 60 с
            s.setPosition(c.x, c.y, c.z);                   // позиция — от клиента
            Integrator.DEFAULT.kick(field, s, Units.TICK);
            c.packet(Inertia.toClient(s.vx, s.vy, s.vz, null, 1));
            c.tick(new double[] {0, 0});
        }
        double rr = Math.sqrt(c.x * c.x + c.y * c.y + c.z * c.z);
        assertEquals(r, rr, r * 0.02, "радиус орбиты держится");
    }

    @Test
    void pushDirection() {
        Vec3d n = new Vec3d(0, 1, 0);
        assertEquals(n, Inertia.pushDirection(n, new Vec3d(0, -1, 0)));        // смотрит в пол — по нормали
        Vec3d away = new Vec3d(0, 1, 1).normalize();
        assertEquals(0, Inertia.pushDirection(n, away).distance(away), 1e-12);  // от стены — по взгляду
        assertEquals(Vec3d.ZERO, Inertia.pushDirection(Vec3d.ZERO, away));
    }

    @Test
    void moveGuard() {
        Inertia.MoveGuard g = new Inertia.MoveGuard();
        g.reset(10);
        for (int t = 0; t < 100; t++) {                     // летит 10 б/с — всё проходит
            g.tick(0, 0);
            assertTrue(g.accept(10.0 / Settings.TPS));
            g.measured(10);
        }
        for (int t = 0; t < 3; t++) {                       // пачка из трёх пакетов после лага — тоже
            g.tick(0, 0);
        }
        assertTrue(g.accept(10.0 / Settings.TPS));
        assertTrue(g.accept(10.0 / Settings.TPS));
        assertTrue(g.accept(10.0 / Settings.TPS));
        g.tick(0, 0);
        assertFalse(g.accept(30), "прыжок на 30 блоков за тик — назад");
    }

    @Test
    void moveGuardIsLenient() {
        Inertia.MoveGuard g = new Inertia.MoveGuard();
        g.reset(5);
        g.tick(0, 0);                                       // допустимо 5 + 3 = 8 б/с → 0,4 блока за тик
        assertEquals(Inertia.MoveGuard.Verdict.OK, g.check(0.4, 3));
        g.tick(0, 0);
        // чуть быстрее (например, тяга клиента больше серверной) — только лог и ограничение скорости
        assertEquals(Inertia.MoveGuard.Verdict.OVER, g.check(0.8, 3));
        g.tick(0, 0);
        // грубо: больше 3× допустимого и хотя бы на блок — назад
        assertEquals(Inertia.MoveGuard.Verdict.GROSS, g.check(3 * 0.4 + 1.5, 3));
        g.tick(0, 0);
        assertEquals(Inertia.MoveGuard.Verdict.OVER, g.check(10, 0), "0 — никогда не возвращать");
    }

    @Test
    void serverThrustUnitsForVanillaClient() {
        // 0,8 б/с² за секунду (20 тиков) — 0,8 б/с, клиенту уходит 0,04 блока/тик
        State st = new State();
        for (int t = 0; t < Settings.TPS; t++) {
            Integrator.DEFAULT.kick(Field.EMPTY, st, Units.TICK, 0.8, 0, 0);
        }
        assertEquals(0.8, st.vx, 1e-12);
        assertEquals(0.04, Inertia.toClient(st.vx, 0, 0, null, 1).x(), 1e-12);
    }
}
