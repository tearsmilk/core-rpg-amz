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
        final TextComponent.Builder b = Component.text();
        b.append(Component.text(HudLayout.BOX).font(HudLayout.DIALOG_FONT).color(NamedTextColor.WHITE)
            .shadowColor(ShadowColor.none()));
        int x = HudLayout.BOX_ADVANCE;   // где сейчас «перо» относительно левого края окна
        x = this.row(b, x, HudLayout.PAD, speaker, HudLayout.ROWS[0]);
        for (int i = 0; i < Math.min(HudLayout.TEXT_ROWS, lines.size()); i++) {
            x = this.row(b, x, HudLayout.PAD, lines.get(i), HudLayout.ROWS[1 + i]);
        }
        for (int i = 0; i < Math.min(HudLayout.ANSWER_ROWS, answers.size()); i++) {
            if (i == selectedRow) {
                b.append(shift(HudLayout.HIGHLIGHT_X - x));
                b.append(Component.text(HudLayout.HIGHLIGHT).font(HudLayout.ROWS[4 + i]).color(NamedTextColor.WHITE)
                    .shadowColor(ShadowColor.none()));
                x = HudLayout.HIGHLIGHT_X + HudLayout.HIGHLIGHT_ADVANCE;
            }
            x = this.row(b, x, HudLayout.PAD, answers.get(i), HudLayout.ROWS[4 + i]);
        }
        if (next) {
            b.append(shift(HudLayout.NEXT_X - x));
            b.append(Component.text(HudLayout.NEXT).font(HudLayout.ROWS[6]).color(NamedTextColor.WHITE)
                .shadowColor(ShadowColor.none()));
            x = HudLayout.NEXT_X + 8;
        }
        b.append(shift(HudLayout.BOX_ADVANCE - x));
        return b.build();
    }

    private int row(final TextComponent.Builder b, final int x, final int at, final Component text,
                    final net.kyori.adventure.key.Key font) {
        if (text == null) {
            return x;
        }
        final int w = this.widths.width(text);
        if (w == 0) {
            return x;
        }
        b.append(shift(at - x));
        b.append(Component.text().font(font).color(NamedTextColor.WHITE).append(text).build());
        return at + w;
    }

    /** Ширина текста в пикселях GUI (для проверки, влезает ли строка). */
    public int width(final Component c) {
        return this.widths.width(c);
    }
}
