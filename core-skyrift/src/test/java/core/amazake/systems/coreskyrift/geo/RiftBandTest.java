package core.amazake.systems.coreskyrift.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class RiftBandTest {

    @Test
    void axisFollowsYawLikeTheDisplayNormal() {
        final RiftBand b0 = RiftBand.of(1.0, 0);
        assertEquals(0, b0.axisX(), 1e-6);
        assertEquals(1, b0.axisZ(), 1e-6);
        final RiftBand b90 = RiftBand.of(1.0, 90);
        assertEquals(1, b90.axisX(), 1e-6);
        assertEquals(0, b90.axisZ(), 1e-6);
        // the shader reads normalize(Normal.xz) of rotation * (0,0,1)
        final Vector3f n = RiftBand.rotation(37, 1.3).transform(new Vector3f(0, 0, 1));
        final RiftBand b = RiftBand.of(1.3, 37);
        final double len = Math.hypot(n.x, n.z);
        assertEquals(n.x / len, b.axisX(), 1e-6);
        assertEquals(n.z / len, b.axisZ(), 1e-6);
        assertEquals(RiftBand.tiltY(1.3), n.y, 1e-6);
    }

    @Test
    void sizeSurvivesTheNormalByte() {
        for (double s = 0.25; s <= 2.0; s += 0.05) {
            final double y = RiftBand.rotation(123, s).transform(new Vector3f(0, 0, 1)).y;
            final int packed = (int) (float) (y * 127.0f);          // client: (int) (y * 127), truncation
            final double shader = 0.25 + 1.75 * Math.min(1, (packed / 127.0) / 0.9);   // sr_vertex
            assertEquals(RiftBand.quantizedSize(s), shader, 1e-9, "size " + s);
            assertEquals(s, shader, 0.02, "close to the asked size " + s);
        }
        assertEquals(1.0, RiftBand.quantizedSize(1.0), 0.015);
        assertEquals(0.25, RiftBand.quantizedSize(0.1), 1e-9);
        assertEquals(RiftBand.quantizedSize(2.0), RiftBand.quantizedSize(5), 1e-9);
    }

    @Test
    void crackGrowsThenOpens() {
        final RiftBand b = RiftBand.of(1.0, 0);   // axis = +z
        assertFalse(b.contains(0, 0, 0, 1), "nothing before the crack");
        assertTrue(b.contains(0, 10, 0.1, 1), "thin crack near the centre");
        assertFalse(b.contains(0, 200, 0.1, 1), "crack has not reached far yet");
        assertFalse(b.contains(20, 0, 0.2, 1), "crack is thin");
        assertTrue(b.contains(0, 230, 0.2, 1), "full length at 0.2");
        assertTrue(b.contains(100, 0, 1.0, 1), "fully open: 100 blocks to the side of the centre");
        assertFalse(b.contains(120, 0, 1.0, 1), "beyond the half-width");
        assertFalse(b.contains(0, 245, 1.0, 1), "beyond the tips");
        assertTrue(b.contains(50, 0, 0.6, 1));
        assertFalse(b.contains(80, 0, 0.6, 1));
        assertEquals(b.halfWidth() + RiftBand.THIN, b.halfWidthAt(0, 1), 1e-9);
        assertEquals(110, b.halfWidth(), 0.1);
        assertEquals(240, b.halfLength(1), 0.2);
    }

    @Test
    void pointMapsInside() {
        final RiftBand b = RiftBand.of(0.8, 211);
        for (double fu = -0.8; fu <= 0.8; fu += 0.2) {
            for (double fv = -0.8; fv <= 0.8; fv += 0.2) {
                final double[] p = b.point(fu, fv, 1.0);
                assertTrue(b.contains(p[0], p[1], 1.0, 0.999), fu + " " + fv);
                assertEquals(fu * b.halfLength(1.0), b.along(p[0], p[1]), 1e-6);
            }
        }
    }
}
