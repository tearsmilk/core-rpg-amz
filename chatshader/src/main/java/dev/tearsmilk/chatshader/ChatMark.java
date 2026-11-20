package dev.tearsmilk.chatshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;

public final class ChatMark {

    public static final int[] RESIDUE = {11, 12, 13};
    public static final int STEP = 16;
    public static final int WHITE = 0xFFFFFF;
    public static final int TEXTSHADER_R = 214;
    public static final int TEXTSHADER_SHADOW_R = 53;
    public static final int FX_TEXT_R = 213;
    public static final int FX_SHADOW_R = 52;

    private ChatMark() {
    }

    static int channel(int value, int residue) {
        int base = (value & ~(STEP - 1)) | residue;
        int best = -1;
        for (int c = base - STEP; c <= base + STEP; c += STEP) {
            if (c < 0 || c > 255) {
                continue;
            }
            if (best < 0 || Math.abs(c - value) < Math.abs(best - value)) {
                best = c;
            }
        }
        return best;
    }

    public static int mark(int rgb) {
        int r = channel((rgb >> 16) & 255, RESIDUE[0]);
        int g = channel((rgb >> 8) & 255, RESIDUE[1]);
        int b = channel(rgb & 255, RESIDUE[2]);
        return (r << 16) | (g << 8) | b;
    }

    public static boolean marked(int rgb) {
        return (((rgb >> 16) & (STEP - 1)) == RESIDUE[0]) && (((rgb >> 8) & (STEP - 1)) == RESIDUE[1])
                && ((rgb & (STEP - 1)) == RESIDUE[2]);
    }

    public static boolean textshader(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        return r == TEXTSHADER_R && (g < 208 || (g >= 224 && g < 240));
    }

    static boolean textshaderShadow(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        return r == TEXTSHADER_SHADOW_R && g < 64 && (g < 52 || g > 55);
    }

    public static boolean fxText(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        return r == FX_TEXT_R && (g < 208 || (g >= 224 && g < 240));
    }

    public static boolean fxShadow(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        return r == FX_SHADOW_R && g < 64 && (g < 52 || g > 55);
    }

    public static int shadowOf(int rgb) {
        int r = (int) (((rgb >> 16) & 255) * 0.25f);
        int g = (int) (((rgb >> 8) & 255) * 0.25f);
        int b = (int) ((rgb & 255) * 0.25f);
        return (r << 16) | (g << 8) | b;
    }

    static int markShadow(int argb) {
        int rgb = argb & 0xFFFFFF;
        int out = textshaderShadow(rgb) ? (FX_SHADOW_R << 16) | (rgb & 0xFFFF) : mark(rgb);
        return (argb & 0xFF000000) | out;
    }

    static int[] pair(Integer color, Integer shadow) {
        int rgb = color == null ? WHITE : color;
        int argb = shadow == null ? 0xFF000000 | shadowOf(rgb) : shadow;
        if (textshader(rgb)) {
            return new int[]{(FX_TEXT_R << 16) | (rgb & 0xFFFF), markShadow(argb)};
        }
        return new int[]{mark(rgb), markShadow(argb)};
    }

    public static Component apply(Component message) {
        return node(message, true);
    }

    private static Component node(Component c, boolean root) {
        Style s = c.style();
        Style.Builder out = s.toBuilder();
        TextColor color = s.color();
        ShadowColor shadow = s.shadowColor();
        if (color != null || root) {
            int[] p = pair(color == null ? null : color.value(), shadow == null ? null : shadow.value());
            out.color(TextColor.color(p[0]));
            out.shadowColor(ShadowColor.shadowColor(p[1]));
        } else if (shadow != null) {
            out.shadowColor(ShadowColor.shadowColor(markShadow(shadow.value())));
        }
        Component result = c.style(out.build());
        if (result instanceof TranslatableComponent t && !t.arguments().isEmpty()) {
            List<ComponentLike> args = new ArrayList<>();
            for (TranslationArgument a : t.arguments()) {
                args.add(a.value() instanceof Component v ? TranslationArgument.component(node(v, false)) : a);
            }
            result = t.arguments(args);
        }
        List<Component> kids = result.children();
        if (!kids.isEmpty()) {
            List<Component> mapped = new ArrayList<>(kids.size());
            for (Component k : kids) {
                mapped.add(node(k, false));
            }
            result = result.children(mapped);
        }
        return result;
    }
}
