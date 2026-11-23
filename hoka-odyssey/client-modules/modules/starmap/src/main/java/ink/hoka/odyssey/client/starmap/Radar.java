package ink.hoka.odyssey.client.starmap;

import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2fStack;

/**
 * Палитра и рисование радара. Палитра — «люминофор»: зелёный/бирюзовый на почти чёрном; рамки — как у окон
 * houi ядра (box: заливка, рамка 1 px, пиксельные скруглённые углы), шрифт — пиксельный шрифт игры.
 * Линии под углом — поворотом матрицы GUI (одна заливка на линию), окружности — по точкам (чёткие пиксели).
 */
final class Radar {
    static final int PANEL = 0xFF050B08;
    static final int PANEL2 = 0xFF081310;
    static final int SCOPE = 0xFF020705;
    static final int BORDER = 0xFF1C5E3F;
    static final int BORDER_HI = 0xFF3FD48A;
    static final int PHOS = 0xFF7CFFB6;
    static final int PHOS_MID = 0xFF3FD48A;
    static final int PHOS_DIM = 0xFF237A52;
    static final int CYAN = 0xFF66F2FF;
    static final int AMBER = 0xFFFFC857;
    static final int RED = 0xFFFF6A55;
    static final int WHITE = 0xFFE8FFF2;

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

    /** Отрезок между двумя точками. */
    static void seg(GuiGraphics g, float x0, float y0, float x1, float y1, int th, int col) {
        float dx = x1 - x0, dy = y1 - y0;
        line(g, x0, y0, (float) Math.atan2(dy, dx), (float) Math.sqrt(dx * dx + dy * dy), th, col);
    }

    /** Окружность по точкам; dash > 0 — пунктир (рисуется dash точек из каждых 2·dash). */
    static void ring(GuiGraphics g, int cx, int cy, float r, int col, int dash) {
        int n = Math.max(24, (int) (r * 2 * Math.PI / 1.5f));
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (dash > 0 && (i / dash) % 2 == 1) continue;
            double t = i * 2 * Math.PI / n;
            int x = cx + (int) Math.round(Math.cos(t) * r), y = cy + (int) Math.round(Math.sin(t) * r);
            if (x == lastX && y == lastY) continue;
            g.fill(x, y, x + 1, y + 1, col);
            lastX = x;
            lastY = y;
        }
    }

    /** Закрашенный круг строками (дно экрана радара). */
    static void disc(GuiGraphics g, int cx, int cy, int r, int col) {
        for (int y = -r; y < r; y++) {
            double yy = y + 0.5;
            int w = (int) Math.round(Math.sqrt(r * (double) r - yy * yy));
            g.fill(cx - w, cy + y, cx + w, cy + y + 1, col);
        }
    }

    /** Всё в квадрате вокруг круга, кроме самого круга, — залить (маска для слоя блоков). */
    static void mask(GuiGraphics g, int cx, int cy, int r, int col) {
        for (int y = -r; y < r; y++) {
            double yy = y + 0.5;
            int w = (int) Math.round(Math.sqrt(r * (double) r - yy * yy));
            g.fill(cx - r, cy + y, cx - w, cy + y + 1, col);
            g.fill(cx + w, cy + y, cx + r, cy + y + 1, col);
        }
    }

    /** Ромбик (точка-метка) с центром в (x, y). */
    static void diamond(GuiGraphics g, int x, int y, int r, int col) {
        for (int i = -r; i <= r; i++) {
            int w = r - Math.abs(i);
            g.fill(x - w, y + i, x + w + 1, y + i + 1, col);
        }
    }

    /** Контур ромбика. */
    static void diamondOutline(GuiGraphics g, int x, int y, int r, int col) {
        for (int i = -r; i <= r; i++) {
            int w = r - Math.abs(i);
            g.fill(x - w, y + i, x - w + 1, y + i + 1, col);
            g.fill(x + w, y + i, x + w + 1, y + i + 1, col);
        }
    }

    /**
     * Стрелка-треугольник носом по курсу yaw (градусы Minecraft: 0 — на юг, вниз по экрану; 90 — на запад).
     * len — длина, wide — полуширина у основания.
     */
    static void arrow(GuiGraphics g, float x, float y, float yaw, int len, int wide, int col) {
        Matrix3x2fStack p = g.pose();
        p.pushMatrix();
        p.translate(x, y);
        p.rotate((float) Math.toRadians(yaw + 180));             // локальный «вверх» (−y) → курс yaw
        int tip = -len * 2 / 3;
        for (int i = 0; i < len; i++) {
            int w = Math.round(wide * i / (float) Math.max(1, len - 1));
            g.fill(-w, tip + i, w + 1, tip + i + 1, col);
        }
        p.popMatrix();
    }

    /** Окно как у houi (box): заливка, рамка 1 px, пиксельные скругления радиуса r. */
    static void box(GuiGraphics g, int x, int y, int w, int h, int fill, int border, int r) {
        for (int i = 0; i < r; i++) {
            int in = r - i;
            g.fill(x + in, y + i, x + w - in, y + i + 1, fill);
            g.fill(x + in, y + h - 1 - i, x + w - in, y + h - i, fill);
        }
        g.fill(x, y + r, x + w, y + h - r, fill);
        if (border == 0) return;
        g.fill(x + r, y, x + w - r, y + 1, border);
        g.fill(x + r, y + h - 1, x + w - r, y + h, border);
        g.fill(x, y + r, x + 1, y + h - r, border);
        g.fill(x + w - 1, y + r, x + w, y + h - r, border);
        for (int i = 0; i < r; i++) {
            g.fill(x + r - i - 1, y + i + 1, x + r - i, y + i + 2, border);
            g.fill(x + w - r + i, y + i + 1, x + w - r + i + 1, y + i + 2, border);
            g.fill(x + r - i - 1, y + h - i - 2, x + r - i, y + h - i - 1, border);
            g.fill(x + w - r + i, y + h - i - 2, x + w - r + i + 1, y + h - i - 1, border);
        }
    }

    /** Уголки-скобки вокруг прямоугольника (рамка «прицела»). */
    static void brackets(GuiGraphics g, int x, int y, int w, int h, int len, int col) {
        g.fill(x, y, x + len, y + 1, col);
        g.fill(x, y, x + 1, y + len, col);
        g.fill(x + w - len, y, x + w, y + 1, col);
        g.fill(x + w - 1, y, x + w, y + len, col);
        g.fill(x, y + h - 1, x + len, y + h, col);
        g.fill(x, y + h - len, x + 1, y + h, col);
        g.fill(x + w - len, y + h - 1, x + w, y + h, col);
        g.fill(x + w - 1, y + h - len, x + w, y + h, col);
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
