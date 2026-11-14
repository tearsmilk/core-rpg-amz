package core.amazake.systems.rezusmenu.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** Вид спереди из синтетического скина: каждая часть залита своим цветом. */
class SkinFrontTest {

    private static final int HEAD = 0xFF110000;
    private static final int HAT = 0xFF220000;
    private static final int BODY = 0xFF003300;
    private static final int R_ARM = 0xFF000044;
    private static final int L_ARM = 0xFF000055;
    private static final int R_LEG = 0xFF660000;
    private static final int L_LEG = 0xFF770000;
    private static final int JACKET_HALF = 0x80FFFFFF;

    private static void fill(final BufferedImage img, final int x, final int y, final int w, final int h, final int argb) {
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                img.setRGB(x + i, y + j, argb);
            }
        }
    }

    private static BufferedImage modern(final int armW) {
        final BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(img, 8, 8, 8, 8, HEAD);
        fill(img, 41, 9, 2, 2, HAT);          // кусочек шляпы, остальное прозрачно
        fill(img, 20, 20, 8, 12, BODY);
        fill(img, 20, 36, 8, 1, JACKET_HALF); // полупрозрачная полоса куртки
        fill(img, 44, 20, armW, 12, R_ARM);
        fill(img, 36, 52, armW, 12, L_ARM);
        fill(img, 4, 20, 4, 12, R_LEG);
        fill(img, 20, 52, 4, 12, L_LEG);
        return img;
    }

    private static int at(final int[] out, final int x, final int y) {
        return out[y * SkinFront.W + x];
    }

    @Test
    void classicFrontLayout() {
        final int[] out = SkinFront.compose(modern(4), false);
        assertEquals(SkinFront.W * SkinFront.H, out.length);
        assertEquals(HEAD, at(out, 4, 0));
        assertEquals(HEAD, at(out, 11, 7));
        assertEquals(HAT, at(out, 5, 1));           // шляпа поверх головы
        assertEquals(0, at(out, 0, 0));             // по бокам головы пусто
        assertEquals(0, at(out, 15, 7));
        assertEquals(R_ARM, at(out, 0, 8));         // правая рука персонажа — слева
        assertEquals(R_ARM, at(out, 3, 19));
        assertEquals(L_ARM, at(out, 12, 8));
        assertEquals(L_ARM, at(out, 15, 19));
        assertEquals(BODY, at(out, 4, 9));
        assertEquals(R_LEG, at(out, 4, 20));
        assertEquals(L_LEG, at(out, 11, 31));
        assertEquals(0, at(out, 0, 20));            // рядом с ногами пусто
        // куртка 50 % белого поверх тела
        final int mixed = at(out, 4, 8);
        assertEquals(0xFF, mixed >>> 24);
        assertEquals(0x80, (mixed >> 16) & 0xFF, 1);
    }

    @Test
    void slimArmsAreThreeWideNextToBody() {
        final int[] out = SkinFront.compose(modern(3), true);
        assertEquals(0, at(out, 0, 8));
        assertEquals(R_ARM, at(out, 1, 8));
        assertEquals(R_ARM, at(out, 3, 8));
        assertEquals(L_ARM, at(out, 12, 8));
        assertEquals(L_ARM, at(out, 14, 8));
        assertEquals(0, at(out, 15, 8));
    }

    /** 64 × 32: левые конечности — зеркало правых; сплошная шляпа не рисуется. */
    @Test
    void legacySkinMirrorsRightLimbs() {
        final BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        fill(img, 8, 8, 8, 8, HEAD);
        fill(img, 40, 8, 8, 8, HAT);                // сплошная — игнор
        fill(img, 44, 20, 4, 12, R_ARM);
        img.setRGB(44, 20, 0xFF0000FF);             // левый верхний пиксель руки
        fill(img, 4, 20, 4, 12, R_LEG);
        final int[] out = SkinFront.compose(img, true); // slim у старых не бывает
        assertEquals(HEAD, at(out, 6, 3));
        assertEquals(0xFF0000FF, at(out, 0, 8));
        assertEquals(0xFF0000FF, at(out, 15, 8));    // отражение: в правом верхнем углу левой руки
        assertEquals(R_ARM, at(out, 12, 8));
        assertEquals(R_LEG, at(out, 8, 25));
    }

    @Test
    void rejectsWrongSize() {
        assertNull(SkinFront.compose(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), false));
        assertNull(SkinFront.compose(null, false));
    }
}
