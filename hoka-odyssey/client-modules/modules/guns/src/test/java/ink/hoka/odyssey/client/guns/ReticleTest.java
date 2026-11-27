package ink.hoka.odyssey.client.guns;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Картинка круга оптики: проекция граней сетки и наложение с альфой. */
class ReticleTest {
    /** Квадрат на тангенсах [x0..x1]×[y0..y1] (y вверх) с uv на весь тексель. */
    private static float[] quad(float x0, float y0, float x1, float y1) {
        return new float[] {x0, y1, 0, 0, x0, y0, 0, 1, x1, y0, 1, 1, x1, y1, 1, 0};
    }

    @Test
    void fillsAndOrientation() {
        int red = 0xFFFF0000, black = 0xFF000000;
        // левая верхняя четверть — красная (x < 0, y > 0 → левый верх картинки)
        int[] out = Reticle.rasterize(List.of(quad(-1, 0, 0, 1)), new int[] {red}, 1, 1, 8, 1);
        assertEquals(red, out[0]);                 // (0, 0) — левый верх
        assertEquals(0, out[7]);                   // правый верх пуст
        assertEquals(0, out[7 * 8]);               // левый низ пуст
        // поверх — чёрное на всю картинку, потом полупрозрачное
        out = Reticle.rasterize(List.of(quad(-1, -1, 1, 1), quad(-1, -1, 1, 1)), new int[] {black}, 1, 1, 4, 1);
        assertEquals(black, out[5]);
    }

    @Test
    void thinStrokeStillVisible() {
        int black = 0xFF000000;
        // штрих в 0,1 пикселя шириной через всю картинку по горизонтали — виден в каждой колонке
        float half = 1, n = 64, px = 2 * half / n;
        int[] out = Reticle.rasterize(List.of(quad(-1, 0, 1, px * 0.1f)), new int[] {black}, 1, 1, (int) n, half);
        for (int x = 1; x < n - 1; x++) {
            boolean any = false;
            for (int y = 0; y < n; y++) any |= out[y * (int) n + x] == black;
            assertEquals(true, any, "колонка " + x);
        }
    }

    @Test
    void alphaOver() {
        assertEquals(0xFF000000, Reticle.over(0xFF000000, 0xFFFFFFFF));
        assertEquals(0xFFFFFFFF, Reticle.over(0x00000000, 0xFFFFFFFF));
        int half = Reticle.over(0x80000000, 0xFFFFFFFF);
        assertEquals(0xFF, half >>> 24);
        int r = (half >> 16) & 255;
        assertEquals(127, r, 1.0);
    }
}
