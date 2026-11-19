package dev.tearsmilk.coreshaders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeometryTest {
    private static final double E = 1e-9;

    @Test
    void cubeCentered() {
        assertArrayEquals(new double[] {10.5, 64.5, -2.5}, Geometry.cubeCenter(10, 64, -3), E);
    }

    @Test
    void planeSitsOnSupportFace() {
        double off = 0.015;
        assertArrayEquals(new double[] {0.5, 64.015, 0.5}, Geometry.planeCenter(0, 64, 0, Dir.UP, off), E);
        assertArrayEquals(new double[] {0.5, 64.985, 0.5}, Geometry.planeCenter(0, 64, 0, Dir.DOWN, off), E);
        assertArrayEquals(new double[] {0.5, 64.5, 0.985}, Geometry.planeCenter(0, 64, 0, Dir.NORTH, off), E);
        assertArrayEquals(new double[] {0.015, 64.5, 0.5}, Geometry.planeCenter(0, 64, 0, Dir.EAST, off), E);
    }

    @Test
    void planeRotationTurnsNormalToFaceAxis() {
        for (Dir d : Dir.values()) {
            double[] n = Geometry.rotate(Geometry.planeRotation(d), new double[] {0, 0, 1});
            assertEquals(1.0, Math.abs(n[0] * d.dx + n[1] * d.dy + n[2] * d.dz), 1e-6, d.name());
        }
    }

    @Test
    void planeBoxIsThinSlabAtSupport() {
        double[] b = Geometry.box(Shape.PLANE, 0, 64, 0, Dir.UP);
        assertArrayEquals(new double[] {0, 64, 0, 1, 64 + Geometry.PLANE_SLAB, 1}, b, E);
        double[] w = Geometry.box(Shape.PLANE, 0, 64, 0, Dir.WEST);
        assertArrayEquals(new double[] {1 - Geometry.PLANE_SLAB, 64, 0, 1, 65, 1}, w, E);
        assertArrayEquals(new double[] {0, 64, 0, 1, 65, 1}, Geometry.box(Shape.CUBE, 0, 64, 0, Dir.UP), E);
    }

    @Test
    void rayHitsAndMisses() {
        double[] box = {0, 0, 0, 1, 1, 1};
        assertEquals(2.0, Geometry.rayBox(new double[] {0.5, 0.5, -2}, new double[] {0, 0, 1}, box, 8), E);
        assertEquals(-1.0, Geometry.rayBox(new double[] {2, 0.5, -2}, new double[] {0, 0, 1}, box, 8), E);
        assertEquals(-1.0, Geometry.rayBox(new double[] {0.5, 0.5, -20}, new double[] {0, 0, 1}, box, 8), E);
        assertEquals(0.0, Geometry.rayBox(new double[] {0.5, 0.5, 0.5}, new double[] {0, 1, 0}, box, 8), E);
    }

    @Test
    void hitboxShapes() {
        double[] c = Geometry.hitbox(Shape.CUBE, 3, 70, 4, Dir.UP, 0.015);
        assertArrayEquals(new double[] {3.5, 70, 4.5, 1, 1}, c, E);
        double[] floor = Geometry.hitbox(Shape.PLANE, 3, 70, 4, Dir.UP, 0.015);
        assertEquals(70, floor[1], E);
        assertTrue(floor[4] < 0.1);
        double[] ceil = Geometry.hitbox(Shape.PLANE, 3, 70, 4, Dir.DOWN, 0.015);
        assertEquals(71.0, ceil[1] + ceil[4], E);
    }

    @Test
    void faceFromClick() {
        assertEquals(Dir.UP, Geometry.faceFromLocal(0.1, 0.5, -0.2));
        assertEquals(Dir.WEST, Geometry.faceFromLocal(-0.5, 0.1, 0.2));
        assertEquals(Dir.SOUTH, Geometry.faceFromLocal(0.1, -0.2, 0.5));
        assertEquals(Dir.DOWN, Geometry.faceFromLocal(0.0, -0.5, 0.0));
    }

    @Test
    void opposite() {
        for (Dir d : Dir.values()) {
            assertEquals(d, d.opposite().opposite());
            assertEquals(0, d.dx + d.opposite().dx);
        }
    }
}
