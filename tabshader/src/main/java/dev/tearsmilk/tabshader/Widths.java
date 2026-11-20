package dev.tearsmilk.tabshader;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class Widths {

    public static final int DEFAULT = 6;
    public static final int UNKNOWN = 8;
    public static final Key DEFAULT_FONT = Key.key("minecraft", "default");

    private final Map<Integer, Integer> advance;

    private Widths(Map<Integer, Integer> advance) {
        this.advance = advance;
    }

    public static Widths load() {
        try (InputStream in = Widths.class.getResourceAsStream("/widths.txt")) {
            if (in == null) {
                throw new IllegalStateException("widths.txt is missing");
            }
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Widths parse(InputStream in) throws IOException {
        Map<Integer, Integer> map = new HashMap<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(" ");
            map.put(Integer.parseInt(f[0], 16), Integer.parseInt(f[1]));
        }
        return new Widths(map);
    }

    public int size() {
        return advance.size();
    }

    public int advance(int cp, boolean bold) {
        Integer a = advance.get(cp);
        int base;
        if (a != null) {
            base = a;
        } else if (cp < 0x2000 || (cp >= 0x2010 && cp < 0x2100)) {
            base = DEFAULT;
        } else {
            base = UNKNOWN;
        }
        return base + (bold ? 1 : 0);
    }

    public int width(String text, boolean bold) {
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\r') {
                continue;
            }
            w += advance(cp, bold);
        }
        return w;
    }

    public int width(Component c) {
        return width(c, false);
    }

    private int width(Component c, boolean parentBold) {
        Key font = c.style().font();
        if (font != null && !DEFAULT_FONT.equals(font)) {
            return -1;
        }
        if (!(c instanceof TextComponent t)) {
            return -1;
        }
        boolean bold = bold(c.style(), parentBold);
        int w = width(t.content(), bold);
        for (Component child : c.children()) {
            int cw = width(child, bold);
            if (cw < 0) {
                return -1;
            }
            w += cw;
        }
        return w;
    }

    private static boolean bold(Style s, boolean parent) {
        TextDecoration.State st = s.decoration(TextDecoration.BOLD);
        if (st == TextDecoration.State.TRUE) {
            return true;
        }
        if (st == TextDecoration.State.FALSE) {
            return false;
        }
        return parent;
    }
}
