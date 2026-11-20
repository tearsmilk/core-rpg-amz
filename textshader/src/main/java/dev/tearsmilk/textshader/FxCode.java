package dev.tearsmilk.textshader;

import java.util.Locale;

public final class FxCode {

    public static final int MARK = 214;
    public static final int SHADOW_MARK = 53;

    public record Decoded(Effect effect, Palette palette, int letter, boolean shadow) {
    }

    private FxCode() {
    }

    public static int encode(Effect effect, Palette palette) {
        return encode(effect, palette, 0);
    }

    public static int encode(Effect effect, Palette palette, int letter) {
        Palette p = palette == null ? Palette.WHITE : palette;
        int i = Math.max(0, Math.min(Effect.MAX_LETTER, letter));
        int g = (effect.id() << 2) | ((i >> 2) & 3);
        int b = (((i >> 4) & 3) << 6) | (p.ordinal() << 2) | (i & 3);
        return (MARK << 16) | (g << 8) | b;
    }

    public static int shadowOf(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        return ((r / 4) << 16) | ((g / 4) << 8) | (b / 4);
    }

    public static Decoded decode(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        if (r == MARK) {
            Effect e = Effect.byId(g >> 2);
            Palette p = Palette.byId((b >> 2) & 15);
            if (e == null || p == null) {
                return null;
            }
            int letter = ((b >> 6) << 4) | ((g & 3) << 2) | (b & 3);
            return new Decoded(e, p, letter, false);
        }
        if (r == SHADOW_MARK) {
            Effect e = Effect.byId(g);
            Palette p = Palette.byId(b & 15);
            if (e == null || p == null) {
                return null;
            }
            return new Decoded(e, p, -1, true);
        }
        return null;
    }

    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    public static String legacyHex(int rgb) {
        return "&" + hex(rgb);
    }
}
