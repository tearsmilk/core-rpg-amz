package ink.hoka.odyssey.coreshaders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RayTest {
    @Test
    void hitsFirstSolidBlock() {
        Ray.Blocks wallAtX5 = (x, y, z) -> x == 5;
        assertEquals(4.5, Ray.firstSolid(wallAtX5, new double[] {0.5, 0.5, 0.5}, new double[] {1, 0, 0}, 8), 1e-9);
        assertEquals(-1.0, Ray.firstSolid(wallAtX5, new double[] {0.5, 0.5, 0.5}, new double[] {1, 0, 0}, 4), 1e-9);
        assertEquals(-1.0, Ray.firstSolid(wallAtX5, new double[] {0.5, 0.5, 0.5}, new double[] {-1, 0, 0}, 8), 1e-9);
        Ray.Blocks floor = (x, y, z) -> y < 0;
        double s = Math.sqrt(0.5);
        assertEquals(Math.sqrt(2) * 1.5, Ray.firstSolid(floor, new double[] {0.5, 1.5, 0.5}, new double[] {s, -s, 0}, 8),
            1e-9);
        Ray.Blocks inside = (x, y, z) -> x == 0 && y == 0 && z == 0;
        assertEquals(-1.0, Ray.firstSolid(inside, new double[] {0.5, 0.5, 0.5}, new double[] {0, 1, 0}, 8), 1e-9);
    }
}
