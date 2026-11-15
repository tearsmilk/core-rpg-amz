package core.amazake.systems.coreweapons.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeometryTest {

    @Test
    void basisFollowsMinecraftYaw() {
        final double[] f = Geometry.forward(0); // юг
        assertEquals(0, f[0], 1e-9);
        assertEquals(1, f[2], 1e-9);
        final double[] r = Geometry.right(0); // смотрит на юг — справа запад (−x)
        assertEquals(-1, r[0], 1e-9);
        final double[] w = Geometry.forward(90); // запад
        assertEquals(-1, w[0], 1e-9);
    }

    @Test
    void orbitPointsKeepRadiusInPlane() {
        final List<double[]> pts = Geometry.orbit(5, 3, 0.75, 14, 0, 0, 37);
        assertEquals(3, pts.size());
        for (final double[] p : pts) {
            assertEquals(0.75, Math.hypot(p[0], p[2]), 1e-9);
            assertEquals(0, p[1], 1e-9);
        }
    }

    @Test
    void arcGoesFromUpperRightToLowerLeft() {
        final double[] look = {0, 0, 1};
        final double[] a = Geometry.arc(0, 1.3, 1.2, look, 0);
        final double[] b = Geometry.arc(1, 1.3, 1.2, look, 0);
        // справа при yaw 0 — это −x
        assertTrue(a[0] < 0 && a[1] > 0, "начало справа сверху");
        assertTrue(b[0] > 0 && b[1] < 0, "конец слева снизу");
        assertEquals(1.2, a[2], 1e-9);
    }

    @Test
    void bandsCoverDiscOnce() {
        int total = 0;
        double prev = 0;
        for (int s = 0; s < 12; s++) {
            final double r = Geometry.waveRadius(s, 12, 6);
            assertTrue(r >= prev);
            total += Geometry.band(s == 0 ? 0 : prev, r).size();
            prev = r;
        }
        assertEquals(6.0, prev, 1e-9);
        assertEquals(Geometry.band(0, 6).size(), total);
    }

    @Test
    void shakeUpAndDown() {
        assertEquals(0, Geometry.shake(0, 0.2), 1e-9);
        assertEquals(0.2, Geometry.shake(2, 0.2), 1e-9);
        assertTrue(Geometry.shake(3, 0.2) < 0.2);
        assertEquals(0, Geometry.shake(5, 0.2), 1e-9);
    }
}
