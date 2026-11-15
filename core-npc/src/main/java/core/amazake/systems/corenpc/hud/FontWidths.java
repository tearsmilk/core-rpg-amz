package core.amazake.systems.corenpc.hud;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;

/** Ширины глифов шрифтов corenpc:row* (font-widths.txt из tools/gen_pack.py): сколько пикселей GUI займёт текст. */
public final class FontWidths {

    /** Ширина незнакомого символа (до {@link #sanitize} — для оценки ширины строк при загрузке диалогов). */
    public static final int UNKNOWN = 6;
    /** Чем заменить символ, которого нет в шрифтах строк. */
    public static final char REPLACEMENT = '?';

    private final Map<Integer, Integer> widths;

    public FontWidths(final Map<Integer, Integer> widths) {
        this.widths = Map.copyOf(widths);
    }

    public static FontWidths load(final InputStream in) throws IOException {
        final Map<Integer, Integer> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final String[] p = line.split("\\s+");
                if (p.length == 2) {
                    m.put(Integer.parseInt(p[0], 16), Integer.parseInt(p[1]));
                }
            }
        }
        return new FontWidths(m);
    }

    public int size() {
        return this.widths.size();
    }

    public boolean known(final int cp) {
        return this.widths.containsKey(cp);
    }

    public int width(final int cp, final boolean bold) {
        final Integer w = this.widths.get(cp);
        return (w == null ? UNKNOWN : w) + (bold ? 1 : 0);
    }

    public int width(final String plain, final boolean bold) {
        int sum = 0;
        for (int i = 0; i < plain.length(); ) {
            final int cp = plain.codePointAt(i);
            sum += this.width(cp, bold);
            i += Character.charCount(cp);
        }
        return sum;
    }

    /**
     * У шрифтов строк нет запасного шрифта: символ вне сгенерированного набора клиент нарисует как «нет глифа». Такие
     * символы (кроме пробелов) заменяются на «?».
     */
    public String sanitize(final String plain) {
        StringBuilder sb = null;
        for (int i = 0; i < plain.length(); ) {
            final int cp = plain.codePointAt(i);
            final int n = Character.charCount(cp);
            if (!this.widths.containsKey(cp)) {
                if (sb == null) {
                    sb = new StringBuilder(plain.length()).append(plain, 0, i);
                }
                sb.append(REPLACEMENT);
            } else if (sb != null) {
                sb.appendCodePoint(cp);
            }
            i += n;
        }
        return sb == null ? plain : sb.toString();
    }

    /** {@link #sanitize(String)} для текста компонента и всех его детей. */
    public Component sanitize(final Component c) {
        Component out = c;
        if (c instanceof TextComponent t) {
            final String fixed = this.sanitize(t.content());
            if (!fixed.equals(t.content())) {
                out = t.content(fixed);
            }
        }
        if (!c.children().isEmpty()) {
            final java.util.List<Component> kids = new java.util.ArrayList<>(c.children().size());
            boolean changed = false;
            for (final Component child : c.children()) {
                final Component f = this.sanitize(child);
                changed |= f != child;
                kids.add(f);
            }
            if (changed) {
                out = out.children(kids);
            }
        }
        return out;
    }

    /** Ширина компонента: текст детей с учётом жирного (+1 на символ). Не-текстовые компоненты — 0. */
    public int width(final Component c) {
        return this.width(c, false);
    }

    private int width(final Component c, final boolean parentBold) {
        final TextDecoration.State st = c.style().decoration(TextDecoration.BOLD);
        final boolean bold = st == TextDecoration.State.NOT_SET ? parentBold : st == TextDecoration.State.TRUE;
        int sum = 0;
        if (c instanceof TextComponent t) {
            sum += this.width(t.content(), bold);
        }
        for (final Component child : c.children()) {
            sum += this.width(child, bold);
        }
        return sum;
    }
}
