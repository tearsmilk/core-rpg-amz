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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void centerTabClearsBossbars() {
        assertEquals(-1, PositionCode.centerCode(0));
        assertEquals(0, PositionCode.tabPadLines(PositionCode.Position.CENTER, 4, 5, 0));
        int b = PositionCode.TAB_BAND;
        for (int bars = 1; bars <= PositionCode.MAX_BOSSBAR_ROWS; bars++) {
            int code = PositionCode.centerCode(bars);
            assertTrue(code >= PositionCode.TAB_CENTER && code < PositionCode.TAB_CODES);
            for (int lines = 3; lines <= PositionCode.TAB_MAX_LINES; lines++) {
                int pad = PositionCode.tabPadLines(PositionCode.Position.CENTER, 4, lines, bars);
                assertEquals(b * (code + PositionCode.TAB_BASE) + 2, pad);
                int top = 9 + 9 * pad;
                int bottom = 12 + 9 * (pad + lines);
                assertEquals(code, PositionCode.tabCodeAt(top - 1));
                assertEquals(code, PositionCode.tabCodeAt(bottom + 2));
                for (int[] scr : SCREENS) {
                    double[] d = tabShift(code, scr[0], scr[1]);
                    assertEquals(0, d[0], 1e-9);
                    double panelTop = top - 1 + d[1];
                    assertEquals(19 * bars + 1, panelTop, 1e-9);
                    assertTrue(panelTop > 12 + 19 * (bars - 1) + 5);
                }
            }
        }
        assertTrue(9 + 9 * b * (PositionCode.TAB_BANDS + PositionCode.TAB_BASE) < 100067);
        assertEquals(-1, PositionCode.tabCodeAt(9 + 9 * b * (PositionCode.TAB_BANDS + PositionCode.TAB_BASE) + 1));
        assertEquals(Settings.ROWS_OWN, Settings.bossbarRows(null));
        assertEquals(Settings.ROWS_OWN, Settings.bossbarRows("own"));
        assertEquals(Settings.ROWS_ALL, Settings.bossbarRows("ALL"));
        assertEquals(3, Settings.bossbarRows("3"));
        assertEquals(PositionCode.MAX_BOSSBAR_ROWS, Settings.bossbarRows("99"));
    }

    @Test
    void truncationRespectsPixelBudgetAndGlyphs() {
        int budget = TabLayout.nameBudget(12, W);
        assertEquals(78, budget);
        Component wide = Component.text("@@@@@@@@@@@@");
        Component cut = TabLayout.truncate(wide, 12, W);
        assertTrue(W.width(cut) <= budget, TabLayout.plain(cut));
        assertTrue(TabLayout.plain(cut).endsWith("..."));
        Component bold = Component.text("Abcdefghijklmn", Style.style(TextDecoration.BOLD));
        Component boldCut = TabLayout.truncate(bold, 12, W);
        assertTrue(W.width(boldCut) <= budget, TabLayout.plain(boldCut) + " " + W.width(boldCut));
        String shifts = "\uF801\uF802";
        Component nexo = Component.text()
                .append(Component.text("\uE123" + shifts))
                .append(Component.text("Abcdefghijklmnop", NamedTextColor.RED))
                .build();
        Component nexoCut = TabLayout.truncate(nexo, 12, W);
        String plain = TabLayout.plain(nexoCut);
        assertTrue(plain.startsWith("\uE123" + shifts), plain);
        assertTrue(plain.endsWith("..."));
        int px = 0;
        for (int i = 0; i < plain.length(); i++) {
            px += W.advance(plain.charAt(i), false);
        }
        assertTrue(px <= budget, plain + " " + px);
        Component glyphFont = Component.text()
                .append(Component.text("AB", Style.style().font(net.kyori.adventure.key.Key.key("nexo", "default")).build()))
                .append(Component.text("Abcdefghijklmnop"))
                .build();
        String gp = TabLayout.plain(TabLayout.truncate(glyphFont, 12, W));
        assertTrue(gp.startsWith("AB"), gp);
        Component shortName = Component.text("\uE123" + shifts + "Bob");
        assertSame(shortName, TabLayout.truncate(shortName, 12, W));
        Component exact = Component.text("abcdefghijkl");
        assertSame(exact, TabLayout.truncate(exact, 12, W));
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
        assertEquals(15, TabLayout.maxEntries(2, 15));
        assertEquals(40, TabLayout.maxEntries(2, 0));
        assertEquals(20, TabLayout.maxEntries(1, 30));
        assertEquals(15, TabLayout.maxEntries(0, 15));
        assertEquals(1, TabLayout.columns(TabLayout.maxEntries(2, 15)));
        assertEquals(15, TabLayout.rows(TabLayout.maxEntries(2, 15)));
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

    private static boolean narrow(double m, int sw) {
        return sw < 2 * (205 + m + 6) + 2 * 92;
    }

    private static double[] tabShift(int band, int sw, int sh) {
        double c = band + PositionCode.TAB_BASE;
        double b = PositionCode.TAB_BAND;
        int code = band % PositionCode.TAB_CODES;
        if (code >= PositionCode.TAB_CENTER) {
            return new double[]{0, Math.max(19 * (code - PositionCode.TAB_CENTER) + 2, 9) - (9 + 9 * (b * c + 2))};
        }
        int p = code & 3;
        double m = code >> 2;
        double half = 205 / 2;
        double mid = Math.floor(sw * 0.5);
        double dx = (p & 1) == 0 ? m + 2 - (mid - half - 1) : sw - m - 2 - (mid + half + 1);
        double dy = p < 2 ? m + 1 - (9 + 9 * (b * c + 2)) : sh - m - 2 - (12 + 9 * (b * c + b - 2));
        if (p >= 2 && narrow(m, sw)) {
            dy -= 60;
        }
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
                                    assertEquals(scr[1] - margin - (narrow(margin, scr[0]) ? 60 : 0), gBottom, 1e-9);
                                    if (!narrow(margin, scr[0])) {
                                        assertTrue(pos.side() == PositionCode.Side.LEFT ? right <= scr[0] / 2 - 92 : left >= scr[0] / 2 + 92);
                                    }
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

    @Test
    void pingBarsSettingDefaultsToHidden() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration empty = new org.bukkit.configuration.file.YamlConfiguration();
        assertFalse(Settings.of(empty).pingBars());
        org.bukkit.configuration.file.YamlConfiguration on = new org.bukkit.configuration.file.YamlConfiguration();
        on.loadFromString("tab:\n  ping-bars: true\n");
        assertTrue(Settings.of(on).pingBars());
        org.bukkit.configuration.file.YamlConfiguration bundled = new org.bukkit.configuration.file.YamlConfiguration();
        bundled.loadFromString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/config.yml")));
        assertTrue(bundled.isBoolean("tab.ping-bars"));
        assertFalse(Settings.of(bundled).pingBars());
    }

    @Test
    void pingHiddenOnlyWhenTabIsShifted() {
        assertTrue(Hud.tabShifts(true, 3, 10));
        assertTrue(Hud.tabShifts(true, PositionCode.TAB_MAX_HEADER, PositionCode.TAB_MAX_LINES));
        assertFalse(Hud.tabShifts(false, 3, 10));
        assertFalse(Hud.tabShifts(true, PositionCode.TAB_MAX_HEADER + 1, 20));
        assertFalse(Hud.tabShifts(true, 3, PositionCode.TAB_MAX_LINES + 1));
        assertEquals(0, PositionCode.tabPadLines(PositionCode.Position.CENTER, 0, PositionCode.TAB_MAX_LINES + 1, 0, true));
    }

    @Test
    void pingHiddenBandsKeepEveryPosition() {
        PositionCode.Position[] all = {PositionCode.Position.TOP_LEFT, PositionCode.Position.TOP_RIGHT,
                PositionCode.Position.BOTTOM_LEFT, PositionCode.Position.BOTTOM_RIGHT, PositionCode.Position.CENTER};
        for (PositionCode.Position pos : all) {
            for (int margin = 0; margin <= 15; margin += 5) {
                for (int bars = 0; bars <= PositionCode.MAX_BOSSBAR_ROWS; bars += pos == PositionCode.Position.CENTER ? 1 : 99) {
                    for (int lines = 3; lines <= PositionCode.TAB_MAX_LINES; lines++) {
                        int code = PositionCode.tabCode(pos, margin, bars, true);
                        assertTrue(code >= 0 && code < PositionCode.TAB_CODES, pos + " " + bars);
                        int band = PositionCode.tabBand(code, true);
                        assertEquals(code + PositionCode.TAB_NOPING, band);
                        int hidden = PositionCode.tabPadLines(pos, margin, lines, bars, true);
                        int top = 9 + 9 * hidden;
                        int bottom = 12 + 9 * (hidden + lines);
                        for (int y : new int[]{top - 1, top, 10 + 9 * hidden + 9, bottom, bottom + 2}) {
                            assertEquals(band, PositionCode.tabCodeAt(y), pos + " y" + y);
                            assertTrue(PositionCode.tabPingHidden(PositionCode.tabCodeAt(y)));
                            assertEquals(code, PositionCode.tabLayout(PositionCode.tabCodeAt(y)));
                        }
                        int shown = PositionCode.tabPadLines(pos, margin, lines, bars, false);
                        if (shown > 0) {
                            int shownBand = PositionCode.tabCodeAt(9 + 9 * shown);
                            assertFalse(PositionCode.tabPingHidden(shownBand));
                            assertEquals(code, shownBand);
                            assertEquals(hidden - shown, PositionCode.TAB_BAND * PositionCode.TAB_NOPING);
                        }
                        for (int[] scr : SCREENS) {
                            double[] d = tabShift(band, scr[0], scr[1]);
                            if (shown > 0) {
                                double[] e = tabShift(code, scr[0], scr[1]);
                                assertEquals(9 + 9 * shown + e[1], top + d[1], 1e-9);
                                assertEquals(e[0], d[0], 1e-9);
                            } else {
                                assertEquals(PositionCode.Position.CENTER, pos);
                                assertEquals(0, bars);
                                assertEquals(0, d[0], 1e-9);
                                assertEquals(9, top + d[1], 1e-9);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(-1, PositionCode.centerCode(0));
        assertEquals(PositionCode.TAB_CENTER, PositionCode.centerCode(0, true));
        assertEquals(-1, PositionCode.tabBand(-1, true));
        assertEquals(0, PositionCode.tabPadLines(PositionCode.Position.CENTER, 4, PositionCode.TAB_MAX_LINES + 1, 0, true));
        assertTrue(9 + 9 * PositionCode.TAB_BAND * (PositionCode.TAB_BANDS + PositionCode.TAB_BASE) < 100067);
    }

    private static double[] boardPanel(int code, int sw, int sh, int width, int align) {
        PositionCode.BoardFields f = PositionCode.boardFields(code);
        double k = code + PositionCode.BOARD_BASE;
        double cell = PositionCode.BOARD_CELL;
        double n = f.lines();
        double bottom = Math.floor(sh * 0.5) + 3 * n;
        double frameTop = bottom - 9 * n - 11;
        double h = bottom + 1 - frameTop;
        boolean right = PositionCode.boardFromRight(f.slot());
        double edge = right ? sw - 5 - 8 - cell * (k - 1) : sw - 3 + 8 - cell * k;
        double m = f.margin();
        double lift = right ? 60 : 132;
        boolean high = f.slot() == 1 || f.slot() == 2;
        double top = frameTop;
        if (high) {
            top = m;
        } else if (f.slot() == 3 || f.slot() == 4) {
            top = sh - m - lift - h;
        }
        double side = 0;
        if (f.tab()) {
            boolean down = !f.tabBottom();
            double raise = f.tabBottom() && narrow(f.tabMargin(), sw) ? 60 : 0;
            double near = down ? f.tabExtent() : sh - raise - f.tabExtent();
            boolean hit = down ? top < near + PositionCode.BOARD_GAP : top + h > near - PositionCode.BOARD_GAP;
            if (hit) {
                double moved = down ? near + PositionCode.BOARD_GAP : near - PositionCode.BOARD_GAP - h;
                boolean fits = down ? moved + h <= sh - lift : moved >= 2;
                if (fits) {
                    top = moved;
                } else {
                    double away = f.tabMargin() + 2 * (205 / 2) + 6;
                    double mid = Math.floor(sw * 0.5);
                    double inner = right ? sw - away - 6 - 236 : away + 6 + 236;
                    if (right ? inner >= mid + 92 : inner <= mid - 92) {
                        side = away;
                    }
                }
            }
        }
        double dx = right ? sw - side - (edge + 3) : side - (edge - 3);
        double e = edge + (right ? -align : align);
        double lo = right ? e - width - 3 : e - 3;
        return new double[]{lo + dx, top, lo + width + 6 + dx, top + h};
    }

    private static final PositionCode.Position[] BOARDS = {PositionCode.Position.LEFT, PositionCode.Position.TOP_LEFT,
            PositionCode.Position.TOP_RIGHT, PositionCode.Position.BOTTOM_LEFT, PositionCode.Position.BOTTOM_RIGHT,
            PositionCode.Position.RIGHT};
    private static final PositionCode.Position[] TABS = {PositionCode.Position.CENTER, PositionCode.Position.TOP_LEFT,
            PositionCode.Position.TOP_RIGHT, PositionCode.Position.BOTTOM_LEFT, PositionCode.Position.BOTTOM_RIGHT};

    @Test
    void boardBandPlacesPanel() {
        assertTrue((long) PositionCode.BOARD_CELL * (PositionCode.BOARD_BASE + PositionCode.BOARD_CODES) < (1L << 23));
        assertTrue((long) PositionCode.BOARD_CELL * PositionCode.BOARD_BASE > 8192 + 3840 + 8);
        assertEquals(236, PositionCode.BOARD_MAX_WIDTH);
        assertEquals(232, PositionCode.boardMaxWidth(4));
        assertEquals(221, PositionCode.boardMaxWidth(15));
        for (PositionCode.Position pos : BOARDS) {
            for (int n = 0; n <= 15; n += 3) {
                for (int margin : new int[]{0, 4, 15}) {
                    int code = PositionCode.boardCode(pos, n, margin);
                    assertTrue(code >= 0 && code < PositionCode.BOARD_PLAIN);
                    PositionCode.BoardFields f = PositionCode.boardFields(code);
                    assertEquals(pos.boardSlot(), f.slot());
                    assertEquals(n, f.lines());
                    assertEquals(margin, f.margin());
                    assertFalse(f.tab());
                    for (int width : new int[]{1, 40, 120, PositionCode.boardMaxWidth(margin)}) {
                        for (int[] scr : SCREENS) {
                            double[] r = boardPanel(code, scr[0], scr[1], width, margin);
                            assertEquals(width + 6, r[2] - r[0], 1e-9);
                            assertEquals(9 * n + 12, r[3] - r[1], 1e-9);
                            if (pos.side() == PositionCode.Side.LEFT) {
                                assertEquals(margin, r[0], 1e-9);
                            } else {
                                assertEquals(scr[0] - margin, r[2], 1e-9);
                            }
                            switch (pos) {
                                case TOP_LEFT, TOP_RIGHT -> assertEquals(margin, r[1], 1e-9);
                                case BOTTOM_LEFT -> assertEquals(scr[1] - margin - 132, r[3], 1e-9);
                                case BOTTOM_RIGHT -> assertEquals(scr[1] - margin - 60, r[3], 1e-9);
                                default -> assertEquals(scr[1] / 2 + 3 * n + 1, r[3], 1e-9);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(-1, PositionCode.boardCode(PositionCode.Position.CENTER, 3, 4));
        assertEquals(-1, PositionCode.boardCodeAt(100, 640));
        assertTrue(PositionCode.sameSide(PositionCode.Position.TOP_LEFT, PositionCode.Position.LEFT));
        assertTrue(PositionCode.sameSide(PositionCode.Position.BOTTOM_RIGHT, PositionCode.Position.RIGHT));
        assertFalse(PositionCode.sameSide(PositionCode.Position.CENTER, PositionCode.Position.RIGHT));
        assertFalse(PositionCode.sameSide(PositionCode.Position.TOP_LEFT, PositionCode.Position.TOP_RIGHT));
    }

    @Test
    void boardCodesDecodeEverywhere() {
        Set<Integer> seen = new HashSet<>();
        int max = -1;
        for (PositionCode.Position pos : BOARDS) {
            for (PositionCode.Position tab : TABS) {
                for (int n = 0; n <= 15; n++) {
                    for (int margin = 0; margin <= 15; margin++) {
                        for (int tm = 0; tm <= 15; tm++) {
                            for (int lines = 1; lines <= PositionCode.TAB_MAX_LINES + 1; lines++) {
                                int code = PositionCode.boardCode(pos, n, margin, tab, tm, lines);
                                assertTrue(code >= 0 && code < PositionCode.BOARD_CODES);
                                max = Math.max(max, code);
                                seen.add(code);
                                PositionCode.BoardFields f = PositionCode.boardFields(code);
                                assertEquals(pos.boardSlot(), f.slot());
                                assertEquals(n, f.lines());
                                boolean avoid = PositionCode.avoids(pos, tab, lines);
                                assertEquals(avoid, f.tab(), pos + " " + tab + " " + lines);
                                assertEquals(avoid, tab.side() == pos.side() && lines <= PositionCode.TAB_MAX_LINES);
                                if (!avoid) {
                                    assertEquals(margin, f.margin());
                                    continue;
                                }
                                int extent = PositionCode.tabExtent(tm, lines);
                                assertEquals(tab.bottom(), f.tabBottom());
                                assertTrue(f.tabExtent() >= extent && f.tabMargin() >= tm);
                                boolean centred = pos == PositionCode.Position.LEFT || pos == PositionCode.Position.RIGHT;
                                if (centred || (!tab.bottom() && !pos.bottom())) {
                                    assertTrue(f.tabExtent() <= extent + 2 && f.tabMargin() <= tm + 2);
                                    assertEquals(f.tabExtent() - f.tabMargin(), 9 * lines + 6);
                                    assertEquals(centred ? 0 : f.tabMargin(), f.margin());
                                } else {
                                    assertTrue(f.tabExtent() < extent + PositionCode.BOARD_COARSE_STEP);
                                    assertEquals(15, f.tabMargin());
                                    assertEquals(margin, f.margin());
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(PositionCode.BOARD_CODES - 1, max);
        assertEquals(PositionCode.BOARD_CODES, seen.size());
        for (int code : new int[]{0, 1535, 1536, 9000, 12287, 12288, 17663, 17664, 30000, PositionCode.BOARD_CODES - 1}) {
            for (int align = 0; align <= 15; align += 5) {
                for (int width : new int[]{1, 120, PositionCode.boardMaxWidth(align)}) {
                    long total = PositionCode.boardTitleWidth(code, width, align);
                    for (int[] scr : SCREENS) {
                        long x0 = scr[0] - total - 3;
                        assertTrue(x0 - 6 > -(1L << 23));
                        for (long x = x0 - 6; x <= x0 + width + 6; x++) {
                            assertEquals(code, PositionCode.boardCodeAt(x, scr[0]), code + " x" + (x - x0));
                        }
                        assertTrue(x0 + width + 6 < -8192);
                    }
                }
            }
        }
    }

    private static double[] tabPanel(PositionCode.Position tab, int tm, int lines, int sw, int sh) {
        double h = 9 * lines + 6;
        double lo = tab.side() == PositionCode.Side.LEFT ? tm : sw - tm - 210;
        double top = tab.bottom() ? sh - tm - (narrow(tm, sw) ? 60 : 0) - h : tm;
        return new double[]{lo, top, lo + 210, top + h};
    }

    private static boolean overlaps(double[] a, double[] b, double gap) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] + gap && b[1] < a[3] + gap;
    }

    @Test
    void boardAvoidsTabOnlyWhenTheyOverlap() {
        int[][] screens = {{320, 180}, {320, 200}, {320, 240}, {427, 240}, {480, 270}, {480, 300}, {640, 360},
                {640, 400}, {683, 384}, {960, 540}, {1280, 720}, {1920, 1080}, {1920, 300}, {2560, 400}};
        int moved = 0;
        int sideways = 0;
        int stayed = 0;
        int stuck = 0;
        for (PositionCode.Position pos : BOARDS) {
            for (PositionCode.Position tab : TABS) {
                if (!PositionCode.sameSide(tab, pos)) {
                    continue;
                }
                for (int n = 1; n <= 15; n += 2) {
                    for (int margin : new int[]{0, 4, 15}) {
                        for (int tm : new int[]{0, 4, 7, 15}) {
                            for (int lines = 2; lines <= PositionCode.TAB_MAX_LINES; lines += 3) {
                                int code = PositionCode.boardCode(pos, n, margin, tab, tm, lines);
                                int plain = PositionCode.boardCode(pos, n, margin);
                                boolean exact = pos == PositionCode.Position.LEFT || pos == PositionCode.Position.RIGHT
                                        || (!tab.bottom() && !pos.bottom());
                                int align = pos == PositionCode.Position.RIGHT ? 0 : margin;
                                PositionCode.BoardFields f = PositionCode.boardFields(code);
                                boolean corner = pos != PositionCode.Position.LEFT && pos != PositionCode.Position.RIGHT
                                        && !tab.bottom() && !pos.bottom();
                                if (corner) {
                                    plain = PositionCode.boardCode(pos, n, f.margin());
                                }
                                for (int[] scr : screens) {
                                    double[] t = tabPanel(tab, tm, lines, scr[0], scr[1]);
                                    double[] home = boardPanel(plain, scr[0], scr[1], 120, align);
                                    double[] r = boardPanel(code, scr[0], scr[1], 120, align);
                                    String at = pos + " tab " + tab + " n" + n + " m" + margin + " tm" + tm + " T" + lines + " " + scr[0] + "x" + scr[1];
                                    double mid = Math.floor(scr[0] * 0.5);
                                    assertTrue(r[0] == home[0] || (pos.side() == PositionCode.Side.LEFT ? r[2] <= mid - 92 : r[0] >= mid + 92), at);
                                    assertEquals(r[2] - r[0], home[2] - home[0], 1e-9);
                                    assertEquals(r[3] - r[1], home[3] - home[1], 1e-9);
                                    double slack = exact ? 2 : PositionCode.BOARD_COARSE_STEP;
                                    boolean clear = !overlaps(home, new double[]{t[0], t[1] - slack, t[2], t[3] + slack}, PositionCode.BOARD_GAP);
                                    if (clear) {
                                        assertArrayEquals(home, r, 1e-9, at);
                                        stayed++;
                                    } else if (java.util.Arrays.equals(home, r)) {
                                        if (overlaps(home, t, PositionCode.BOARD_GAP)) {
                                            stuck++;
                                            double h = r[3] - r[1];
                                            double lift = pos.side() == PositionCode.Side.LEFT ? 132 : 60;
                                            double raise = tab.bottom() && narrow(f.tabMargin(), scr[0]) ? 60 : 0;
                                            boolean fits = tab.bottom() ? scr[1] - raise - f.tabExtent() - 3 - h >= 2
                                                    : f.tabExtent() + 3 + h <= scr[1] - lift;
                                            double inner = f.tabMargin() + 216 + 6 + 236;
                                            assertFalse(fits, at);
                                            assertTrue(pos.side() == PositionCode.Side.LEFT ? inner > mid - 92 : scr[0] - inner < mid + 92, at);
                                        } else {
                                            stayed++;
                                        }
                                    } else if (r[0] == home[0]) {
                                        assertFalse(overlaps(r, t, 0), at);
                                        moved++;
                                        assertTrue(r[1] >= 0 && r[3] <= scr[1], at);
                                        double gap = tab.bottom() ? t[1] - r[3] : r[1] - t[3];
                                        assertTrue(gap >= PositionCode.BOARD_GAP && gap <= PositionCode.BOARD_GAP + slack, at + " gap " + gap);
                                    } else {
                                        sideways++;
                                        assertFalse(overlaps(r, t, 0), at);
                                        assertArrayEquals(new double[]{home[1], home[3]}, new double[]{r[1], r[3]}, 1e-9, at);
                                        double gap = pos.side() == PositionCode.Side.LEFT ? r[0] - t[2] : t[0] - r[2];
                                        assertTrue(gap >= align && gap <= align + slack, at + " side " + gap);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(moved > 1000 && sideways > 50 && stayed > 1000 && stuck > 100, moved + " " + sideways + " " + stayed + " " + stuck);
    }

    @Test
    void userLayoutKeepsBoardInPlace() {
        int code = PositionCode.boardCode(PositionCode.Position.RIGHT, 6, 4, PositionCode.Position.TOP_RIGHT, 4, 4);
        double[] home = {0, 0, 1440 / 4.0, 0};
        double[] r = boardPanel(code, 1440 / 4, 900 / 4, 120, 0);
        assertEquals(home[2], r[2], 1e-9);
        assertEquals(Math.floor(225 * 0.5) + 18 + 1, r[3], 1e-9);
        int tall = PositionCode.boardCode(PositionCode.Position.RIGHT, 6, 4, PositionCode.Position.TOP_RIGHT, 4, 8);
        double[] below = boardPanel(tall, 1440 / 4, 900 / 4, 120, 0);
        assertEquals(home[2], below[2], 1e-9);
        assertEquals(6 + 9 * 8 + 6 + 3, below[1], 1e-9);
        int huge = PositionCode.boardCode(PositionCode.Position.RIGHT, 6, 4, PositionCode.Position.TOP_RIGHT, 4, 19);
        assertArrayEquals(r, boardPanel(huge, 1440 / 4, 900 / 4, 120, 0), 1e-9);
        for (int[] scr : new int[][]{{427, 240}, {480, 270}}) {
            int full = PositionCode.boardCode(PositionCode.Position.RIGHT, 6, 4, PositionCode.Position.TOP_RIGHT, 4, 19);
            int plain = PositionCode.boardCode(PositionCode.Position.RIGHT, 6, 4);
            assertArrayEquals(boardPanel(plain, scr[0], scr[1], 120, 0), boardPanel(full, scr[0], scr[1], 120, 0), 1e-9);
        }
        int wide = PositionCode.boardCode(PositionCode.Position.RIGHT, 6, 4, PositionCode.Position.TOP_RIGHT, 4, 25);
        double[] beside = boardPanel(wide, 1920, 1080 / 2 - 200, 120, 0);
        assertEquals(1920 - 216, beside[2], 1e-9);
    }

    @Test
    void avoidSettingAndTabLimit() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration empty = new org.bukkit.configuration.file.YamlConfiguration();
        Settings d = Settings.of(empty);
        assertEquals(PositionCode.Avoid.OVERLAP, d.boardAvoid());
        assertEquals(15, d.maxPlayers());
        assertEquals(15, d.tabLimit());
        org.bukkit.configuration.file.YamlConfiguration c = new org.bukkit.configuration.file.YamlConfiguration();
        c.loadFromString("tab:\n  max-players: 0\n  max-columns: 3\n  position: top-right\nboard:\n  avoid-tab: never\n");
        Settings s = Settings.of(c);
        assertEquals(PositionCode.Avoid.NEVER, s.boardAvoid());
        assertEquals(60, s.tabLimit());
        org.bukkit.configuration.file.YamlConfiguration bundled = new org.bukkit.configuration.file.YamlConfiguration();
        bundled.loadFromString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/config.yml")));
        assertEquals(15, bundled.getInt("tab.max-players"));
        assertEquals("overlap", bundled.getString("board.avoid-tab"));
        assertEquals(PositionCode.Avoid.OVERLAP, PositionCode.Avoid.parse("x", PositionCode.Avoid.OVERLAP));
        assertEquals(PositionCode.Avoid.NEVER, PositionCode.Avoid.parse(" Never ", PositionCode.Avoid.OVERLAP));

        Component title = MiniMessage.miniMessage().deserialize("<b>Amazake</b>");
        List<Component> lines = List.of(Component.text("Online: 1"), Component.text("XYZ: 1 2 3"));
        c.loadFromString("tab:\n  position: top-right\nboard:\n  position: right\n");
        Settings overlap = Settings.of(c);
        assertSame(title, Hud.boardTitle(title, lines, overlap, W, 0));
        Component avoiding = Hud.boardTitle(title, lines, overlap, W, 5);
        assertFalse(avoiding == title);
        int rgb = avoiding.children().getFirst().color().value();
        assertEquals(PositionCode.BOARD_MARKER_R, PositionCode.red(rgb));
        assertEquals(0, PositionCode.markerAlign(rgb));
        c.loadFromString("tab:\n  position: top-right\nboard:\n  position: right\n  avoid-tab: never\n");
        assertSame(title, Hud.boardTitle(title, lines, Settings.of(c), W, 5));
        c.loadFromString("tab:\n  position: center\nboard:\n  position: right\n");
        assertSame(title, Hud.boardTitle(title, lines, Settings.of(c), W, 5));
        c.loadFromString("tab:\n  position: top-left\nboard:\n  position: top-right\n  margin: 7\n");
        Component plain = Hud.boardTitle(title, lines, Settings.of(c), W, 5);
        assertEquals(7, PositionCode.markerAlign(plain.children().getFirst().color().value()));
        assertEquals(229, Hud.maxWidth(Settings.of(c)));
        assertEquals(236, Hud.maxWidth(overlap));
        List<Component> wide = List.of(Component.text("x".repeat(60)));
        assertTrue(Hud.boardTooWide(title, wide, overlap, W, 5));
        assertFalse(Hud.boardTooWide(title, wide, overlap, W, 0));
        c.loadFromString("tab:\n  position: top-left\nboard:\n  position: right\n");
        assertFalse(Hud.boardTooWide(title, wide, Settings.of(c), W, 5));
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
        assertEquals(0xE9F078, PositionCode.boardMarker(120, 15));
        assertEquals(120, PositionCode.markerValue(PositionCode.boardMarker(120, 15)));
        assertEquals(15, PositionCode.markerAlign(PositionCode.boardMarker(120, 15)));
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
            int code = PositionCode.boardCode(pos, 6, 4);
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
        assertTrue(glsl.contains("#define TH_TAB_WIDTH " + PositionCode.TAB_WIDTH + "\n"));
        assertTrue(glsl.contains("#define TH_TAB_BAND " + PositionCode.TAB_BAND + "\n"));
        assertTrue(glsl.contains("#define TH_TAB_CODES " + PositionCode.TAB_CODES + "\n"));
        assertTrue(glsl.contains("#define TH_TAB_CENTER " + PositionCode.TAB_CENTER + "\n"));
        assertTrue(glsl.contains("#define TH_TAB_NOPING " + PositionCode.TAB_NOPING + "\n"));
        assertTrue(glsl.contains("#define TH_TAB_BANDS " + PositionCode.TAB_BANDS + "\n"));
        assertTrue(glsl.contains("#define TH_TAB_HIDDEN " + PositionCode.TAB_HIDDEN + "\n"));
        assertTrue(glsl.contains("int code = band % TH_TAB_CODES;"));
        assertTrue(glsl.contains("max(TH_BOSSBAR_ROW * float(code - TH_TAB_CENTER) + 2.0, 9.0)"));
        assertTrue(glsl.contains("#define TH_TAB_BASE " + PositionCode.TAB_BASE + "\n"));
        assertTrue(glsl.contains("#define TH_BOARD_CELL " + PositionCode.BOARD_CELL + "\n"));
        assertTrue(glsl.contains("#define TH_BOARD_BASE " + PositionCode.BOARD_BASE + "\n"));
        assertTrue(glsl.contains("#define TH_BOARD_CODES " + PositionCode.BOARD_CODES + "\n"));
        assertEquals(205, Settings.width("205"));
        assertEquals(PositionCode.TAB_WIDTH, TabLayout.defaultWidth(W, 12, 2));
    }

    @Test
    void hiddenBandRoundTrip() {
        int hidden = PositionCode.TAB_HIDDEN;
        assertEquals(2 * PositionCode.TAB_CODES, hidden);
        assertEquals(hidden + 1, PositionCode.TAB_BANDS);
        assertTrue(PositionCode.tabHidden(hidden));
        assertEquals(-1, PositionCode.tabLayout(hidden));
        for (int band = -1; band < hidden; band++) {
            assertFalse(PositionCode.tabHidden(band), "band " + band);
        }
        int b = PositionCode.TAB_BAND;
        int start = 9 + 9 * b * (hidden + PositionCode.TAB_BASE);
        int end = start + 9 * b;
        assertEquals(hidden, PositionCode.tabCodeAt(start));
        assertEquals(hidden, PositionCode.tabCodeAt(end - 1));
        assertEquals(-1, PositionCode.tabCodeAt(end));
        assertEquals(PositionCode.TAB_HIDDEN - 1, PositionCode.tabCodeAt(start - 1));
        assertTrue(end < 100067);
        PositionCode.Position[] all = {PositionCode.Position.TOP_LEFT, PositionCode.Position.TOP_RIGHT,
                PositionCode.Position.BOTTOM_LEFT, PositionCode.Position.BOTTOM_RIGHT, PositionCode.Position.CENTER};
        for (PositionCode.Position pos : all) {
            for (boolean noPing : new boolean[]{false, true}) {
                int band = PositionCode.tabBand(PositionCode.tabCode(pos, 99, 99, noPing), noPing);
                assertTrue(band < hidden, pos + " " + noPing);
                for (int lines = 1; lines <= PositionCode.TAB_MAX_LINES; lines++) {
                    int pad = PositionCode.tabPadLines(pos, 99, lines, 99, noPing);
                    assertTrue(pad < PositionCode.hiddenPadLines());
                    assertFalse(PositionCode.tabHidden(PositionCode.tabCodeAt(12 + 9 * (pad + lines))));
                }
            }
        }
    }

    private static void collectFonts(Component c, List<Component> out) {
        if (c.style().font() != null || (c.color() != null && PositionCode.red(c.color().value()) == PositionCode.TAB_MARKER_R)) {
            out.add(c);
        }
        for (Component child : c.children()) {
            collectFonts(child, out);
        }
    }

    @Test
    void hiddenHeaderPushesWholeTabIntoHiddenBand() {
        List<Component> lines = TabLayout.hiddenHeader();
        int pad = PositionCode.hiddenPadLines();
        assertEquals(PositionCode.TAB_BAND * (PositionCode.TAB_HIDDEN + PositionCode.TAB_BASE) + 2, pad);
        assertEquals(pad + 1, lines.size());
        for (int i = 0; i < pad; i++) {
            assertEquals("", TabLayout.plain(lines.get(i)));
        }
        assertEquals(" ", TabLayout.plain(lines.getLast()));
        String text = TabLayout.plain(Hud.HIDDEN_HEADER);
        assertEquals(pad, text.chars().filter(ch -> ch == '\n').count());
        assertEquals(pad + 1, text.length());
        assertEquals("", TabLayout.plain(Hud.NONE));
        List<Component> marked = new ArrayList<>();
        collectFonts(Hud.HIDDEN_HEADER, marked);
        assertTrue(marked.isEmpty(), marked.toString());
        int h = lines.size();
        int fillBottom = 10 + 9 * h;
        assertEquals(1, fillBottom % 9);
        assertEquals(-1, PositionCode.tabCodeAt(9));
        assertEquals(PositionCode.TAB_HIDDEN, PositionCode.tabCodeAt(fillBottom));
        assertEquals(PositionCode.TAB_HIDDEN, PositionCode.tabCodeAt(10 + 9 * (h - 1)));
        for (int entries = 0; entries <= TabLayout.CLIENT_CAP; entries++) {
            int rows = TabLayout.rows(entries);
            int listTop = fillBottom + 1;
            int listBottom = listTop + 9 * rows;
            assertEquals(PositionCode.TAB_HIDDEN, PositionCode.tabCodeAt(listTop - 1));
            assertEquals(PositionCode.TAB_HIDDEN, PositionCode.tabCodeAt(listBottom));
            for (int footer = 0; footer <= 8; footer++) {
                assertEquals(PositionCode.TAB_HIDDEN, PositionCode.tabCodeAt(listBottom + 1 + 9 * footer + 2), entries + " " + footer);
            }
        }
    }

    @Test
    void tabVisibleSetting() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration empty = new org.bukkit.configuration.file.YamlConfiguration();
        assertTrue(Settings.of(empty).tabVisible());
        org.bukkit.configuration.file.YamlConfiguration off = new org.bukkit.configuration.file.YamlConfiguration();
        off.loadFromString("position-markers: false\ntab:\n  enabled: false\n  visible: false\n");
        Settings s = Settings.of(off);
        assertFalse(s.tabVisible());
        assertFalse(s.tab());
        assertFalse(s.markers());
        org.bukkit.configuration.file.YamlConfiguration bundled = new org.bukkit.configuration.file.YamlConfiguration();
        bundled.loadFromString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/config.yml")));
        assertTrue(bundled.isBoolean("tab.visible"));
        assertTrue(Settings.of(bundled).tabVisible());
    }

    @Test
    void configFileKeepsEverythingButTheKey() throws Exception {
        String bundled = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/config.yml"));
        String off = ConfigFile.setBoolean(bundled, "tab", "visible", false);
        assertEquals(bundled.replace("tab:\n  enabled: true\n  visible: true\n", "tab:\n  enabled: true\n  visible: false\n"), off);
        assertEquals(bundled, ConfigFile.setBoolean(off, "tab", "visible", true));
        org.bukkit.configuration.file.YamlConfiguration a = new org.bukkit.configuration.file.YamlConfiguration();
        a.loadFromString(bundled);
        org.bukkit.configuration.file.YamlConfiguration b = new org.bukkit.configuration.file.YamlConfiguration();
        b.loadFromString(off);
        assertFalse(b.getBoolean("tab.visible"));
        for (String key : a.getKeys(true)) {
            if (!key.equals("tab.visible") && !a.isConfigurationSection(key)) {
                assertEquals(a.get(key), b.get(key), key);
            }
        }
        String old = "# top\ntab:\n    enabled: true\n    header:\n      - \"visible: x\"\n    visible: true   # keep\nboard:\n  visible: true\n";
        assertEquals("# top\ntab:\n    enabled: true\n    header:\n      - \"visible: x\"\n    visible: false   # keep\nboard:\n  visible: true\n",
                ConfigFile.setBoolean(old, "tab", "visible", false));
        String missing = "tab:\r\n  enabled: true\r\n  header:\r\n    - \"a\"\r\nboard:\r\n  enabled: true\r\n";
        assertEquals("tab:\r\n  visible: false\r\n  enabled: true\r\n  header:\r\n    - \"a\"\r\nboard:\r\n  enabled: true\r\n",
                ConfigFile.setBoolean(missing, "tab", "visible", false));
        assertEquals("server-name: x\ntab:\n  visible: false\n", ConfigFile.setBoolean("server-name: x", "tab", "visible", false));
        assertEquals("tab:\n  visible: true\nboard: {}\n", ConfigFile.setBoolean("tab:\nboard: {}\n", "tab", "visible", true));
        assertNull(ConfigFile.setBoolean("tab: {enabled: true}\n", "tab", "visible", false));
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("tabshader-config");
        java.nio.file.Path file = dir.resolve("config.yml");
        java.nio.file.Files.writeString(file, bundled);
        ConfigFile.saveBoolean(file, new org.bukkit.configuration.file.YamlConfiguration(), "tab", "visible", false);
        assertEquals(off, java.nio.file.Files.readString(file));
        assertFalse(java.nio.file.Files.exists(dir.resolve("config.yml.tmp")));
        java.nio.file.Files.delete(file);
        java.nio.file.Files.delete(dir);
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
