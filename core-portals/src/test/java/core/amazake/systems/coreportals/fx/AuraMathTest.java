package core.amazake.systems.coreportals.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // rezusmenu узнаёт курсор по R ≈ 254, B ≈ 254 (±1,5): наш синий канал до 250 под это не попадает.
        assertTrue(Markers.param(Markers.RIFT, 1.0).getBlue() < 252.5);
    }
}
