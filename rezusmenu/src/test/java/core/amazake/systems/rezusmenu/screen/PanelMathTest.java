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

    /** Холст 704 × 400 (16:9.1): на широком экране упирается в высоту, на узком — в ширину. */
    @Test
    void scaleFitsCalibratedScreen() {
        assertEquals(2 * 0.9 * 0.98 / 400, PanelMath.scale(1.6, 0.9), EPS);
        assertEquals(2 * 0.9 * 0.98 / 400, PanelMath.scale(2.4, 0.9), EPS);
        assertEquals(2 * 1.0 * 0.98 / 704, PanelMath.scale(1.0, 0.9), EPS);
    }

    @Test
    void canvasCornersAndCentreMapToCamera() {
        final double s = 0.005;
        assertEquals(0, PanelMath.camX(352, s), EPS);
        assertEquals(0, PanelMath.camY(200, s), EPS);
        assertEquals(-352 * s, PanelMath.camX(0, s), EPS);
        assertEquals(200 * s, PanelMath.camY(0, s), EPS);
        assertEquals(352 * s, PanelMath.camX(704, s), EPS);
        assertEquals(-200 * s, PanelMath.camY(400, s), EPS);
    }

    @Test
    void cameraToCanvasIsInverse() {
        final double s = PanelMath.scale(1.245, 0.7);
        for (final double px : new double[] {0, 17.5, 352, 400, 704}) {
            assertEquals(px, PanelMath.canvasX(PanelMath.camX(px, s), s), 1e-6);
        }
        for (final double py : new double[] {0, 10, 200, 271.25, 400}) {
            assertEquals(py, PanelMath.canvasY(PanelMath.camY(py, s), s), 1e-6);
        }
    }

    /** Панель стоит в холсте с (96, 56): её правый нижний угол — (608, 344), середина холста — (352, 200). */
    @Test
    void panelSitsInsideCanvas() {
        assertEquals(608, PanelMath.PANEL_X + 512, EPS);
        assertEquals(344, PanelMath.PANEL_Y + 288, EPS);
        assertEquals(PanelMath.W / 2, PanelMath.PANEL_X + 256, EPS);
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
        assertEquals(1, PanelMath.hit(grid, PanelMath.canvasX(cu, s), PanelMath.canvasY(cv, s)));
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
        assertEquals(new PanelMath.Size(176, 16), PanelMath.texture("bar_energy"));
        assertEquals(new PanelMath.Size(192, 112), PanelMath.texture("back_2_2"));
        assertNull(PanelMath.texture("nope"));
    }

    /** Символы идут подряд с U+E000 в порядке списка (как в font/ui.json); ни одна текстура не шире 256 (атлас шрифта). */
    @Test
    void glyphTableIsDenseAndFitsFontAtlas() {
        assertEquals(28, PanelMath.glyphIds().size());
        for (int i = 0; i < PanelMath.ORDER.size(); i++) {
            final PanelMath.Glyph g = PanelMath.glyph(PanelMath.ORDER.get(i));
            assertEquals((char) (0xE000 + i), g.ch());
            assertTrue(g.w() <= 256 && g.h() <= 256 && g.w() % 16 == 0 && g.h() % 16 == 0, PanelMath.ORDER.get(i));
        }
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
