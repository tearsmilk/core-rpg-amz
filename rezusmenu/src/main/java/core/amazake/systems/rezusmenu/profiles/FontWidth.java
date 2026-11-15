package core.amazake.systems.rezusmenu.profiles;

/**
 * Ширина строки шрифтом Minecraft по умолчанию, пиксели шрифта (с промежутком 1 после каждого символа). Нужна, чтобы
 * прижать текстовый дисплей к краю: дисплей сам центрирует строку. Приблизительно для редких символов. Чистая логика.
 */
public final class FontWidth {

    private FontWidth() {
    }

    public static int of(final String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += advance(s.charAt(i));
        }
        return w;
    }

    static int advance(final char c) {
        return switch (c) {
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
            case 'l', '`' -> 3;
            case 'I', 't', '[', ']', ' ' -> 4;
            case 'f', 'k', '<', '>', '{', '}', '(', ')', '*', '"' -> 5;
            case '@', '~' -> 7;
            case 'Ж', 'Ш', 'Щ', 'Ю', 'ж', 'ш', 'щ', 'ю', 'Ы', 'ы', 'М', 'м' -> 7;
            default -> 6;
        };
    }
}
