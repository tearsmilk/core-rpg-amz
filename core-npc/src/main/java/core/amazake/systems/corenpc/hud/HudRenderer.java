package core.amazake.systems.corenpc.hud;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;

/**
 * Собирает строку actionbar с окном диалога: глиф окна, затем каждая строка своим шрифтом (свой ascent — своя высота)
 * и сдвиг назад на её ширину. Итоговая ширина = ширине окна, поэтому клиент центрирует окно ровно.
 */
public final class HudRenderer {

    private final FontWidths widths;

    public HudRenderer(final FontWidths widths) {
        this.widths = widths;
    }

    private static Component shift(final int px) {
        return Component.text(Shift.of(px)).font(HudLayout.DIALOG_FONT);
    }

    /**
     * speaker — имя (может быть пустым), lines — до 3 строк текста, answers — до 3 строк ответов (уже с номером и
     * пометкой выбора), selectedRow — какая из них подсвечена (−1 — никакая), next — показать «▼».
     */
    public Component render(final Component speaker, final List<Component> lines, final List<Component> answers,
                            final int selectedRow, final boolean next) {
        return this.render(speaker, lines, answers, selectedRow, next, 0);
    }

    /**
     * offsetX — сдвиг окна вправо (влево, если &lt; 0) от центра экрана в пикселях GUI: пробел +offsetX в начале и
     * −offsetX в конце, общая ширина строки не меняется — клиент центрирует так же, а окно стоит правее.
     */
    public Component render(final Component speaker, final List<Component> lines, final List<Component> answers,
                            final int selectedRow, final boolean next, final int offsetX) {
        final TextComponent.Builder b = Component.text();
        if (offsetX != 0) {
            b.append(shift(offsetX));
        }
        int x = 0;   // где сейчас «перо» относительно левого края раскладки
        x = this.glyph(b, x, 0, HudLayout.BOX, HudLayout.BOX_ADVANCE);
        if (!answers.isEmpty()) {
            x = this.glyph(b, x, HudLayout.PANEL_X, HudLayout.PANEL, HudLayout.PANEL_ADVANCE);
        }
        x = this.row(b, x, HudLayout.NAME_X, speaker, HudLayout.ROWS[0]);
        for (int i = 0; i < Math.min(HudLayout.TEXT_ROWS, lines.size()); i++) {
            x = this.row(b, x, HudLayout.TEXT_X, lines.get(i), HudLayout.ROWS[1 + i]);
        }
        for (int i = 0; i < Math.min(HudLayout.ANSWER_ROWS, answers.size()); i++) {
            if (i == selectedRow) {
                x = this.glyph(b, x, HudLayout.POINTER_X, HudLayout.POINTER[i], HudLayout.POINTER_ADVANCE);
            }
            x = this.row(b, x, HudLayout.ANSWER_X, answers.get(i), HudLayout.ROWS[4 + i]);
        }
        if (next) {
            x = this.glyph(b, x, HudLayout.NEXT_X, HudLayout.NEXT, HudLayout.NEXT_ADVANCE);
        }
        b.append(shift(HudLayout.TOTAL_W - x));
        if (offsetX != 0) {
            b.append(shift(-offsetX));
        }
        return b.build();
    }

    /** Глиф окна (шрифт corenpc:dialog) с левым краем на at; возвращает новое положение «пера». */
    private int glyph(final TextComponent.Builder b, final int x, final int at, final String glyph, final int advance) {
        b.append(shift(at - x));
        b.append(Component.text(glyph).font(HudLayout.DIALOG_FONT).color(NamedTextColor.WHITE)
            .shadowColor(ShadowColor.none()));
        return at + advance;
    }

    private int row(final TextComponent.Builder b, final int x, final int at, final Component text,
                    final net.kyori.adventure.key.Key font) {
        if (text == null) {
            return x;
        }
        final Component safe = this.widths.sanitize(text);
        final int w = this.widths.width(safe);
        if (w == 0) {
            return x;
        }
        b.append(shift(at - x));
        b.append(Component.text().font(font).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none()).append(safe)
            .build());
        return at + w;
    }

    /** Ширина текста в пикселях GUI (для проверки, влезает ли строка). */
    public int width(final Component c) {
        return this.widths.width(c);
    }
}
