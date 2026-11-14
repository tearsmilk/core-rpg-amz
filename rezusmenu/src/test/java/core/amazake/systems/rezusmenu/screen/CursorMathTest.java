package core.amazake.systems.rezusmenu.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Шейдерный курсор: сервер считает ту же точку, что и шейдер (отражение через центр экрана, K = 1). */
class CursorMathTest {

    private static final double EPS = 1e-9;
    private static final double[] EYE = {10.5, 384.62, -3.25};

    @Test
    void basisIsMinecraftConvention() {
        final double[][] b = PanelMath.viewBasis(0, 0);
        assertArrayEquals(new double[] {0, 0, 1}, b[0], EPS);   // yaw 0 — юг (+z)
        assertArrayEquals(new double[] {-1, 0, 0}, b[1], EPS);  // справа — запад
        assertArrayEquals(new double[] {0, 1, 0}, b[2], EPS);
        final double[][] down = PanelMath.viewBasis(90, 30);
        assertEquals(-0.5, down[0][1], EPS);                   // pitch > 0 — вниз
    }

    /** Точка прямо по взгляду — центр экрана. */
    @Test
    void anchorOnViewAxisIsCentre() {
        final double[] p = PanelMath.anchor(EYE, 37, -12, 0, 0, 0.6);
        assertArrayEquals(new double[] {0, 0}, PanelMath.mirror(EYE, 37, -12, p), EPS);
    }

    /** anchor и mirror — взаимно обратные при том же взгляде. */
    @Test
    void anchorRoundTrips() {
        for (final double[] s : new double[][] {{0.4, -0.2}, {-1.1, 0.6}, {1.24, 0.69}}) {
            final double[] p = PanelMath.anchor(EYE, -150, 20, s[0], s[1], 0.6);
            assertArrayEquals(s, PanelMath.mirror(EYE, -150, 20, p), 1e-9);
        }
    }

    /** Голову вправо — мировая точка уходит влево, а отражённый курсор — вправо, на tan угла (как мышь). */
    @Test
    void turningRightMovesCursorRight() {
        final double[] p = PanelMath.anchor(EYE, 0, 0, 0, 0, 0.6);
        // в Minecraft поворот вправо — рост yaw
        final double[] s = PanelMath.mirror(EYE, 10, 0, p);
        assertEquals(Math.tan(Math.toRadians(10)), s[0], 1e-9);
        assertEquals(0, s[1], 1e-9);
        // голову вверх (pitch меньше) — курсор вверх
        final double[] up = PanelMath.mirror(EYE, 0, -10, p);
        assertTrue(up[1] > 0);
        assertEquals(Math.tan(Math.toRadians(10)), up[1], 1e-9);
    }

    @Test
    void pointBehindIsNull() {
        final double[] p = PanelMath.anchor(EYE, 0, 0, 0, 0, 0.6);
        assertNull(PanelMath.mirror(EYE, 180, 0, p));
    }

    /** lookAt смотрит из глаз в точку: её направление совпадает с осью взгляда. */
    @Test
    void lookAtFacesPoint() {
        final double[] p = PanelMath.anchor(EYE, 0, 0, 0.3, -0.2, 0.6);
        final float[] rot = PanelMath.lookAt(EYE, p);
        assertArrayEquals(new double[] {0, 0}, PanelMath.mirror(EYE, rot[0], rot[1], p), 1e-5);
    }
}
