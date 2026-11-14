package core.amazake.systems.rezusmenu.screen;

import java.awt.image.BufferedImage;

/**
 * Скин игрока «спереди» — 16 × 32 пикселя (ARGB, 0 — прозрачно), как стоит персонаж лицом к нам: его правая рука
 * и нога — слева. Голова 8 × 8 + шляпа, тело 8 × 12 + куртка, руки 4 × 12 (тонкая модель — 3 × 12) + рукава,
 * ноги 4 × 12 + штаны. Старые скины 64 × 32: левая рука и нога — зеркало правых, второго слоя нет (кроме шляпы).
 * Чистая логика: без сервера.
 */
public final class SkinFront {

    public static final int W = 16;
    public static final int H = 32;

    private SkinFront() {
    }

    /** Вид спереди; null — картинка не похожа на скин (не 64 × 64 и не 64 × 32). */
    public static int[] compose(final BufferedImage skin, final boolean slim) {
        if (skin == null || skin.getWidth() != 64 || (skin.getHeight() != 64 && skin.getHeight() != 32)) {
            return null;
        }
        final boolean legacy = skin.getHeight() == 32;
        final int arm = slim && !legacy ? 3 : 4;
        final int[] out = new int[W * H];
        // голова и шляпа
        copy(skin, 8, 8, 8, 8, false, out, 4, 0, true);
        if (!legacy || !opaqueEverywhere(skin, 40, 8, 8, 8)) {
            copy(skin, 40, 8, 8, 8, false, out, 4, 0, false);
        }
        // тело
        copy(skin, 20, 20, 8, 12, false, out, 4, 8, true);
        // правая рука (слева на картинке): вплотную к телу
        copy(skin, 44, 20, arm, 12, false, out, 4 - arm, 8, true);
        // правая нога
        copy(skin, 4, 20, 4, 12, false, out, 4, 20, true);
        if (legacy) {
            copy(skin, 44, 20, 4, 12, true, out, 12, 8, true);
            copy(skin, 4, 20, 4, 12, true, out, 8, 20, true);
        } else {
            copy(skin, 36, 52, arm, 12, false, out, 12, 8, true);
            copy(skin, 20, 52, 4, 12, false, out, 8, 20, true);
            // второй слой
            copy(skin, 20, 36, 8, 12, false, out, 4, 8, false);
            copy(skin, 44, 36, arm, 12, false, out, 4 - arm, 8, false);
            copy(skin, 52, 52, arm, 12, false, out, 12, 8, false);
            copy(skin, 4, 36, 4, 12, false, out, 4, 20, false);
            copy(skin, 4, 52, 4, 12, false, out, 8, 20, false);
        }
        return out;
    }

    /**
     * Кусок w × h из (sx, sy) скина — в (dx, dy) картинки. {@code base}: нижний слой, всегда непрозрачный (как в
     * игре); иначе — второй слой поверх с учётом прозрачности. {@code mirror}: отразить по горизонтали.
     */
    private static void copy(final BufferedImage skin, final int sx, final int sy, final int w, final int h,
                             final boolean mirror, final int[] out, final int dx, final int dy, final boolean base) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int src = skin.getRGB(sx + (mirror ? w - 1 - x : x), sy + y);
                final int i = (dy + y) * W + dx + x;
                if (base) {
                    out[i] = src | 0xFF000000;
                } else {
                    out[i] = over(src, out[i]);
                }
            }
        }
    }

    /** Пиксель {@code top} поверх {@code under} (ARGB). */
    static int over(final int top, final int under) {
        final int a = top >>> 24;
        if (a == 0) {
            return under;
        }
        if (a == 255 || under >>> 24 == 0) {
            return a == 255 ? top : 0;
        }
        final double t = a / 255.0;
        int rgb = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            final int c = (int) Math.round(((top >> shift) & 0xFF) * t + ((under >> shift) & 0xFF) * (1 - t));
            rgb |= c << shift;
        }
        return 0xFF000000 | rgb;
    }

    /** Старые скины часто заливают шляпу сплошным цветом: такую шляпу игра не рисует. */
    private static boolean opaqueEverywhere(final BufferedImage skin, final int sx, final int sy, final int w,
                                            final int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (skin.getRGB(sx + x, sy + y) >>> 24 < 255) {
                    return false;
                }
            }
        }
        return true;
    }
}
