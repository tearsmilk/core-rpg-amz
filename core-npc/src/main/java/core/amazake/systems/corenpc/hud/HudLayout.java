package core.amazake.systems.corenpc.hud;

import net.kyori.adventure.key.Key;

/** Раскладка окна диалога; числа совпадают с tools/gen_pack.py. */
public final class HudLayout {

    public static final Key DIALOG_FONT = Key.key("corenpc", "dialog");
    /** Шрифты строк: 0 — имя, 1..3 — текст, 4..6 — ответы. */
    public static final Key[] ROWS = new Key[7];
    public static final int TEXT_ROWS = 3;
    public static final int ANSWER_ROWS = 3;
    /**
     * Окно — две половины 160×78 (U+E000, U+E001) со сдвигом −1 между ними: клиент 1.21.11 собирает глифы в атлас
     * 256×256, глиф шире 256 не рисуется. Шаг каждой половины 161, итого 160 + 161 = {@link #BOX_ADVANCE}.
     */
    public static final String BOX = "\uE000\uF801\uE001";
    /** Подсветка ответа — две половины 154 (U+E010, U+E012; U+F801 = −1 есть и в шрифтах строк). Шаг 309. */
    public static final String HIGHLIGHT = "\uE010\uF801\uE012";
    /** «▼ дальше» — только в шрифте row6. */
    public static final String NEXT = "\uE011";
    /** Ширина окна 320 → шаг 321 (как у одного глифа шириной 320). */
    public static final int BOX_WIDTH = 320;
    public static final int BOX_ADVANCE = BOX_WIDTH + 1;
    public static final int HIGHLIGHT_ADVANCE = 309;
    /** Отступ текста от левого края окна. */
    public static final int PAD = 8;
    /** Подсветка начинается на 6 пикселей от края окна. */
    public static final int HIGHLIGHT_X = 6;
    /** Ширина строки текста, после которой она вылезет за окно. */
    public static final int TEXT_WIDTH = BOX_WIDTH - 2 * PAD;
    /** «▼ дальше» у правого нижнего угла. */
    public static final int NEXT_X = BOX_WIDTH - 16;

    static {
        for (int i = 0; i < ROWS.length; i++) {
            ROWS[i] = Key.key("corenpc", "row" + i);
        }
    }

    private HudLayout() {
    }
}
