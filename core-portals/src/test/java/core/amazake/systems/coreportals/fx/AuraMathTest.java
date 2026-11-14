package core.amazake.systems.coreportals.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.coreportals.portal.Markers;
import org.junit.jupiter.api.Test;

class AuraMathTest {

    @Test
    void closenessFallsToZeroAtTheRadius() {
        assertEquals(1, AuraMath.closeness(0, 10), 1e-9);
        assertEquals(0.5, AuraMath.closeness(5, 10), 1e-9);
        assertEquals(0, AuraMath.closeness(10, 10), 1e-9);
        assertEquals(0, AuraMath.closeness(25, 10), 1e-9);
        assertEquals(0, AuraMath.closeness(1, 0), 1e-9);   // радиус 0 — ауры нет
        assertEquals(0, AuraMath.closeness(Double.NaN, 10), 1e-9);
    }

    @Test
    void glowIsStrongerCloserAndScalesWithOpenness() {
        double prev = 2;
        for (double d = 0; d <= 10.0001; d += 0.5) {
            final double g = AuraMath.glowTarget(d, 10, 1, true);
            assertTrue(g <= prev + 1e-12, "свечение растёт с расстоянием на " + d);
            prev = g;
        }
        assertEquals(1, AuraMath.glowTarget(0, 10, 1, true), 1e-9);
        assertEquals(0.5, AuraMath.glowTarget(5, 10, 1, true), 1e-9);
        assertEquals(0, AuraMath.glowTarget(10, 10, 1, true), 1e-9);
        assertEquals(0.25, AuraMath.glowTarget(5, 10, 0.5, true), 1e-9);
        assertEquals(0, AuraMath.glowTarget(2, 10, 0, true), 1e-9);   // ещё трещина: свечения нет
        assertEquals(AuraMath.NO_SIGHT_GLOW * 0.5, AuraMath.glowTarget(5, 10, 1, false), 1e-9);
    }

    @Test
    void approachFadesInAndOutWithoutJumps() {
        double v = 0;
        int ticks = 0;
        while (v < 0.95 && ticks < 100) {
            final double next = AuraMath.approach(v, 1, 1);
            assertTrue(next - v <= AuraMath.RISE + 1e-12);
            v = next;
            ticks++;
        }
        assertTrue(ticks >= 8 && ticks <= 20, "нарастание за " + ticks + " тиков");
        ticks = 0;
        while (v > 0 && ticks < 100) {
            v = AuraMath.approach(v, 0, 1);
            ticks++;
        }
        assertEquals(0, v);
        assertTrue(ticks <= 40, "затухание за " + ticks + " тиков");
        assertEquals(AuraMath.approach(AuraMath.approach(0.3, 1, 1), 1, 1), AuraMath.approach(0.3, 1, 2), 1e-12);
    }

    @Test
    void pullNeedsSightStrengthAndOpenRift() {
        assertEquals(0.05, AuraMath.pull(0, 10, 1, true, 0.05), 1e-9);
        assertEquals(0.05 * Math.pow(0.5, 1.5), AuraMath.pull(5, 10, 1, true, 0.05), 1e-9);
        assertEquals(0, AuraMath.pull(5, 10, 1, false, 0.05));      // через стену не тянет
        assertEquals(0, AuraMath.pull(5, 10, 1, true, 0));          // 0 — выключено
        assertEquals(0, AuraMath.pull(5, 10, 0, true, 0.05));       // трещина ещё не раскрылась
        assertEquals(0, AuraMath.pull(12, 10, 1, true, 0.05));      // вне радиуса
    }

    @Test
    void capLimitsSpeedTowardTheRift() {
        assertEquals(0.05, AuraMath.cap(0, 0.05, 0.25), 1e-9);
        assertEquals(0.03, AuraMath.cap(0.22, 0.05, 0.25), 1e-9);
        assertEquals(0, AuraMath.cap(0.3, 0.05, 0.25), 1e-9);       // уже быстрее предела — не разгоняет
        assertEquals(0.05, AuraMath.cap(-0.2, 0.05, 0.25), 1e-9);   // уходит прочь — толчок целиком
        assertEquals(0, AuraMath.cap(0, 0, 0.25), 1e-9);
    }

    private static final double F = AuraMath.GROUND_FRICTION;

    @Test
    void pullAddsToHorizontalAndNeverTouchesVertical() {
        // Стоит на месте, разлом на +X: только толчок по X, Y — как был.
        double[] v = AuraMath.pullVelocity(0, AuraMath.GROUND_VY, 0, F, 5, 0, 0.03, 0.25, true);
        assertNotNull(v);
        assertEquals(0.03, v[0], 1e-12);
        assertEquals(AuraMath.GROUND_VY, v[1]);
        assertEquals(0, v[2], 1e-12);
        // Идёт вбок (по Z): своя скорость остаётся (смещение × трение), толчок добавляется к ней.
        v = AuraMath.pullVelocity(0, -0.0784, 0.2, F, 3, 0, 0.03, 0.25, true);
        assertEquals(0.03, v[0], 1e-12);
        assertEquals(0.2 * F, v[2], 1e-12);
        assertEquals(-0.0784, v[1]);
        // Любой Y (не вверх) возвращается ровно тем же.
        for (final double vy : new double[] {0, -0.0784, -0.3, -1.2}) {
            assertEquals(vy, AuraMath.pullVelocity(0.1, vy, 0.1, F, 1, 1, 0.03, 0.25, true)[1]);
        }
        // Направление нормируется: длина вектора к разлому не важна.
        final double[] a = AuraMath.pullVelocity(0, -0.08, 0, F, 0, -2, 0.03, 0.25, true);
        final double[] b = AuraMath.pullVelocity(0, -0.08, 0, F, 0, -40, 0.03, 0.25, true);
        assertEquals(a[2], b[2], 1e-12);
        assertEquals(-0.03, a[2], 1e-12);
    }

    @Test
    void pullSkipsJumpsAndAir() {
        // В воздухе / в прыжке — ничего (null: скорость вообще не ставится).
        assertNull(AuraMath.pullVelocity(0, -0.08, 0, F, 1, 0, 0.03, 0.25, false));
        assertNull(AuraMath.pullVelocity(0, 0.42, 0, F, 1, 0, 0.03, 0.25, true));    // скорость вверх — прыжок
        assertNull(AuraMath.pullVelocity(0, 0.01, 0, F, 1, 0, 0.03, 0.25, true));
        assertNull(AuraMath.pullVelocity(0, -0.08, 0, F, 0, 0, 0.03, 0.25, true));   // уже в центре
        assertNull(AuraMath.pullVelocity(0, -0.08, 0, F, 1, 0, 0, 0.25, true));      // выключено
        // grounded: всё вместе.
        assertTrue(AuraMath.grounded(true, AuraMath.GROUND_TICKS, 0, AuraMath.GROUND_VY, false));
        assertFalse(AuraMath.grounded(false, 20, 0, AuraMath.GROUND_VY, false));      // в воздухе
        assertFalse(AuraMath.grounded(true, 20, 0, AuraMath.GROUND_VY, true));        // жмёт прыжок
        assertFalse(AuraMath.grounded(true, 20, 0.42, AuraMath.GROUND_VY, false));    // тик прыжка: поднялся
        assertFalse(AuraMath.grounded(true, 20, -0.3, AuraMath.GROUND_VY, false));    // падает / спрыгнул
        assertFalse(AuraMath.grounded(true, 20, 0, 0.2, false));                       // скорость вверх
        assertFalse(AuraMath.grounded(true, AuraMath.GROUND_TICKS - 1, 0, -0.08, false)); // только что приземлился
    }

    @Test
    void pullCapsOnlyTheSpeedTowardTheRift() {
        // Уже идёт к разлому со скоростью предела — толчка нет (null), бежать быстрее не заставляет.
        assertNull(AuraMath.pullVelocity(0.25, -0.08, 0, F, 1, 0, 0.03, 0.25, true));
        // Идёт к разлому 0,24 — добавка только 0,01.
        double[] v = AuraMath.pullVelocity(0.24, -0.08, 0, F, 1, 0, 0.03, 0.25, true);
        assertEquals(0.24 * F + 0.01, v[0], 1e-12);
        // Уходит от разлома — толчок целиком, но он меньше шага ходьбы: уйти можно.
        v = AuraMath.pullVelocity(-0.22, -0.08, 0, F, 1, 0, 0.03, 0.25, true);
        assertEquals(-0.22 * F + 0.03, v[0], 1e-12);
        assertTrue(v[0] < 0);
        // Боковая скорость не ограничивается — предел только к разлому.
        v = AuraMath.pullVelocity(0, -0.08, 0.6, F, 1, 0, 0.03, 0.25, true);
        assertEquals(0.6 * F, v[2], 1e-12);
    }

    @Test
    void standingStillDriftsGently() {
        // Стоит и не жмёт клавиш: скорость гаснет трением каждый тик, толчок раз в 2 тика (вплотную, сила 0.03).
        double vx = 0;
        double x = 0;
        for (int t = 0; t < 200; t++) {
            if (t % 2 == 0) {
                final double[] nv = AuraMath.pullVelocity(vx, AuraMath.GROUND_VY, 0, 1, 1, 0, 0.03, 0.25, true);
                if (nv != null) {
                    vx = nv[0];
                }
            }
            x += vx;
            vx *= F;
        }
        final double perSecond = x / 10;   // 200 тиков = 10 с
        assertTrue(perSecond > 0.3 && perSecond < 2.0, "дрейф " + perSecond + " блоков/с");
    }

    @Test
    void bytesNeverReachTheForeignMarker() {
        assertEquals(0, AuraMath.toByte(0));
        assertEquals(250, AuraMath.toByte(1));
        assertEquals(250, AuraMath.toByte(7));
        assertEquals(0, AuraMath.toByte(-1));
        assertEquals(125, AuraMath.toByte(0.5));
        assertEquals(AuraMath.MAX_BYTE, Markers.MAX_PARAM);
        assertEquals(250, Markers.paramByte(1.0));
        assertEquals(0, Markers.paramByte(Double.NaN));
        for (final int shade : Markers.SHARD_SHADES) {
            assertTrue(shade <= Markers.MAX_PARAM);
        }
        // Запас от маркера курсора rezusmenu (254, G, 254) даже при допуске ±1,5.
        assertTrue(Markers.param(Markers.RIFT, 1.0).getBlue() < 252.5);
    }
}
