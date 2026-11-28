package ink.hoka.odyssey.gravity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MotionTest {
    /** Как в config.yml по умолчанию. */
    static final Settings S = new Settings(Set.of("galaxy1"), Settings.perTick2(8.0), Settings.perTick(4.0),
        Motion.keepPerTick(0.35), Settings.perTick2(2.4), Settings.perTick(7.0), Motion.keepPerTick(0.8),
        false, false, "");

    static final Floater.Keys UP = new Floater.Keys(false, false, false, false, true, false);
    static final Floater.Keys DOWN = new Floater.Keys(false, false, false, false, false, true);

    @Test
    void units() {
        assertEquals(0.2, S.vMax(), 1e-12);
        assertEquals(0.02, S.vThrust(), 1e-12);
        assertEquals(0.5, Math.pow(Motion.keepPerTick(0.35), 7), 1e-9);   // за 0,35 с = 7 тиков — вдвое
        assertEquals(0, Motion.keepPerTick(0));
    }

    @Test
    void noInputNoMotionNoImpulse() {
        assertEquals(0, Motion.verticalImpulse(0, 0, S));
        Floater f = new Floater();
        for (int i = 0; i < 40; i++) {
            assertNull(f.tick(S, 0, 100, 0, 0, Floater.Keys.NONE, false, false), "висит — ничего не шлём");
        }
    }

    @Test
    void thrustIsLimitedAndReachesMaxSpeed() {
        double v = 0;
        for (int i = 0; i < 200; i++) {
            double imp = Motion.verticalImpulse(v, 1, S);
            assertTrue(imp <= S.vThrust() + 1e-12, "толчок не больше разгона");
            v = Motion.nextVertical(v, imp);
            assertTrue(v <= S.vMax() + 1e-9, "не быстрее предела: " + v);
        }
        assertEquals(S.vMax(), v, 1e-9);
        for (int i = 0; i < 200; i++) {
            v = Motion.nextVertical(v, Motion.verticalImpulse(v, -1, S));
        }
        assertEquals(-S.vMax(), v, 1e-9);
    }

    @Test
    void releaseDecaysSmoothlyNotInstantly() {
        double v = S.vMax();
        double prev = v;
        int ticks = 0;
        while (v > 0 && ticks < 200) {
            v = Motion.nextVertical(v, Motion.verticalImpulse(v, 0, S));
            assertTrue(v < prev, "скорость гаснет");
            assertTrue(v == 0 || v > prev * 0.8, "без рывка: " + prev + " -> " + v);
            prev = v;
            ticks++;
        }
        assertEquals(0, v);
        assertTrue(ticks > 10 && ticks < 80, "остановка за разумное время, тиков: " + ticks);
        // через 7 тиков (0,35 с) — примерно вдвое
        double w = S.vMax();
        for (int i = 0; i < 7; i++) {
            w = Motion.nextVertical(w, Motion.verticalImpulse(w, 0, S));
        }
        assertEquals(S.vMax() / 2, w, 0.01);
    }

    @Test
    void direction() {
        assertArrayEquals(new double[] {0, 1}, Motion.direction(0, 1, 0), 1e-9);    // yaw 0 — вперёд на юг (+z)
        assertArrayEquals(new double[] {1, 0}, Motion.direction(0, 0, 1), 1e-9);    // влево — восток (+x)
        assertArrayEquals(new double[] {1, 0}, Motion.direction(-90, 1, 0), 1e-9);  // yaw -90 — на восток
        double[] diag = Motion.direction(0, 1, 1);
        assertEquals(1, Math.hypot(diag[0], diag[1]), 1e-9);
    }

    @Test
    void horizontalThrustOnlyInAirAndBelowMax() {
        double[] h = Motion.horizontalImpulse(0, 1, 0, 0, 0, false, S);
        assertEquals(S.hThrust(), h[1], 1e-12);
        assertArrayEquals(new double[] {0, 0}, Motion.horizontalImpulse(0, 1, 0, 0, 0, true, S), 0, "на земле — обычная ходьба");
        assertArrayEquals(new double[] {0, 0}, Motion.horizontalImpulse(0, 1, 0, 0, S.hMax(), false, S), 0, "быстрее предела — без разгона");
        assertTrue(Motion.horizontalImpulse(0, -1, 0, 0, S.hMax(), false, S)[1] < 0, "тормозить можно всегда");
    }

    @Test
    void glideKeepsInertia() {
        // клиент без клавиш: v = (v + imp) * 0.91; с поддержкой должно выйти ≈ v * keep
        double v = 0.3;
        double[] h = Motion.horizontalImpulse(0, 0, 0, 0, v, false, S);
        double next = (v + h[1]) * Motion.CLIENT_AIR_FRICTION;
        assertEquals(v * S.hKeep(), next, 1e-9);
        assertTrue(next > v * Motion.CLIENT_AIR_FRICTION, "тормозит медленнее обычного воздуха");
        assertTrue(next < v, "но всё же тормозит");
    }

    @Test
    void floaterModelFollowsKeysAndResetsOnGround() {
        Floater f = new Floater();
        double y = 100;
        double sum = 0;
        for (int i = 0; i < 30; i++) {
            Floater.Impulse imp = f.tick(S, 0, y, 0, 0, UP, false, false);
            sum += imp == null ? 0 : imp.y();
            y += f.vy;                                   // клиент двигается как модель
        }
        assertTrue(sum > 0 && f.vy > 0.15, "пробел — вверх: " + f.vy);
        for (int i = 0; i < 60; i++) {
            f.tick(S, 0, y, 0, 0, DOWN, false, false);
            y += f.vy;
        }
        assertTrue(f.vy < -0.15, "Shift — вниз: " + f.vy);
        f.tick(S, 0, y, 0, 0, DOWN, true, false);
        assertEquals(0, f.vy, "на земле вниз не толкаем");
        assertNull(f.tick(S, 0, y, 0, 0, DOWN, true, false));
    }

    @Test
    void floaterStopsModelWhenStuck() {
        Floater f = new Floater();
        for (int i = 0; i < Floater.STUCK_TICKS + 5; i++) {
            f.tick(S, 0, 100, 0, 0, UP, false, false);    // жмёт вверх, но упёрся в потолок
        }
        assertTrue(f.vy < S.vMax(), "модель сброшена, а не копит скорость: " + f.vy);
    }

    @Test
    void creativeFlightIsLeftAlone() {
        Floater f = new Floater();
        assertNull(f.tick(S, 0, 100, 0, 0, UP, false, true));
        assertEquals(0, f.vy);
    }

    @Test
    void teleportResetsMeasurements() {
        Floater f = new Floater();
        f.tick(S, 0, 100, 0, 0, Floater.Keys.NONE, false, false);
        f.tick(S, 0, 100, 0.3, 0, Floater.Keys.NONE, false, false);
        assertTrue(f.mz > 0);
        f.tick(S, 500, 100, 500, 0, Floater.Keys.NONE, false, false);
        assertEquals(0, f.mx);
        assertEquals(0, f.mz);
    }

    @Test
    void enteringWhileFallingBrakesTheFall() {
        Floater f = new Floater();
        double y = 100;
        double v = -1.5;                                 // падал, и тут включили вакуум
        for (int i = 0; i < 80; i++) {
            y += v;
            Floater.Impulse imp = f.tick(S, 0, y, 0, 0, Floater.Keys.NONE, false, false);
            v = (v + (imp == null ? 0 : imp.y())) * Motion.CLIENT_VERTICAL_DRAG;   // клиент без гравитации
        }
        assertTrue(Math.abs(v) < 0.01, "падение погашено: " + v);
    }
}
