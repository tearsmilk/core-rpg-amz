package core.amazake.systems.coremechanics.tooltip;

import net.kyori.adventure.text.format.TextColor;

/**
 * Перевод цветов текста подсказки под светлый пергамент: тот же оттенок, но «чернилами» — тёмный и насыщенный.
 * Белый/светло-серый (обычный текст) → тёмно-коричневые чернила, серый → выцветшие чернила, цветной → тот же тон,
 * светлота ≤ 0.30. Чистая функция — проверяется тестом.
 */
public final class InkPalette {

    public static final TextColor INK = TextColor.color(0x3B2214);
    public static final TextColor FADED = TextColor.color(0x6E5038);
    public static final TextColor NOTE = TextColor.color(0x7A5A3C);

    private InkPalette() {
    }

    public static TextColor ink(final TextColor c) {
        if (c == null) {
            return INK;
        }
        final float r = c.red() / 255f, g = c.green() / 255f, b = c.blue() / 255f;
        final float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        final float l = (max + min) / 2f;
        final float d = max - min;
        final float s = d < 1e-4f ? 0f : d / (1f - Math.abs(2f * l - 1f));
        if (s < 0.25f) {
            return l > 0.55f ? INK : FADED;
        }
        float h;
        if (max == r) {
            h = ((g - b) / d) % 6f;
        } else if (max == g) {
            h = (b - r) / d + 2f;
        } else {
            h = (r - g) / d + 4f;
        }
        h = (h * 60f + 360f) % 360f;
        if (h >= 30f && h <= 65f) {
            return hsl(22f, 0.85f, 0.36f);   // золото/оранжевый/жёлтый → рыжие чернила (тёмно-жёлтый на пергаменте — «болото»)
        }
        return hsl(h, Math.min(0.75f, Math.max(0.55f, s)), Math.min(l, 0.30f));
    }

    static TextColor hsl(final float h, final float s, final float l) {
        final float c = (1f - Math.abs(2f * l - 1f)) * s;
        final float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        final float m = l - c / 2f;
        float r = 0, g = 0, b = 0;
        if (h < 60) { r = c; g = x; } else if (h < 120) { r = x; g = c; } else if (h < 180) { g = c; b = x; }
        else if (h < 240) { g = x; b = c; } else if (h < 300) { r = x; b = c; } else { r = c; b = x; }
        return TextColor.color(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }
}
