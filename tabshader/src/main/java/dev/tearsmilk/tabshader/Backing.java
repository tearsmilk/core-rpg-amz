package dev.tearsmilk.tabshader;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class Backing {

    public static final Key FONT = Key.key("tabshader", "hud");
    public static final String GLYPHS = "";
    public static final int MARK_R = 231;
    public static final int MAX_WIDTH = 4095;

    public enum Kind {
        ACTIONBAR(1),
        TITLE(2),
        SUBTITLE(3);

        private final int id;

        Kind(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    private Backing() {
    }

    public static int code(Kind kind, int width) {
        int w = Math.max(0, Math.min(MAX_WIDTH, width));
        return (MARK_R << 16) | (((kind.id() << 4) | (w >> 8)) << 8) | (w & 255);
    }

    public static Kind kindOf(int rgb) {
        if (((rgb >> 16) & 255) != MARK_R) {
            return null;
        }
        int k = ((rgb >> 8) & 255) >> 4;
        for (Kind kind : Kind.values()) {
            if (kind.id() == k) {
                return kind;
            }
        }
        return null;
    }

    public static int widthOf(int rgb) {
        return (((rgb >> 8) & 15) << 8) | (rgb & 255);
    }

    public static Component marker(Kind kind, int width) {
        Style style = Style.style()
                .font(FONT)
                .color(TextColor.color(code(kind, width)))
                .shadowColor(ShadowColor.none())
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.UNDERLINED, false)
                .decoration(TextDecoration.STRIKETHROUGH, false)
                .decoration(TextDecoration.OBFUSCATED, false)
                .build();
        return Component.text(GLYPHS, style);
    }

    public static Component wrap(Component text, Kind kind, Widths widths) {
        if (text == null || Component.empty().equals(text)) {
            return text;
        }
        int w = widths.width(text);
        if (w <= 0) {
            return text;
        }
        return Component.text().append(marker(kind, w)).append(text).build();
    }
}
