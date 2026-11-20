package dev.tearsmilk.tabshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

public final class TabLayout {

    public static final int ROWS_PER_COLUMN = 20;
    public static final int CLIENT_CAP = 80;
    public static final int HEAD = 9;
    public static final int PING = 13;
    public static final int COLUMN_GAP = 5;
    public static final int ROW = 9;
    public static final String ELLIPSIS = "...";
    public static final String MARKER_GLYPHS = Backing.GLYPHS;

    private TabLayout() {
    }

    public static int columns(int entries) {
        int rows = entries;
        int cols = 1;
        while (rows > ROWS_PER_COLUMN) {
            cols++;
            rows = (entries + cols - 1) / cols;
        }
        return cols;
    }

    public static int rows(int entries) {
        int cols = columns(entries);
        return (entries + cols - 1) / cols;
    }

    public static int maxEntries(int maxColumns) {
        if (maxColumns <= 0) {
            return CLIENT_CAP;
        }
        return Math.min(CLIENT_CAP, maxColumns * ROWS_PER_COLUMN);
    }

    public static int listWidth(int entries, int maxNameWidth, boolean heads) {
        if (entries <= 0) {
            return 0;
        }
        int cols = columns(entries);
        int col = (heads ? HEAD : 0) + maxNameWidth + PING;
        return cols * col + (cols - 1) * COLUMN_GAP;
    }

    public static int defaultWidth(Widths widths, int nameChars, int columns) {
        int name = nameChars * widths.advance('W', false) + widths.width(ELLIPSIS, false);
        int cols = Math.max(1, columns);
        return cols * (HEAD + name + PING) + (cols - 1) * COLUMN_GAP;
    }

    public static int panelHeight(int headerLines, int rows, int footerLines) {
        int h = rows * ROW + 1;
        if (headerLines > 0) {
            h += headerLines * ROW + 1;
        }
        if (footerLines > 0) {
            h += footerLines * ROW + 1;
        }
        return h;
    }

    public static int visibleLength(Component c) {
        int n = c instanceof TextComponent t ? t.content().codePointCount(0, t.content().length()) : 0;
        for (Component child : c.children()) {
            n += visibleLength(child);
        }
        return n;
    }

    public static String plain(Component c) {
        StringBuilder sb = new StringBuilder();
        plain(c, sb);
        return sb.toString();
    }

    private static void plain(Component c, StringBuilder sb) {
        if (c instanceof TextComponent t) {
            sb.append(t.content());
        }
        for (Component child : c.children()) {
            plain(child, sb);
        }
    }

    public static Component truncate(Component c, int max) {
        if (c == null || max <= 0 || visibleLength(c) <= max) {
            return c;
        }
        Cut cut = new Cut(max);
        Component body = cut.walk(c, Style.empty());
        return body.append(Component.text(ELLIPSIS, cut.last));
    }

    private static final class Cut {
        int left;
        Style last = Style.empty();

        Cut(int left) {
            this.left = left;
        }

        Component walk(Component c, Style parent) {
            Style effective = c.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
            Component self = c;
            if (c instanceof TextComponent t) {
                String s = t.content();
                int n = s.codePointCount(0, s.length());
                if (n > 0) {
                    if (n > left) {
                        s = s.substring(0, s.offsetByCodePoints(0, left));
                        n = left;
                    }
                    left -= n;
                    if (n > 0) {
                        last = effective;
                    }
                }
                self = t.content(s);
            }
            List<Component> kids = new ArrayList<>();
            for (Component child : c.children()) {
                if (left <= 0) {
                    break;
                }
                kids.add(walk(child, effective));
            }
            return self.children(kids);
        }
    }

    public static boolean representable(int px, int space, int boldSpace) {
        if (px < 0) {
            return false;
        }
        for (int b = 0; b * boldSpace <= px; b++) {
            if ((px - b * boldSpace) % space == 0) {
                return true;
            }
        }
        return false;
    }

    public static int[] split(int extra, int space, int boldSpace) {
        for (int total = extra; total > 0; total--) {
            int half = total / 2;
            for (int d = 0; d <= half; d++) {
                int[] tries = {half - d, half + d};
                for (int l : tries) {
                    int r = total - l;
                    if (l >= 0 && r >= 0 && representable(l, space, boldSpace) && representable(r, space, boldSpace)) {
                        return new int[]{l, r};
                    }
                }
            }
        }
        return new int[]{0, 0};
    }

    public static Component spaces(int px, int space, int boldSpace) {
        for (int b = 0; b * boldSpace <= px; b++) {
            int rest = px - b * boldSpace;
            if (rest % space == 0) {
                List<Component> parts = new ArrayList<>(2);
                if (rest > 0) {
                    parts.add(Component.text(" ".repeat(rest / space), plainStyle(false)));
                }
                if (b > 0) {
                    parts.add(Component.text(" ".repeat(b), plainStyle(true)));
                }
                return Component.text().append(parts).build();
            }
        }
        return Component.empty();
    }

    private static Style plainStyle(boolean bold) {
        return Style.style()
                .decoration(TextDecoration.BOLD, bold)
                .decoration(TextDecoration.UNDERLINED, false)
                .decoration(TextDecoration.STRIKETHROUGH, false)
                .decoration(TextDecoration.OBFUSCATED, false)
                .decoration(TextDecoration.ITALIC, false)
                .build();
    }

    public record Padded(Component line, int width) {
    }

    public static Padded pad(Component line, int lineWidth, int target, Widths widths) {
        if (lineWidth < 0 || target <= lineWidth) {
            return new Padded(line, Math.max(lineWidth, 0));
        }
        int space = widths.advance(' ', false);
        int bold = widths.advance(' ', true);
        int[] lr = split(target - lineWidth, space, bold);
        Component out = Component.text()
                .append(spaces(lr[0], space, bold))
                .append(line)
                .append(spaces(lr[1], space, bold))
                .build();
        return new Padded(out, lineWidth + lr[0] + lr[1]);
    }

    public static Component marker(int rgb) {
        Style style = Style.style()
                .font(Backing.FONT)
                .color(TextColor.color(rgb))
                .shadowColor(ShadowColor.none())
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.UNDERLINED, false)
                .decoration(TextDecoration.STRIKETHROUGH, false)
                .decoration(TextDecoration.OBFUSCATED, false)
                .build();
        return Component.text(MARKER_GLYPHS, style);
    }

    public static final char PAD_FIRST = '\ue100';
    public static final int PAD_BITS = 23;

    public static Component pad(long px) {
        if (px <= 0) {
            return Component.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (int bit = 0; bit < PAD_BITS; bit++) {
            if ((px >> bit & 1) != 0) {
                sb.append((char) (PAD_FIRST + bit));
            }
        }
        if (px >> PAD_BITS != 0) {
            throw new IllegalArgumentException("pad too wide: " + px);
        }
        return Component.text(sb.toString(), Style.style().font(Backing.FONT).build());
    }

    public static long padWidth(String glyphs) {
        long w = 0;
        for (int i = 0; i < glyphs.length(); i++) {
            int bit = glyphs.charAt(i) - PAD_FIRST;
            if (bit >= 0 && bit < PAD_BITS) {
                w += 1L << bit;
            }
        }
        return w;
    }

    public static List<Component> tabHeader(List<Component> lines, int padLines) {
        List<Component> out = new ArrayList<>(padLines + lines.size());
        for (int i = 0; i < padLines; i++) {
            out.add(Component.empty());
        }
        out.addAll(lines);
        return out;
    }

    public static Component boardTitle(Component title, int titleWidth, int contentWidth, int code) {
        int content = Math.max(contentWidth, titleWidth);
        long total = PositionCode.boardTitleWidth(code, content);
        int before = Math.max(0, (content - titleWidth) / 2);
        long after = total - before - titleWidth;
        return Component.text()
                .append(marker(PositionCode.boardMarker(content)))
                .append(pad(before))
                .append(title)
                .append(pad(after))
                .build();
    }
}
