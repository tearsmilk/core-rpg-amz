package core.amazake.systems.rezusmenu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import core.amazake.systems.rezusmenu.screen.ScreenFrame;
import org.junit.jupiter.api.Test;

class ScreenFrameTest {

    @Test
    void snapsToCardinalAndRightIsCorrect() {
        // смотрит на юг (+Z): вправо — запад (−X)
        final ScreenFrame south = ScreenFrame.facing(0, 65, 0, 10f, 2);
        assertEquals(0f, south.yaw());
        assertArrayEquals(new double[] {-1, 66, 2}, south.point(1, 1, 0), 1e-9);
        // смотрит на восток (yaw −90 = 270): вправо — юг (+Z)
        final ScreenFrame east = ScreenFrame.facing(0, 65, 0, -95f, 2);
        assertEquals(270f, east.yaw());
        assertArrayEquals(new double[] {2, 65, 1}, east.point(1, 0, 0), 1e-9);
    }

    @Test
    void layersMoveTowardPlayer() {
        final ScreenFrame north = ScreenFrame.facing(0, 65, 0, 180f, 2);
        assertEquals(-2 + 3 * ScreenFrame.LAYER_STEP, north.point(0, 0, 3)[2], 1e-9);
    }

    @Test
    void hitMatchesPoint() {
        final ScreenFrame west = ScreenFrame.facing(10, 70, 10, 90f, 2.5);
        final double[] target = west.point(-1.2, 0.7, 0);
        final double dx = target[0] - 10;
        final double dy = target[1] - 70;
        final double dz = target[2] - 10;
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final double[] uv = west.hit(10, 70, 10, dx / len, dy / len, dz / len);
        assertArrayEquals(new double[] {-1.2, 0.7}, uv, 1e-9);
        assertNull(west.hit(10, 70, 10, 1, 0, 0)); // смотрит назад
    }
}
