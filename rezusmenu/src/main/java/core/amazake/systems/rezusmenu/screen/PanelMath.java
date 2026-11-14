package core.amazake.systems.rezusmenu.screen;

import java.util.List;
import java.util.Map;

/**
 * Чистая математика панели (без сервера): холст 512 × 288 px (начало — левый верхний угол, y вниз) ↔ единицы
 * камеры (блоки на расстоянии 1 перед глазами, (0, 0) — центр экрана, y вверх).
 */
public final class PanelMath {

    /** Ширина и высота холста, px. */
    public static final double W = 512;
    public static final double H = 288;
    /** Какую долю откалиброванного экрана занимает панель. */
    public static final double FILL = 0.98;

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
     * Все текстуры пака: символ шрифта и размер. Порядок символов совпадает с {@code font/ui.json} в паке.
     * Накладки — содержимое в левом верхнем углу, остальное прозрачно.
     */
    private static final Map<String, Glyph> GLYPHS = Map.ofEntries(
        Map.entry("panel_0", new Glyph('\uE000', 256, 144)),
        Map.entry("panel_1", new Glyph('\uE001', 256, 144)),
        Map.entry("panel_2", new Glyph('\uE002', 256, 144)),
        Map.entry("panel_3", new Glyph('\uE003', 256, 144)),
        Map.entry("tab_active", new Glyph('\uE004', 128, 32)),
        Map.entry("tab_hover", new Glyph('\uE005', 128, 32)),
        Map.entry("slot_hover", new Glyph('\uE006', 32, 32)),
        Map.entry("slot_selected", new Glyph('\uE007', 32, 32)),
        Map.entry("button_hover", new Glyph('\uE008', 144, 32)),
        Map.entry("bar_exp", new Glyph('\uE009', 16, 16)),
        Map.entry("bar_hp", new Glyph('\uE00A', 16, 16)),
        Map.entry("bar_mana", new Glyph('\uE00B', 16, 16)),
        Map.entry("cursor", new Glyph('\uE00C', 16, 16)));

    /** Глиф текстуры; null — неизвестный id. */
    public static Glyph glyph(final String id) {
        return GLYPHS.get(id);
    }

    /** Все id текстур (для пака и тестов). */
    public static java.util.Set<String> glyphIds() {
        return GLYPHS.keySet();
    }

    private PanelMath() {
    }

    /** Размер текстуры; null — неизвестный id. */
    public static Size texture(final String id) {
        final Glyph g = GLYPHS.get(id);
        return g == null ? null : new Size(g.w(), g.h());
    }

    /** Сколько единиц камеры в одном пикселе холста: панель вписана в {@link #FILL} откалиброванного экрана. */
    public static double scale(final double halfW, final double halfH) {
        return Math.min(2 * halfW * FILL / W, 2 * halfH * FILL / H);
    }

    public static double camX(final double px, final double s) {
        return (px - W / 2) * s;
    }

    public static double camY(final double py, final double s) {
        return (H / 2 - py) * s;
    }

    public static double panelX(final double camX, final double s) {
        return camX / s + W / 2;
    }

    public static double panelY(final double camY, final double s) {
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
}
