package core.amazake.systems.corenpc.hud;

/** Сдвиг по горизонтали пробелами шрифта corenpc:dialog: U+F801.. = −1,−2,−4…−512, U+F821.. = +1…+512. */
public final class Shift {

    private Shift() {
    }

    public static String of(final int pixels) {
        if (pixels == 0) {
            return "";
        }
        final int base = pixels < 0 ? 0xF801 : 0xF821;
        int left = Math.abs(pixels);
        final StringBuilder sb = new StringBuilder();
        while (left >= 1024) {
            sb.append((char) (base + 9));
            left -= 512;
        }
        for (int bit = 9; bit >= 0; bit--) {
            if ((left & (1 << bit)) != 0) {
                sb.append((char) (base + bit));
            }
        }
        return sb.toString();
    }

    /** Сумма сдвига строки (для тестов). */
    public static int measure(final String s) {
        int sum = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c >= 0xF801 && c <= 0xF80A) {
                sum -= 1 << (c - 0xF801);
            } else if (c >= 0xF821 && c <= 0xF82A) {
                sum += 1 << (c - 0xF821);
            }
        }
        return sum;
    }
}
