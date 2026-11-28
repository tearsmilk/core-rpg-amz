package ink.hoka.odyssey.client.starmap;

import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2fStack;

/**
 * Общие мелочи радара: цвета для меток на экране без карты, прозрачность, отрезок под углом (поворотом матрицы
 * GUI), ромбик, форматы расстояний и высот, плавности появления. Само окно радара рисуется своими текстурами
 * ({@link Raster}, {@link RadarScreen}).
 */
final class Radar {
    static final int AMBER = 0xFFFFC857;

    private Radar() {
    }

    /** Цвет с прозрачностью, умноженной на a (0..1). */
    static int a(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 255) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    /** Цвет с заданной прозрачностью (0..255). */
    static int alpha(int argb, int al) {
        return (Math.max(0, Math.min(255, al)) << 24) | (argb & 0xFFFFFF);
    }

    /** Отрезок из (x, y) под углом ang (радианы, 0 — вправо, по часовой — вниз) длиной len, толщиной th. */
    static void line(GuiGraphics g, float x, float y, float ang, float len, int th, int col) {
        Matrix3x2fStack p = g.pose();
        p.pushMatrix();
        p.translate(x, y);
        p.rotate(ang);
        g.fill(0, -th / 2, Math.max(1, Math.round(len)), -th / 2 + th, col);
        p.popMatrix();
    }

    /** Ромбик (точка-метка) с центром в (x, y). */
    static void diamond(GuiGraphics g, int x, int y, int r, int col) {
        for (int i = -r; i <= r; i++) {
            int w = r - Math.abs(i);
            g.fill(x - w, y + i, x + w + 1, y + i + 1, col);
        }
    }

    /** Расстояние для подписей: 87 м, 1,2 км. */
    static String dist(double d) {
        if (d < 1000) return Math.round(d) + "м";
        return String.format(java.util.Locale.ROOT, "%.1fкм", d / 1000).replace('.', ',');
    }

    /** Разница высот со знаком: +12, −30. */
    static String dy(double d) {
        long v = Math.round(d);
        return v > 0 ? "+" + v : v < 0 ? "−" + (-v) : "±0";
    }

    static float easeOut(float p) {
        p = Math.max(0, Math.min(1, p));
        return 1 - (1 - p) * (1 - p) * (1 - p);
    }

    static float easeBack(float p) {
        p = Math.max(0, Math.min(1, p));
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(p - 1, 3) + c1 * (float) Math.pow(p - 1, 2);
    }
}
