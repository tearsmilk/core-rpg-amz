package ink.hoka.odyssey.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class TossTest {
    static final double EPS = 1e-9;

    @Test
    void lookingSouthThrowsToPlusZ() {
        double[] v = Toss.velocity(0, 0, 0.3, null);
        assertEquals(0, v[0], EPS);
        assertEquals(0.1, v[1], EPS);                          // только подброс
        assertEquals(0.3, v[2], EPS);
    }

    @Test
    void yawFollowsMinecraftConvention() {
        double[] west = Toss.velocity(90, 0, 0.3, null);       // 90 — на −X
        assertEquals(-0.3, west[0], EPS);
        assertEquals(0, west[2], EPS);
        double[] north = Toss.velocity(180, 0, 0.3, null);     // 180 — на −Z
        assertEquals(0, north[0], EPS);
        assertEquals(-0.3, north[2], EPS);
        double[] east = Toss.velocity(-90, 0, 0.3, null);
        assertEquals(0.3, east[0], EPS);
    }

    @Test
    void pitchUpAndDown() {
        double[] up = Toss.velocity(0, -90, 0.3, null);
        assertEquals(0.4, up[1], EPS);
        assertEquals(0, Math.hypot(up[0], up[2]), EPS);
        double[] down = Toss.velocity(0, 90, 0.3, null);
        assertEquals(-0.2, down[1], EPS);
        double[] half = Toss.velocity(0, -30, 0.3, null);
        assertEquals(0.3 * Math.cos(Math.toRadians(30)), half[2], EPS);
        assertEquals(0.3 * 0.5 + 0.1, half[1], EPS);
    }

    @Test
    void speedScales() {
        double[] v = Toss.velocity(45, 0, 0.6, null);
        assertEquals(0.6, Math.hypot(v[0], v[2]), EPS);
    }

    @Test
    void spreadIsSmallAndKeepsDirection() {
        SplittableRandom r = new SplittableRandom(42);
        double minY = 1;
        double maxY = -1;
        for (int i = 0; i < 10_000; i++) {
            double[] v = Toss.velocity(0, 0, 0.3, r);
            assertTrue(Math.abs(v[0]) <= Toss.SIDE_SPREAD + EPS, "x " + v[0]);
            assertTrue(Math.abs(v[2] - 0.3) <= Toss.SIDE_SPREAD + EPS, "z " + v[2]);
            assertTrue(Math.abs(v[1] - 0.1) <= Toss.UP_SPREAD + EPS, "y " + v[1]);
            minY = Math.min(minY, v[1]);
            maxY = Math.max(maxY, v[1]);
        }
        assertTrue(maxY - minY > 0.1, "разброс по вертикали есть");
    }
}
