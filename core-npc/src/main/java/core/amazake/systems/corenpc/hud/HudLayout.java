package core.amazake.systems.corenpc.hud;

import net.kyori.adventure.key.Key;

/**
 * Раскладка окна диалога «свиток писаря»; числа совпадают с tools/gen_pack.py. Координаты — от левого края
 * раскладки (ширина {@link #TOTAL_W}, клиент центрирует по ней): слева портрет и плашка имени, ниже пергамент
 * с текстом и пером, сверху справа — листок ответов (только когда ответы есть) с пером-указателем.
 */
public final class HudLayout {

    public static final Key DIALOG_FONT = Key.key("corenpc", "dialog");
    /** Шрифты строк: 0 — имя, 1..3 — текст, 4..6 — ответы (у каждого свой ascent — своя высота). */
    public static final Key[] ROWS = new Key[7];
    public static final int TEXT_ROWS = 3;
    public static final int ANSWER_ROWS = 3;
    /**
     * Основное окно — две половины 142 (U+E000, U+E001; клиент не рисует глиф шире 256), между ними сдвиг −1:
     * шаг половины 143, итого {@link #BOX_ADVANCE}.
     */
    public static final String BOX = "";
    public static final int BOX_ADVANCE = 284 + 1;
    /** Листок ответов (U+E002) с левым краем на {@link #PANEL_X}. */
    public static final String PANEL = "";
    public static final int PANEL_X = 172;
    public static final int PANEL_ADVANCE = 158 + 1;
    /** Перо-указатель выбранного ответа: свой глиф на каждую из трёх строк ответа (свой ascent). */
    public static final String[] POINTER = {"", "", ""};
    public static final int POINTER_X = 166;
    public static final int POINTER_ADVANCE = 16 + 1;
    /** «▼ дальше» внизу пергамента. */
    public static final String NEXT = "";
    public static final int NEXT_X = 226;
    public static final int NEXT_ADVANCE = 7 + 1;
    /** Где начинаются строки: имя на плашке, текст на пергаменте, ответы на листке. */
    public static final int NAME_X = 48;
    public static final int TEXT_X = 38;
    public static final int ANSWER_X = 184;
    /** Общая ширина раскладки. */
    public static final int TOTAL_W = 330;
    /** Ширина строки текста на пергаменте (перенос строк диалога). */
    public static final int TEXT_WIDTH = 224;
    /** Ширина строки ответа на листке (дальше вылезет за листок). */
    public static final int ANSWER_WIDTH = 140;

    static {
        for (int i = 0; i < ROWS.length; i++) {
            ROWS[i] = Key.key("corenpc", "row" + i);
        }
    }

    private HudLayout() {
    }
}
