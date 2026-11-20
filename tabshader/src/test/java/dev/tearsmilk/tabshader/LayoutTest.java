package dev.tearsmilk.tabshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutTest {

    private static final Widths W = Widths.load();

    private static Component letters(String s) {
        TextComponent.Builder b = Component.text();
        for (int i = 0; i < s.length(); i++) {
            b.append(Component.text(String.valueOf(s.charAt(i)), TextColor.color(214, i, 4 * i)));
        }
        return b.build();
    }

    private static List<TextColor> colours(Component c, TextColor parent, List<TextColor> out) {
        TextColor col = c.color() != null ? c.color() : parent;
        if (c instanceof TextComponent t) {
            for (int i = 0; i < t.content().length(); i++) {
                out.add(col);
            }
        }
        for (Component child : c.children()) {
            colours(child, col, out);
        }
        return out;
    }

    @Test
    void truncatesVisibleText() {
        Component plain = Component.text("grgwgrgwgrgwgrgw");
        Component cut = TabLayout.truncate(plain, 12);
        assertEquals("grgwgrgwgrgw...", TabLayout.plain(cut));
        Component twelve = Component.text("grgwgrgwgrgw");
        assertSame(twelve, TabLayout.truncate(twelve, 12));
        Component exact = Component.text("abcdefghijkl");
        assertSame(exact, TabLayout.truncate(exact, 12));
        assertSame(exact, TabLayout.truncate(exact, 0));
        assertNull(TabLayout.truncate(null, 12));
    }

    @Test
    void truncationKeepsPerLetterColours() {
        Component name = letters("LongNicknameHere");
        Component cut = TabLayout.truncate(name, 12);
        assertEquals("LongNickname...", TabLayout.plain(cut));
        List<TextColor> cols = colours(cut, null, new ArrayList<>());
        assertEquals(15, cols.size());
        for (int i = 0; i < 12; i++) {
            assertEquals(TextColor.color(214, i, 4 * i), cols.get(i));
        }
        for (int i = 12; i < 15; i++) {
            assertEquals(TextColor.color(214, 11, 44), cols.get(i));
        }
        assertEquals(cut, TabLayout.truncate(cut, 12));
    }

    @Test
    void truncationKeepsNestedStyles() {
        Component name = MiniMessage.miniMessage().deserialize("<red><b>Abc</b><gradient:#ff0000:#0000ff>defghijklmnop</gradient>");
        Component cut = TabLayout.truncate(name, 12);
        assertEquals("Abcdefghijkl...", TabLayout.plain(cut));
        assertEquals(cut, TabLayout.truncate(cut, 12));
        Component bold = Component.text("x", Style.style(NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text("yyyyyyyyyyyyyyyy"));
        Component cutBold = TabLayout.truncate(bold, 3);
        assertEquals("xyy...", TabLayout.plain(cutBold));
        Component dots = cutBold.children().getLast();
        assertEquals(TextDecoration.State.TRUE, dots.decoration(TextDecoration.BOLD));
        assertEquals(NamedTextColor.RED, dots.color());
        Component surrogate = Component.text("😀".repeat(14));
        assertEquals(15, TabLayout.visibleLength(TabLayout.truncate(surrogate, 12)));
    }

    @Test
    void vanillaColumns() {
        assertEquals(1, TabLayout.columns(0));
        assertEquals(1, TabLayout.columns(20));
        assertEquals(2, TabLayout.columns(21));
        assertEquals(2, TabLayout.columns(40));
        assertEquals(3, TabLayout.columns(41));
        assertEquals(3, TabLayout.columns(60));
        assertEquals(4, TabLayout.columns(61));
        assertEquals(4, TabLayout.columns(80));
        assertEquals(20, TabLayout.rows(40));
        assertEquals(14, TabLayout.rows(41));
        assertEquals(11, TabLayout.rows(21));
        for (int cols = 1; cols <= 4; cols++) {
            int max = TabLayout.maxEntries(cols);
            assertEquals(20 * cols, max);
            assertTrue(TabLayout.columns(max) <= cols);
            if (max < TabLayout.CLIENT_CAP) {
                assertEquals(cols + 1, TabLayout.columns(max + 1));
            }
        }
        assertEquals(TabLayout.CLIENT_CAP, TabLayout.maxEntries(0));
        assertEquals(TabLayout.CLIENT_CAP, TabLayout.maxEntries(9));
    }

    @Test
    void widthAndHeight() {
        assertEquals(205, TabLayout.defaultWidth(W, 12, 2));
        assertEquals(100, TabLayout.defaultWidth(W, 12, 1));
        assertEquals(2 * (9 + 50 + 13) + 5, TabLayout.listWidth(30, 50, true));
        assertEquals(50 + 13, TabLayout.listWidth(3, 50, false));
        assertEquals(0, TabLayout.listWidth(0, 50, true));
        assertEquals(3 + 9 * (2 + 20 + 1), TabLayout.panelHeight(2, 20, 1));
        assertEquals(9 * 5 + 1, TabLayout.panelHeight(0, 5, 0));
        assertEquals(2 + 9 * (5 + 1), TabLayout.panelHeight(0, 5, 1));
    }

    private static UUID id(int i) {
        return new UUID(0, i);
    }

    @Test
    void columnLimitSelection() {
        List<TabLimiter.Rank> ranks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ranks.add(new TabLimiter.Rank(id(i), String.format("p%02d", i), 0));
        }
        ranks.add(new TabLimiter.Rank(id(100), "zzz_vip", 10));
        ranks.add(new TabLimiter.Rank(id(101), "Admin", 50));
        ranks.add(new TabLimiter.Rank(id(102), "aaa", 10));
        List<TabLimiter.Rank> ordered = TabLimiter.order(ranks);
        assertEquals("Admin", ordered.get(0).name());
        assertEquals("aaa", ordered.get(1).name());
        assertEquals("zzz_vip", ordered.get(2).name());
        assertEquals("p00", ordered.get(3).name());

        UUID viewer = id(59);
        TabLimiter.Split s = TabLimiter.split(viewer, ordered, u -> true, 40);
        assertEquals(39, s.kept().size());
        assertEquals(63 - 40, s.hidden().size());
        assertFalse(s.kept().contains(viewer));
        assertFalse(s.hidden().contains(viewer));
        assertEquals(id(101), s.kept().get(0));
        assertEquals(id(102), s.kept().get(1));
        assertEquals(id(100), s.kept().get(2));
        assertEquals(id(35), s.kept().get(38));
        assertTrue(s.hidden().contains(id(36)));
        assertEquals(40, 1 + s.kept().size());
        assertTrue(TabLayout.columns(1 + s.kept().size()) <= 2);

        Set<UUID> vanished = new HashSet<>(List.of(id(101), id(0)));
        TabLimiter.Split v = TabLimiter.split(viewer, ordered, u -> !vanished.contains(u), 40);
        assertFalse(v.kept().contains(id(101)));
        assertFalse(v.hidden().contains(id(101)));
        assertEquals(39, v.kept().size());
        assertEquals(63 - 40 - 2, v.hidden().size());

        TabLimiter.Split all = TabLimiter.split(viewer, ordered, u -> true, Integer.MAX_VALUE);
        assertEquals(62, all.kept().size());
        assertEquals(0, all.hidden().size());
    }

    @Test
    void priorityPermissions() {
        assertEquals(0, TabLimiter.priority(List.of()));
        assertEquals(7, TabLimiter.priority(List.of("tabshader.tab.priority.3", "tabshader.tab.priority.7", "other.9")));
        assertEquals(5, TabLimiter.priority(List.of("TabShader.Tab.Priority.5", "tabshader.tab.priority.x", "tabshader.tab.priority.")));
    }

    @Test
    void positionCodeRoundTrip() {
        for (PositionCode.Element e : PositionCode.Element.values()) {
            for (PositionCode.Position p : PositionCode.Position.values()) {
                for (int m : new int[]{0, 1, 4, 31, 200, 255}) {
                    int rgb = PositionCode.place(e, p, m);
                    assertEquals(PositionCode.PLACE_R, PositionCode.red(rgb));
                    assertEquals(e, PositionCode.element(rgb));
                    assertEquals(p, PositionCode.position(rgb));
                    assertEquals(m, PositionCode.margin(rgb));
                    assertTrue(PositionCode.isCode(rgb));
                    assertTrue(rgb >= 0 && rgb <= 0xFFFFFF);
                }
            }
            for (int v = 0; v <= PositionCode.MAX_VALUE; v++) {
                int w = PositionCode.width(e, v);
                int h = PositionCode.height(e, v);
                assertEquals(PositionCode.WIDTH_R, PositionCode.red(w));
                assertEquals(PositionCode.HEIGHT_R, PositionCode.red(h));
                assertEquals(e, PositionCode.element(w));
                assertEquals(e, PositionCode.element(h));
                assertEquals(v, PositionCode.value(w));
                assertEquals(v, PositionCode.value(h));
                assertNull(PositionCode.position(w));
            }
        }
        assertEquals(PositionCode.MAX_VALUE, PositionCode.value(PositionCode.width(PositionCode.Element.TAB, 99999)));
        assertEquals(255, PositionCode.margin(PositionCode.place(PositionCode.Element.TAB, PositionCode.Position.CENTER, 999)));
        assertEquals(0xE80000, PositionCode.place(PositionCode.Element.TAB, PositionCode.Position.CENTER, 0));
        assertEquals(0xE81604, PositionCode.place(PositionCode.Element.BOARD, PositionCode.Position.RIGHT, 4));
        assertEquals(0xE900CD, PositionCode.width(PositionCode.Element.TAB, 205));
        assertFalse(PositionCode.isCode(Backing.code(Backing.Kind.TITLE, 100)));
        assertFalse(PositionCode.isCode(0xE8F000));
        assertNull(PositionCode.element(0xE82000));
    }

    @Test
    void positionParsing() {
        PositionCode.Element tab = PositionCode.Element.TAB;
        PositionCode.Element board = PositionCode.Element.BOARD;
        assertEquals(PositionCode.Position.TOP_LEFT, PositionCode.parse("top-left", tab, PositionCode.Position.CENTER));
        assertEquals(PositionCode.Position.BOTTOM_RIGHT, PositionCode.parse("BOTTOM_RIGHT", tab, PositionCode.Position.CENTER));
        assertEquals(PositionCode.Position.CENTER, PositionCode.parse("left", tab, PositionCode.Position.CENTER));
        assertEquals(PositionCode.Position.LEFT, PositionCode.parse("left", board, PositionCode.Position.RIGHT));
        assertEquals(PositionCode.Position.RIGHT, PositionCode.parse("center", board, PositionCode.Position.RIGHT));
        assertEquals(PositionCode.Position.RIGHT, PositionCode.parse(null, board, PositionCode.Position.RIGHT));
        assertEquals(Settings.AUTO_WIDTH, Settings.width("auto"));
        assertEquals(Settings.AUTO_WIDTH, Settings.width("x"));
        assertEquals(240, Settings.width("240"));
        assertEquals(0, Settings.width("-5"));
    }

    @Test
    void markersHaveZeroWidthAndCarryCodes() {
        Component m = TabLayout.markers(PositionCode.Element.TAB, PositionCode.Position.TOP_RIGHT, 6, 205, 120);
        List<Integer> codes = new ArrayList<>();
        for (Component c : m.children()) {
            assertEquals(Backing.FONT, c.style().font());
            assertEquals(TabLayout.MARKER_GLYPHS, ((TextComponent) c).content());
            assertEquals(0, c.style().shadowColor().alpha());
            codes.add(c.color().value());
        }
        assertEquals(List.of(PositionCode.place(PositionCode.Element.TAB, PositionCode.Position.TOP_RIGHT, 6),
                PositionCode.width(PositionCode.Element.TAB, 205), PositionCode.height(PositionCode.Element.TAB, 120)), codes);
    }

    @Test
    void headerPaddingWidth() {
        int space = W.advance(' ', false);
        int bold = W.advance(' ', true);
        assertEquals(4, space);
        assertEquals(5, bold);
        for (int target = 0; target <= 400; target += 7) {
            for (String text : new String[]{"", "Amazake", "Онлайн: 12   Пинг: 40 мс", "iiiiiiiii"}) {
                Component line = MiniMessage.miniMessage().deserialize("<b>" + text + "</b>");
                int w = W.width(line);
                TabLayout.Padded p = TabLayout.pad(line, w, target, W);
                assertEquals(p.width(), W.width(p.line()), text + " " + target);
                if (target <= w) {
                    assertSame(line, p.line());
                    assertEquals(w, p.width());
                } else if (target - w >= 24) {
                    assertEquals(target, p.width(), text + " " + target);
                    Component left = p.line().children().getFirst();
                    Component right = p.line().children().getLast();
                    assertTrue(Math.abs(W.width(left) - W.width(right)) <= 4, text + " " + target);
                } else {
                    assertTrue(p.width() <= target && p.width() >= target - 3, text + " " + target);
                }
            }
        }
        for (int n = 0; n <= 200; n++) {
            int[] lr = TabLayout.split(n, space, bold);
            assertTrue(lr[0] + lr[1] <= n);
            if (n >= 24) {
                assertEquals(n, lr[0] + lr[1]);
            }
            assertEquals(lr[0], W.width(TabLayout.spaces(lr[0], space, bold)));
            assertEquals(lr[1], W.width(TabLayout.spaces(lr[1], space, bold)));
        }
    }
}
