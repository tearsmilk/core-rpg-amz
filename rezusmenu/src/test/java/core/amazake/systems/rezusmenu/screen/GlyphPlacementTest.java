package core.amazake.systems.rezusmenu.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Раскладка глифов текстового дисплея по клиенту 1.21.11 (исследование Q1). */
class GlyphPlacementTest {

    private static final double EPS = 1e-9;

    /** Один глиф W × H, ascent = H: x ∈ [(1 − W)/2, (1 + W)/2], низ — 2 px шрифта над точкой дисплея. */
    @Test
    void singleGlyphBoxMatchesClient() {
        final PanelMath.FontBox b = PanelMath.glyphBox(256, 144);
        assertEquals((1 - 256) / 2.0, b.left(), EPS);
        assertEquals(256, b.w(), EPS);
        assertEquals(144 + 2, b.top(), EPS);
        assertEquals(144, b.h(), EPS);
        // правый край — (1 + W)/2, т. е. центр в +0.5 px
        assertEquals((1 + 256) / 2.0, b.left() + b.w(), EPS);
        assertEquals(2, b.top() - b.h(), EPS);
    }

    /** Строка «пикселей»: 16 пикселей шириной 1 → ширина строки 16, первый пиксель с x = −7, верх — ascent + 2. */
    @Test
    void pixelRowBox() {
        final PanelMath.FontBox b = PanelMath.lineBox(16, 0, 16, 1, PanelMath.PIXEL_ASCENT);
        assertEquals(-7, b.left(), EPS);
        assertEquals(3, b.top(), EPS);
    }

    /** fit кладёт углы прямоугольника шрифта ровно в заданные углы (единицы камеры). */
    @Test
    void fitMapsCornersExactly() {
        final PanelMath.FontBox b = PanelMath.glyphBox(176, 16);
        final double left = -0.31;
        final double top = 0.17;
        final double w = 0.4;
        final double h = 0.036;
        final PanelMath.Fit f = PanelMath.fit(b, left, top, w, h, 0, 0);
        assertArrayEquals(new double[] {left, top}, PanelMath.apply(f, b.left(), b.top()), EPS);
        assertArrayEquals(new double[] {left + w, top - h}, PanelMath.apply(f, b.left() + b.w(), b.top() - b.h()), EPS);
        assertEquals(w / 176 / PanelMath.FONT_PX, f.sx(), EPS);
    }

    /** Подстройка art.glyph-offset-x/y двигает картинку вправо/вверх на столько пикселей текстуры. */
    @Test
    void fineOffsetMovesRightAndUp() {
        final PanelMath.FontBox b = PanelMath.glyphBox(32, 32);
        final PanelMath.Fit plain = PanelMath.fit(b, 0, 0, 32, 32, 0, 0);
        final PanelMath.Fit moved = PanelMath.fit(b, 0, 0, 32, 32, 1, 2);
        // 1 px текстуры = 1 единица (32 единицы на 32 px)
        assertEquals(plain.tx() + 1, moved.tx(), EPS);
        assertEquals(plain.ty() + 2, moved.ty(), EPS);
    }

    /** Заливка полоски режется масштабом: левый край на месте, правый — на доле ширины. */
    @Test
    void barCropKeepsLeftEdge() {
        final double s = 0.004;
        final PanelMath.FontBox b = PanelMath.glyphBox(176, 16);
        final double left = PanelMath.camX(96 + 32, s);
        final double top = PanelMath.camY(56 + 222, s);
        final PanelMath.Fit full = PanelMath.fit(b, left, top, 176 * s, 16 * s, 0, 0);
        final PanelMath.Fit half = PanelMath.fit(b, left, top, 88 * s, 16 * s, 0, 0);
        assertEquals(PanelMath.apply(full, b.left(), b.top())[0], PanelMath.apply(half, b.left(), b.top())[0], EPS);
        // 166 px содержимого → на половине — 83 px
        final double contentRight = PanelMath.apply(half, b.left() + 166, b.top())[0];
        assertEquals(83, PanelMath.canvasX(contentRight, s) - (96 + 32), 1e-6);
        // и верх заливки — ровно y 222 панели
        assertEquals(56 + 222, PanelMath.canvasY(PanelMath.apply(half, b.left(), b.top())[1], s), 1e-6);
    }
}
