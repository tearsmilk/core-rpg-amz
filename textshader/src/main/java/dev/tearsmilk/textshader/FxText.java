package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;

public final class FxText {

    public record Letter(String text, int code) {
    }

    private FxText() {
    }

    public static List<Letter> letters(String text, FxSpec spec) {
        List<Letter> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        if (!spec.effect().indexed()) {
            out.add(new Letter(text, spec.code()));
            return out;
        }
        int index = 0;
        int[] cps = text.codePoints().toArray();
        for (int cp : cps) {
            String ch = new String(Character.toChars(cp));
            if (cp == '\n') {
                out.add(new Letter(ch, spec.code(0)));
                index = 0;
                continue;
            }
            out.add(new Letter(ch, spec.code(index)));
            index++;
        }
        return out;
    }

    public static Component render(String text, FxSpec spec) {
        List<Letter> letters = letters(text, spec);
        if (letters.size() == 1) {
            Letter l = letters.get(0);
            return Component.text(l.text(), TextColor.color(l.code()));
        }
        TextComponent.Builder b = Component.text();
        for (Letter l : letters) {
            b.append(Component.text(l.text(), TextColor.color(l.code())));
        }
        return b.build();
    }

    public static String legacy(String text, FxSpec spec) {
        StringBuilder sb = new StringBuilder();
        for (Letter l : letters(text, spec)) {
            sb.append(FxCode.legacyHex(l.code())).append(l.text());
        }
        return sb.toString();
    }

    public static String miniMessage(String text, FxSpec spec) {
        StringBuilder sb = new StringBuilder();
        for (Letter l : letters(text, spec)) {
            sb.append('<').append(FxCode.hex(l.code())).append('>').append(escape(l.text()));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("<", "\\<");
    }
}
