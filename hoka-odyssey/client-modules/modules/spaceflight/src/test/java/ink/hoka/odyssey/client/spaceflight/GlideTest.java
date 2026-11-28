package ink.hoka.odyssey.client.spaceflight;

import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Плавный переезд глаз после смены рамки: начало и конец на месте, на ребре — дуга снаружи блока. */
class GlideTest {
    private static final long SEC = 1_000_000_000L;

    private static void near(Vec3d a, Vec3d b, double eps) {
        assertTrue(a.distance(b) < eps, a + " != " + b);
    }

    @Test
    void easeIsSmoothAndMonotonic() {
        assertEquals(0, SurfaceGravity.ease(0), 1e-12);
        assertEquals(1, SurfaceGravity.ease(1), 1e-12);
        assertEquals(0.5, SurfaceGravity.ease(0.5), 1e-12);
        double prev = 0;
        for (int i = 1; i <= 100; i++) {
            double e = SurfaceGravity.ease(i / 100.0);
            assertTrue(e >= prev);
            prev = e;
        }
        // нулевая скорость у краёв: первый и последний шаг много меньше среднего
        assertTrue(SurfaceGravity.ease(0.01) < 0.001);
        assertTrue(1 - SurfaceGravity.ease(0.99) < 0.001);
    }

    @Test
    void straightWithoutTurn() {
        Vec3d a = new Vec3d(0, 1, 0), b = new Vec3d(2, 1, 0);
        Glide g = Glide.of(a, b, Quat.IDENTITY, 0, 0.5);
        near(a.sub(b), g.offset(0), 1e-9);
        near(new Vec3d(1, 1, 0), g.at(SEC / 4), 1e-9);
        near(Vec3d.ZERO, g.offset(SEC / 2), 1e-9);
        assertTrue(g.done(SEC / 2));
    }

    /**
     * Внешнее ребро: блок x &lt; 0, y &lt; 0; сошёл с верха (+Y) на боковую грань (+X). Глаза едут по дуге вокруг
     * ребра — всё время снаружи блока (x &gt; 0 или y &gt; 0) и на одном расстоянии от центра дуги.
     */
    @Test
    void outerEdgeArcStaysOutside() {
        Vec3d e0 = new Vec3d(0.3, 1.32, 0.4), e1 = new Vec3d(1.62, 0, 0.4);
        Quat turn = SurfaceGravity.between(new Vec3d(0, 1, 0), new Vec3d(1, 0, 0));
        Glide g = Glide.of(e0, e1, turn, 0, 0.5);
        near(e0, g.at(0), 1e-9);
        near(e1, g.at(SEC / 2), 1e-9);
        Vec3d mid = g.at(SEC / 4);
        // поворот на 90° вокруг центра (0.3, 0): на полпути — под 45°
        near(new Vec3d(0.3 + 1.32 * Math.sqrt(0.5), 1.32 * Math.sqrt(0.5), 0.4), mid, 1e-9);
        for (int i = 0; i <= 50; i++) {
            Vec3d p = g.at(SEC / 2 * i / 50);
            assertTrue(p.x() > 0 || p.y() > 0, "внутри блока: " + p);
            assertEquals(1.32, Math.hypot(p.x() - 0.3, p.y()), 1e-9);
            assertEquals(0.4, p.z(), 1e-9);
        }
    }

    /** Внутренний угол: пол (+Y) → стена x = 0 (нормаль −X). Дуга — внутри комнаты, глаза не уходят в стену. */
    @Test
    void innerCornerArcStaysInRoom() {
        Vec3d e0 = new Vec3d(-0.3, 1.62, 0), e1 = new Vec3d(-1.62, 1.5, 0);
        Quat turn = SurfaceGravity.between(new Vec3d(0, 1, 0), new Vec3d(-1, 0, 0));
        Glide g = Glide.of(e0, e1, turn, 100, 0.55);
        near(e0, g.at(100), 1e-9);
        near(e1, g.at(100 + (long) (0.55 * SEC)), 1e-9);
        for (int i = 0; i <= 50; i++) {
            Vec3d p = g.at(100 + (long) (0.55 * SEC * i / 50));
            assertTrue(p.x() < -0.2 && p.y() > 0.2, "у стены или пола: " + p);
        }
    }

    /** Сдвиг вдоль оси поворота — по прямой. */
    @Test
    void axialPartLinear() {
        Vec3d e0 = new Vec3d(0.3, 1.32, 0), e1 = new Vec3d(1.62, 0, 1);
        Quat turn = SurfaceGravity.between(new Vec3d(0, 1, 0), new Vec3d(1, 0, 0));
        Glide g = Glide.of(e0, e1, turn, 0, 1);
        assertEquals(0.5, g.at(SEC / 2).z(), 1e-9);
        near(e1, g.at(SEC), 1e-9);
    }
}
