package core.amazake.systems.coreeffects.hud;

import java.util.List;

/**
 * Экранные накладки — глифы шрифта {@code coreeffects:overlay} в заголовке (title). Чистая логика: какая строка
 * символов рисует какие слои.
 *
 * <p>Картинка накладки 512×256 режется на 4 клетки 256×128 (больше 256 в шрифт не влезает): верхний ряд — свой
 * bitmap-провайдер с ascent 69, нижний — с ascent −3, высота клетки 72 → ширина 144, шаг (advance) 145. Ряд:
 * {@code A NEG1 B} = 289; {@code BACK} (−289) возвращает курсор, и следующий ряд/слой ложится поверх. Итог любой строки
 * — ширина 289, поэтому заголовок центрирует её одинаково. Заголовок рисуется ×4 — накладка 1152×576 единиц GUI с
 * центром в центре экрана (см. README, «Экран»).</p>
 */
public final class Overlay {

    /** Шаг клетки и полной ширины в единицах шрифта (для проверки в тестах). */
    public static final int TILE_ADVANCE = 145;
    public static final int FULL_ADVANCE = 289;

    public static final char NEG1 = '';
    public static final char BACK = '';
    /** Сплошное затемнение 25% (одна клетка 16×8 растянута на весь размер). */
    public static final char DARK = '';

    /** Слой: четыре клетки (или сплошной). */
    public enum Layer {
        DARK_FILL(DARK),
        VIGNETTE(''),
        BLOOD(''),
        HEAT(''),
        CURSE(''),
        PANIC('');

        private final char first;

        Layer(final char first) {
            this.first = first;
        }

        /** Символы слоя с возвратом курсора в начало: ширина 0 перед следующим слоем. */
        void append(final StringBuilder sb) {
            if (this == DARK_FILL) {
                sb.append(DARK);
                return;
            }
            sb.append(this.first).append(NEG1).append((char) (this.first + 1)).append(BACK)
                .append((char) (this.first + 2)).append(NEG1).append((char) (this.first + 3));
        }
    }

    /** Слой и сколько раз его наложить (прозрачность складывается: 1 − (1 − a)^n). */
    public record Part(Layer layer, int count) {
    }

    private Overlay() {
    }

    /** Строка заголовка; пусто — накладки нет. */
    public static String compose(final List<Part> parts) {
        final StringBuilder sb = new StringBuilder();
        for (final Part p : parts) {
            for (int i = 0; i < p.count(); i++) {
                if (!sb.isEmpty()) {
                    sb.append(BACK);
                }
                p.layer().append(sb);
            }
        }
        return sb.toString();
    }

    /** Ширина строки по шагам глифов (как считает клиент) — для тестов. */
    public static int width(final String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == NEG1) {
                w -= 1;
            } else if (c == BACK) {
                w -= FULL_ADVANCE;
            } else if (c == DARK) {
                w += FULL_ADVANCE;
            } else {
                w += TILE_ADVANCE;
            }
        }
        return w;
    }
}
