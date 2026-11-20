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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static final int[][] SCREENS = {{480, 270}, {640, 400}, {683, 384}, {960, 540}, {1920, 1080}, {3840, 2160}};

    private static double[] tabShift(int code, int sw, int sh) {
        int p = code & 3;
        double m = code >> 2;
        double c = code + PositionCode.TAB_BASE;
        double b = PositionCode.TAB_BAND;
        double half = 205 / 2;
        double mid = Math.floor(sw * 0.5);
        double dx = (p & 1) == 0 ? m + 2 - (mid - half - 1) : sw - m - 2 - (mid + half + 1);
        double dy = p < 2 ? m + 1 - (9 + 9 * (b * c + 2)) : sh - m - 2 - (12 + 9 * (b * c + b - 2));
        return new double[]{dx, dy};
    }

    @Test
    void tabBandPlacesPanelAtEveryCorner() {
        PositionCode.Position[] corners = {PositionCode.Position.TOP_LEFT, PositionCode.Position.TOP_RIGHT,
                PositionCode.Position.BOTTOM_LEFT, PositionCode.Position.BOTTOM_RIGHT};
        assertTrue(9 + 9 * PositionCode.TAB_BAND * PositionCode.TAB_BASE > 2160 + 20);
        for (PositionCode.Position pos : corners) {
            for (int margin = 0; margin <= 15; margin++) {
                int code = PositionCode.tabCode(pos, margin);
                assertTrue(code >= 0 && code < 64);
                for (int h0 = 1; h0 <= 3; h0++) {
                    for (int rows = 1; rows <= 20; rows++) {
                        for (int f = 1; f <= 3; f++) {
                            int lines = h0 + rows + f;
                            int pad = PositionCode.tabPadLines(pos, margin, lines);
                            int hT = pad + h0;
                            int top = 9 + 9 * pad;
                            int bottom = 12 + 9 * (pad + lines);
                            int[] ys = {top, 10 + 9 * pad, 10 + 9 * pad + 8 + 1, 10 + 9 * hT, 11 + 9 * hT + 9 * rows,
                                    12 + 9 * (hT + rows) + 9 * (f - 1) + 9, top - 1, bottom + 2};
                            for (int y : ys) {
                                assertEquals(code, PositionCode.tabCodeAt(y), pos + " m" + margin + " y" + y);
                            }
                            for (int[] scr : SCREENS) {
                                assertTrue(top - 1 > scr[1] + 16);
                                double[] d = tabShift(code, scr[0], scr[1]);
                                int mid = scr[0] / 2;
                                double left = mid - 205 / 2 - 1 - 2 + d[0];
                                double right = mid + 205 / 2 + 1 + 2 + d[0];
                                double gTop = top - 1 + d[1];
                                double gBottom = bottom + 2 + d[1];
                                if (pos.side() == PositionCode.Side.LEFT) {
                                    assertEquals(margin, left, 1e-9);
                                } else {
                                    assertEquals(scr[0] - margin, right, 1e-9);
                                }
                                if (pos.bottom()) {
                                    assertEquals(scr[1] - margin, gBottom, 1e-9);
                                } else {
                                    assertEquals(margin, gTop, 1e-9);
                                }
                                assertEquals(9 * lines + 3 + 3, gBottom - gTop, 1e-9);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(0, PositionCode.tabPadLines(PositionCode.Position.CENTER, 4, 5));
        assertEquals(0, PositionCode.tabPadLines(PositionCode.Position.TOP_LEFT, 4, PositionCode.TAB_MAX_LINES + 1));
        assertEquals(-1, PositionCode.tabCodeAt(500));
        assertEquals(PositionCode.tabCode(PositionCode.Position.TOP_LEFT, 99), PositionCode.tabCode(PositionCode.Position.TOP_LEFT, 15));
    }

    private static double[] boardShift(int code, int sw, int sh, int width) {
        int[] f = PositionCode.boardFields(code);
        double k = code + PositionCode.BOARD_BASE;
        double cell = PositionCode.BOARD_CELL;
        double n = f[1];
        double bottom = Math.floor(sh * 0.5) + 3 * n;
        double top = bottom - 9 * n;
        boolean right = PositionCode.boardFromRight(f[0]);
        double edge = right ? sw - 5 - cell * (k - 1) : sw - 3 - cell * k;
        double m = f[3];
        double side = f[2] > 0 ? f[2] - 1 + 2 * (205 / 2) + 6 : 0;
        double dx = right ? sw - m - side - (edge + 3) : m + side - (edge - 3);
        double dy = 0;
        if (f[0] == 1 || f[0] == 2) {
            dy = m - (top - 11);
        } else if (f[0] == 3) {
            dy = sh - m - 132 - (bottom + 1);
        } else if (f[0] == 4) {
            dy = sh - m - 60 - (bottom + 1);
        }
        double lo = right ? edge - width - 3 : edge - 3;
        return new double[]{lo + dx, top - 11 + dy, lo + width + 6 + dx, bottom + 1 + dy};
    }

    @Test
    void boardBandPlacesPanel() {
        PositionCode.Position[] all = {PositionCode.Position.LEFT, PositionCode.Position.TOP_LEFT,
                PositionCode.Position.TOP_RIGHT, PositionCode.Position.BOTTOM_LEFT, PositionCode.Position.BOTTOM_RIGHT,
                PositionCode.Position.RIGHT};
        assertTrue((long) PositionCode.BOARD_CELL * (PositionCode.BOARD_BASE + PositionCode.BOARD_CODES) < (1L << 23));
        assertTrue((long) PositionCode.BOARD_CELL * PositionCode.BOARD_BASE > 8192 + 3840 + 8);
        for (PositionCode.Position pos : all) {
            for (int n = 0; n <= 15; n += 3) {
                for (int tab : new int[]{-1, 0, 4, 15}) {
                    for (int margin : new int[]{0, 4, 15}) {
                        int code = PositionCode.boardCode(pos, n, tab, margin);
                        assertTrue(code >= 0 && code < PositionCode.BOARD_CODES);
                        int[] f = PositionCode.boardFields(code);
                        assertEquals(pos.boardSlot(), f[0]);
                        assertEquals(n, f[1]);
                        assertEquals(tab < 0 ? 0 : tab + 1, f[2]);
                        assertEquals(margin, f[3]);
                        for (int width : new int[]{1, 40, 120, PositionCode.BOARD_MAX_WIDTH}) {
                            long total = PositionCode.boardTitleWidth(code, width);
                            for (int[] scr : SCREENS) {
                                long x0 = scr[0] - total - 3;
                                for (long x = x0; x <= x0 + width + 1; x++) {
                                    assertEquals(code, PositionCode.boardCodeAt(x, scr[0]), pos + " x" + (x - x0));
                                }
                                assertTrue(x0 - 2 < -8192);
                                double[] r = boardShift(code, scr[0], scr[1], width);
                                double side = tab < 0 ? 0 : tab + 2 * 102 + 6;
                                assertEquals(width + 6, r[2] - r[0], 1e-9);
                                assertEquals(9 * n + 12, r[3] - r[1], 1e-9);
                                if (pos.side() == PositionCode.Side.LEFT) {
                                    assertEquals(margin + side, r[0], 1e-9);
                                } else {
                                    assertEquals(scr[0] - margin - side, r[2], 1e-9);
                                }
                                double vanillaBottom = scr[1] / 2 + 3 * n + 1;
                                switch (pos) {
                                    case TOP_LEFT, TOP_RIGHT -> assertEquals(margin, r[1], 1e-9);
                                    case BOTTOM_LEFT -> assertEquals(scr[1] - margin - 132, r[3], 1e-9);
                                    case BOTTOM_RIGHT -> assertEquals(scr[1] - margin - 60, r[3], 1e-9);
                                    default -> assertEquals(vanillaBottom, r[3], 1e-9);
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(-1, PositionCode.boardCode(PositionCode.Position.CENTER, 3, -1, 4));
        assertEquals(-1, PositionCode.boardCodeAt(100, 640));
        assertTrue(PositionCode.sameSide(PositionCode.Position.TOP_LEFT, PositionCode.Position.LEFT));
        assertTrue(PositionCode.sameSide(PositionCode.Position.BOTTOM_RIGHT, PositionCode.Position.RIGHT));
        assertFalse(PositionCode.sameSide(PositionCode.Position.CENTER, PositionCode.Position.RIGHT));
        assertFalse(PositionCode.sameSide(PositionCode.Position.TOP_LEFT, PositionCode.Position.TOP_RIGHT));
    }

    @Test
    void markerCodes() {
        for (int h = 0; h <= 15; h++) {
            int rgb = PositionCode.tabMarker(h);
            assertEquals(PositionCode.TAB_MARKER_R, PositionCode.red(rgb));
            assertEquals(h, PositionCode.markerValue(rgb));
        }
        assertEquals(15, PositionCode.markerValue(PositionCode.tabMarker(40)));
        for (int w = 0; w <= PositionCode.MAX_VALUE; w++) {
            int rgb = PositionCode.boardMarker(w);
            assertEquals(PositionCode.BOARD_MARKER_R, PositionCode.red(rgb));
            assertEquals(w, PositionCode.markerValue(rgb));
        }
        assertEquals(0xE80200, PositionCode.tabMarker(2));
        assertEquals(0xE90078, PositionCode.boardMarker(120));
        assertTrue(PositionCode.red(Backing.code(Backing.Kind.TITLE, 100)) != PositionCode.TAB_MARKER_R);
    }

    @Test
    void padGlyphsAreExact() {
        for (long px : new long[]{1, 2, 3, 7, 100, 255, 4096, 123457, 6_700_000, (1L << 23) - 1}) {
            Component c = TabLayout.pad(px);
            assertEquals(Backing.FONT, c.style().font());
            assertEquals(px, TabLayout.padWidth(((TextComponent) c).content()));
        }
        assertEquals(Component.empty(), TabLayout.pad(0));
        assertThrows(IllegalArgumentException.class, () -> TabLayout.pad(1L << 23));
    }

    @Test
    void boardTitleHasExactWidth() {
        Component title = MiniMessage.miniMessage().deserialize("<b>Amazake</b>");
        int tw = W.width(title);
        for (PositionCode.Position pos : new PositionCode.Position[]{PositionCode.Position.LEFT,
                PositionCode.Position.TOP_RIGHT, PositionCode.Position.BOTTOM_LEFT}) {
            int code = PositionCode.boardCode(pos, 6, -1, 4);
            Component t = TabLayout.boardTitle(title, tw, 90, code);
            long w = 0;
            for (Component c : t.children()) {
                if (Backing.FONT.equals(c.style().font())) {
                    String s = ((TextComponent) c).content();
                    w += s.equals(TabLayout.MARKER_GLYPHS) ? 0 : TabLayout.padWidth(s);
                } else {
                    w += W.width(c);
                }
            }
            assertEquals(PositionCode.boardTitleWidth(code, 90), w);
            assertEquals(PositionCode.boardMarker(90), t.children().getFirst().color().value());
            assertEquals((90 - tw) / 2, TabLayout.padWidth(((TextComponent) t.children().get(1)).content()));
        }
    }

    @Test
    void shaderConstantsMatch() throws Exception {
        String glsl = java.nio.file.Files.readString(java.nio.file.Path.of("pack/assets/tabshader/shaders/include/th_layout.glsl"));
        assertTrue(glsl.contains("#define TH_TAB_WIDTH 205\n"));
        assertTrue(glsl.contains("#define TH_TAB_BAND " + PositionCode.TAB_BAND + "\n"));
        assertTrue(glsl.contains("#define TH_TAB_BASE " + PositionCode.TAB_BASE + "\n"));
        assertTrue(glsl.contains("#define TH_BOARD_CELL " + PositionCode.BOARD_CELL + "\n"));
        assertTrue(glsl.contains("#define TH_BOARD_BASE " + PositionCode.BOARD_BASE + "\n"));
        assertTrue(glsl.contains("#define TH_BOARD_CODES " + PositionCode.BOARD_CODES + "\n"));
        assertEquals(205, Settings.width("205"));
        assertEquals(205, TabLayout.defaultWidth(W, 12, 2));
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
