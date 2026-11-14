package core.amazake.systems.rezusmenu.screen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Чистая математика меню (без сервера).
 *
 * <p>Холст — 704 × 400 px (начало — левый верхний угол, y вниз); панель 512 × 288 стоит в нём с левым верхним углом
 * в {@link #PANEL_X}, {@link #PANEL_Y}. Единицы камеры — блоки на расстоянии 1 перед глазами, (0, 0) — центр
 * экрана, y вверх. Пиксели шрифта — то, в чём текстовый дисплей раскладывает глифы (до масштаба 0.025).</p>
 */
public final class PanelMath {

    /** Холст целиком, px. */
    public static final double W = 704;
    public static final double H = 400;
    /** Левый верхний угол панели на холсте. */
    public static final double PANEL_X = 96;
    public static final double PANEL_Y = 56;
    /** Какую долю откалиброванного экрана занимает холст. */
    public static final double FILL = 0.98;
    /** Пиксель шрифта в блоках при масштабе дисплея 1. */
    public static final double FONT_PX = 0.025;
    /** Высота строки текстового дисплея, px шрифта (9 + 1, зашито в клиенте). */
    public static final int LINE_H = 10;

    /** Символы склейки «пикселей» портрета: пиксель (ширина 2, рисует 1), шаг −1 и пустой шаг +1. */
    public static final char PIXEL = '';
    public static final char NEG1 = '';
    public static final char POS1 = '';
    /** Глиф «пикселя»: высота и ascent 1. */
    public static final int PIXEL_ASCENT = 1;

    /** Размер текстуры (px) — для накладок, которые ставятся левым верхним углом. */
    public record Size(int w, int h) {
    }

    /** Прямоугольник на холсте, px: левый верхний угол (x, y), размер w × h. */
    public record Rect(double x, double y, double w, double h) {

        /** Точка внутри: левый и верхний край включительно, правый и нижний — нет. */
        public boolean contains(final double px, final double py) {
            return px >= this.x && px < this.x + this.w && py >= this.y && py < this.y + this.h;
        }
    }

    /** Глиф текстуры в шрифте {@code rezusmenu:ui}: символ и размер картинки, px. */
    public record Glyph(char ch, int w, int h) {
    }

    /**
     * Где глиф лежит относительно точки текстового дисплея, px шрифта, x вправо, y вверх (до масштаба 0.025 и
     * трансформации дисплея).
     */
    public record FontBox(double left, double top, double w, double h) {
    }

    /** Перенос и масштаб дисплея (единицы камеры), чтобы {@link FontBox} лёг в заданный прямоугольник. */
    public record Fit(double tx, double ty, double sx, double sy) {
    }

    /**
     * Порядок символов: {@code U+E000 + индекс}. Совпадает с {@code font/ui.json} в паке (генератор пака пишет его
     * в том же порядке). Накладки — содержимое в левом верхнем углу, остальное прозрачно; у каждой текстуры правый
     * столбец не пустой (там невидимый пиксель с альфой 1), поэтому ширина глифа = ширина текстуры.
     */
    static final List<String> ORDER = List.of(
        "panel_0", "panel_1", "panel_2", "panel_3", "tab_active", "tab_hover", "slot_hover", "slot_selected",
        "button_hover", "bar_exp", "bar_hp", "bar_mana", "cursor", "bar_energy", "skills_bg_0", "skills_bg_1",
        "skill_hover", "skill_locked", "cursor_mirror",
        "back_0_0", "back_0_1", "back_0_2", "back_1_0", "back_1_1", "back_1_2", "back_2_0", "back_2_1", "back_2_2");

    private static final Map<String, Size> SIZES = Map.ofEntries(
        Map.entry("panel_0", new Size(256, 144)), Map.entry("panel_1", new Size(256, 144)),
        Map.entry("panel_2", new Size(256, 144)), Map.entry("panel_3", new Size(256, 144)),
        Map.entry("tab_active", new Size(128, 32)), Map.entry("tab_hover", new Size(128, 32)),
        Map.entry("slot_hover", new Size(32, 32)), Map.entry("slot_selected", new Size(32, 32)),
        Map.entry("button_hover", new Size(144, 32)),
        Map.entry("bar_exp", new Size(176, 16)), Map.entry("bar_hp", new Size(176, 16)),
        Map.entry("bar_mana", new Size(176, 16)), Map.entry("bar_energy", new Size(176, 16)),
        Map.entry("cursor", new Size(16, 16)), Map.entry("cursor_mirror", new Size(16, 16)),
        Map.entry("skills_bg_0", new Size(144, 224)), Map.entry("skills_bg_1", new Size(144, 224)),
        Map.entry("skill_hover", new Size(64, 80)), Map.entry("skill_locked", new Size(64, 80)),
        Map.entry("back_0_0", new Size(256, 144)), Map.entry("back_0_1", new Size(256, 144)),
        Map.entry("back_0_2", new Size(192, 144)), Map.entry("back_1_0", new Size(256, 144)),
        Map.entry("back_1_1", new Size(256, 144)), Map.entry("back_1_2", new Size(192, 144)),
        Map.entry("back_2_0", new Size(256, 112)), Map.entry("back_2_1", new Size(256, 112)),
        Map.entry("back_2_2", new Size(192, 112)));

    private static final Map<String, Glyph> GLYPHS;

    static {
        final Map<String, Glyph> m = new HashMap<>();
        for (int i = 0; i < ORDER.size(); i++) {
            final Size s = SIZES.get(ORDER.get(i));
            m.put(ORDER.get(i), new Glyph((char) (0xE000 + i), s.w(), s.h()));
        }
        GLYPHS = Map.copyOf(m);
    }

    /** Колонки и строки фоновых тайлов {@code back_r_c}. */
    public static final int[] BACK_X = {0, 256, 512};
    public static final int[] BACK_Y = {0, 144, 288};

    private PanelMath() {
    }

    /** Глиф текстуры; null — неизвестный id. */
    public static Glyph glyph(final String id) {
        return GLYPHS.get(id);
    }

    /** Все id текстур (для пака и тестов). */
    public static java.util.Set<String> glyphIds() {
        return GLYPHS.keySet();
    }

    /** Размер текстуры; null — неизвестный id. */
    public static Size texture(final String id) {
        final Glyph g = GLYPHS.get(id);
        return g == null ? null : new Size(g.w(), g.h());
    }

    // --- холст ↔ камера ---

    /** Сколько единиц камеры в одном пикселе холста: холст вписан в {@link #FILL} откалиброванного экрана. */
    public static double scale(final double halfW, final double halfH) {
        return Math.min(2 * halfW * FILL / W, 2 * halfH * FILL / H);
    }

    public static double camX(final double px, final double s) {
        return (px - W / 2) * s;
    }

    public static double camY(final double py, final double s) {
        return (H / 2 - py) * s;
    }

    public static double canvasX(final double camX, final double s) {
        return camX / s + W / 2;
    }

    public static double canvasY(final double camY, final double s) {
        return H / 2 - camY / s;
    }

    /** Индекс последнего (верхнего) прямоугольника, в который попала точка (px), или −1. */
    public static int hit(final List<Rect> rects, final double px, final double py) {
        for (int i = rects.size() - 1; i >= 0; i--) {
            if (rects.get(i).contains(px, py)) {
                return i;
            }
        }
        return -1;
    }

    // --- раскладка глифов текстового дисплея (клиент 1.21.11, DisplayRenderer$TextDisplayRenderer) ---

    /**
     * Один bitmap-глиф W × H (ascent = H, правый столбец непрозрачный) — единственный в одной строке дисплея.
     * Ширина строки w = W + 1 (advance), строка начинается в x = 1 − w/2; верх глифа — ascent + 2 над точкой
     * дисплея (высота строки 10, строка рисуется от −9, глиф — от 7 − ascent).
     */
    public static FontBox glyphBox(final int w, final int h) {
        return lineBox(w + 1, 0, w, h, h);
    }

    /**
     * Общий случай для одной строки: ширина строки {@code lineWidth} (сумма advance, округлённая вверх),
     * глиф начинается через {@code xOffset} от начала строки, рисует {@code quadW} × {@code quadH}, ascent
     * {@code ascent}.
     */
    public static FontBox lineBox(final int lineWidth, final double xOffset, final double quadW, final double quadH,
                                  final double ascent) {
        final double left = 1.0 - lineWidth / 2.0 + xOffset;
        final double top = (LINE_H - 1) - (7 - ascent);
        return new FontBox(left, top, quadW, quadH);
    }

    /**
     * Перенос и масштаб дисплея, чтобы прямоугольник шрифта {@code box} лёг левым верхним углом в (left, top) с размером w × h — всё в
     * единицах камеры, y вверх; потом картинка сдвигается ещё на ({@code fineX}, {@code fineY}) px шрифта вправо и
     * вверх (тонкая подстройка из config.yml, обычно 0). Масштаб — множитель дисплея (1 px шрифта = 0.025 блока при масштабе 1).
     */
    public static Fit fit(final FontBox box, final double left, final double top, final double w, final double h,
                          final double fineX, final double fineY) {
        final double kx = w / box.w();
        final double ky = h / box.h();
        final double tx = left - kx * (box.left() - fineX);
        final double ty = top - ky * (box.top() - fineY);
        return new Fit(tx, ty, kx / FONT_PX, ky / FONT_PX);
    }

    /** Где после {@link #fit} окажется точка шрифта (fx, fy): единицы камеры. Для тестов. */
    public static double[] apply(final Fit f, final double fx, final double fy) {
        return new double[] {f.tx() + f.sx() * FONT_PX * fx, f.ty() + f.sy() * FONT_PX * fy};
    }

    // --- шейдерный курсор (зеркало относительно центра экрана) ---

    /** Базис взгляда: вперёд, вправо, вверх (мировые оси Minecraft; yaw/pitch в градусах). */
    public static double[][] viewBasis(final double yaw, final double pitch) {
        final double y = Math.toRadians(yaw);
        final double p = Math.toRadians(pitch);
        final double[] f = {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
        final double[] r = {-Math.cos(y), 0, -Math.sin(y)};
        final double[] u = {
            r[1] * f[2] - r[2] * f[1],
            r[2] * f[0] - r[0] * f[2],
            r[0] * f[1] - r[1] * f[0]};
        return new double[][] {f, r, u};
    }

    /**
     * Куда шейдер рисует точку {@code p} мира с маркерным цветом: отражение через центр экрана. Ответ — единицы
     * камеры на расстоянии 1 (x вправо, y вверх); null — точка позади или у самых глаз.
     */
    public static double[] mirror(final double[] eye, final double yaw, final double pitch, final double[] p) {
        final double[][] b = viewBasis(yaw, pitch);
        final double[] d = {p[0] - eye[0], p[1] - eye[1], p[2] - eye[2]};
        final double depth = dot(d, b[0]);
        if (depth < 0.05) {
            return null;
        }
        return new double[] {-dot(d, b[1]) / depth, -dot(d, b[2]) / depth};
    }

    /** Точка мира на расстоянии {@code dist} от глаз, которую шейдер покажет в (sx, sy) единиц камеры. */
    public static double[] anchor(final double[] eye, final double yaw, final double pitch, final double sx,
                                  final double sy, final double dist) {
        final double[][] b = viewBasis(yaw, pitch);
        final double[] dir = new double[3];
        for (int i = 0; i < 3; i++) {
            dir[i] = b[0][i] - sx * b[1][i] - sy * b[2][i];
        }
        final double n = Math.sqrt(dot(dir, dir));
        return new double[] {eye[0] + dir[0] / n * dist, eye[1] + dir[1] / n * dist, eye[2] + dir[2] / n * dist};
    }

    /** Поворот (yaw, pitch), смотрящий из {@code from} в {@code to}. */
    public static float[] lookAt(final double[] from, final double[] to) {
        final double dx = to[0] - from[0];
        final double dy = to[1] - from[1];
        final double dz = to[2] - from[2];
        final double h = Math.sqrt(dx * dx + dz * dz);
        final float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        final float pitch = (float) Math.toDegrees(Math.atan2(-dy, h));
        return new float[] {yaw, pitch};
    }

    private static double dot(final double[] a, final double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
