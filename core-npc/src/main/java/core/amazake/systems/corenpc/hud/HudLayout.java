package core.amazake.systems.corenpc.hud;

import net.kyori.adventure.key.Key;

/** Раскладка окна диалога; числа совпадают с tools/gen_pack.py. */
public final class HudLayout {

    public static final Key DIALOG_FONT = Key.key("corenpc", "dialog");
    /** Шрифты строк: 0 — имя, 1..3 — текст, 4..6 — ответы. */
    public static final Key[] ROWS = new Key[7];
    public static final int TEXT_ROWS = 3;
    public static final int ANSWER_ROWS = 3;
    public static final String BOX = "";
    public static final String HIGHLIGHT = "";
    public static final String NEXT = "";
    /** Ширина окна 248 → шаг глифа 249. */
    public static final int BOX_ADVANCE = 249;
    public static final int HIGHLIGHT_ADVANCE = 237;
    /** Отступ текста от левого края окна. */
    public static final int PAD = 8;
    /** Подсветка начинается на 6 пикселей от края окна. */
    public static final int HIGHLIGHT_X = 6;
    /** Ширина строки текста, после которой она вылезет за окно. */
    public static final int TEXT_WIDTH = 248 - 2 * PAD;
    /** «▼ дальше» у правого нижнего угла. */
    public static final int NEXT_X = 248 - 16;

    static {
        for (int i = 0; i < ROWS.length; i++) {
            ROWS[i] = Key.key("corenpc", "row" + i);
        }
    }

    private HudLayout() {
    }
}
