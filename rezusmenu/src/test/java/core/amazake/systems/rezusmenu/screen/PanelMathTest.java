package core.amazake.systems.rezusmenu.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PanelMathTest {

    private static final double EPS = 1e-9;

    /** 16:9 экран: панель упирается в обе стороны одновременно; 21:9 — в высоту. */
    @Test
    void scaleFitsCalibratedScreen() {
        assertEquals(2 * 1.6 * 0.98 / 512, PanelMath.scale(1.6, 0.9), EPS);
        assertEquals(2 * 0.9 * 0.98 / 288, PanelMath.scale(2.4, 0.9), EPS);
        assertEquals(2 * 1.0 * 0.98 / 512, PanelMath.scale(1.0, 0.9), EPS);
    }

    @Test
    void canvasCornersAndCentreMapToCamera() {
        final double s = 0.005;
        assertEquals(0, PanelMath.camX(256, s), EPS);
        assertEquals(0, PanelMath.camY(144, s), EPS);
        assertEquals(-256 * s, PanelMath.camX(0, s), EPS);
        assertEquals(144 * s, PanelMath.camY(0, s), EPS);
        assertEquals(256 * s, PanelMath.camX(512, s), EPS);
        assertEquals(-144 * s, PanelMath.camY(288, s), EPS);
    }

    @Test
    void cameraToPanelIsInverse() {
        final double s = PanelMath.scale(1.245, 0.7);
        for (final double px : new double[] {0, 17.5, 256, 400, 512}) {
            assertEquals(px, PanelMath.panelX(PanelMath.camX(px, s), s), 1e-6);
        }
        for (final double py : new double[] {0, 10, 144, 271.25, 288}) {
            assertEquals(py, PanelMath.panelY(PanelMath.camY(py, s), s), 1e-6);
        }
    }

    @Test
    void rectContainsUsesHalfOpenEdges() {
        final PanelMath.Rect r = new PanelMath.Rect(218, 74, 28, 28);
        assertTrue(r.contains(218, 74));
        assertTrue(r.contains(245.99, 101.99));
        assertFalse(r.contains(246, 80));
        assertFalse(r.contains(230, 102));
        assertFalse(r.contains(217.99, 80));
    }

    /** Курсор над клеткой сетки (остриё стрелки) попадает в неё; в зазоре между клетками — ни во что. */
    @Test
    void hitTestThroughCameraUnits() {
        final double s = PanelMath.scale(1.245, 0.7);
        final List<PanelMath.Rect> grid = List.of(
            new PanelMath.Rect(218, 74, 28, 28),
            new PanelMath.Rect(249, 74, 28, 28),
            new PanelMath.Rect(218, 244, 134, 28));
        final double cu = PanelMath.camX(250, s);
        final double cv = PanelMath.camY(80, s);
        assertEquals(1, PanelMath.hit(grid, PanelMath.panelX(cu, s), PanelMath.panelY(cv, s)));
        assertEquals(-1, PanelMath.hit(grid, 247.5, 80));
        assertEquals(2, PanelMath.hit(grid, 300, 260));
    }

    @Test
    void laterRectWinsOnOverlap() {
        final List<PanelMath.Rect> rects = List.of(new PanelMath.Rect(0, 0, 100, 100), new PanelMath.Rect(10, 10, 5, 5));
        assertEquals(1, PanelMath.hit(rects, 12, 12));
        assertEquals(0, PanelMath.hit(rects, 50, 50));
    }

    @Test
    void overlayTextureSizesFollowPackContract() {
        assertEquals(new PanelMath.Size(128, 32), PanelMath.texture("tab_hover"));
        assertEquals(new PanelMath.Size(32, 32), PanelMath.texture("slot_selected"));
        assertEquals(new PanelMath.Size(144, 32), PanelMath.texture("button_hover"));
        assertNull(PanelMath.texture("nope"));
    }

    @Test
    void calibrationFromEyeInTopRightCorner() {
        final Screen.Calibration c = CalibrationScreen.result(1.2, 0.65);
        assertNotNull(c);
        assertEquals(1.2 + CalibrationScreen.EYE_HALF, c.halfW(), EPS);
        assertEquals(0.65 + CalibrationScreen.EYE_HALF, c.halfH(), EPS);
        assertNull(CalibrationScreen.result(0.4, 0.65));
        assertNull(CalibrationScreen.result(1.2, 0.2));
    }
}
