package core.amazake.systems.rezusmenu.rpg;

import java.util.List;

/**
 * Раскладка всплывающей подсказки в пикселях панели: ширина по самой длинной строке (оценка по ширине символов
 * шрифта), сбоку от слота — справа, а если не влезает, слева; нижний край на уровне низа слота, а если подсказка
 * вылезает за верх холста — опускается. Слишком длинная обрезается ({@link #cut()}).
 *
 * @param left   левый край, px панели
 * @param bottom нижний край, px панели
 * @param width  ширина рамки, px
 * @param lines  сколько строк показать
 * @param cut    часть строк не влезла
 */
record TipLayout(double left, double bottom, double width, int lines, boolean cut) {

    /** Высота строки подсказки, px. */
    static final double LH = 7;
    /** Перенос строк длиннее этого, px. */
    static final double WRAP_W = 190;
    /** Средняя ширина символа на строку высотой {@link #LH}: шрифт 6 из 10 единиц строки. */
    static final double CHAR_W = LH * 0.6;
    /** Холст в координатах панели: x −96..608, y −56..344. */
    static final double CANVAS_LEFT = -96 + 4;
    static final double CANVAS_RIGHT = 608 - 4;
    static final double CANVAS_TOP = -56 + 4;
    static final double CANVAS_BOTTOM = 344 - 4;
    static final int MAX_LINES = 40;

    static TipLayout of(final List<String> plain, final double slotX, final double slotY, final double slotSize) {
        int rows = 0;
        int shown = 0;
        double widest = 0;
        final int maxRows = (int) Math.floor((CANVAS_BOTTOM - CANVAS_TOP) / LH) - 1;
        for (final String line : plain) {
            final double w = line.length() * CHAR_W;
            final int wraps = Math.max(1, (int) Math.ceil(w / WRAP_W));
            if (rows + wraps > Math.min(MAX_LINES, maxRows)) {
                break;
            }
            rows += wraps;
            shown++;
            widest = Math.max(widest, Math.min(w, WRAP_W));
        }
        final boolean cut = shown < plain.size();
        if (cut) {
            rows++;
        }
        final double width = Math.max(40, widest + 4);
        double left = slotX + slotSize + 4;
        if (left + width > CANVAS_RIGHT) {
            left = slotX - 4 - width;
        }
        left = Math.max(CANVAS_LEFT, Math.min(CANVAS_RIGHT - width, left));
        double bottom = slotY + slotSize;
        final double height = rows * LH;
        if (bottom - height < CANVAS_TOP) {
            bottom = CANVAS_TOP + height;
        }
        bottom = Math.min(CANVAS_BOTTOM, bottom);
        return new TipLayout(left, bottom, width, shown, cut);
    }
}
