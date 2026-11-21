package dev.tearsmilk.aurahud.layout;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    static HudSettings read(String text) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return HudSettings.read(y.getConfigurationSection("hud"));
    }

    @Test
    void emptyConfigIsVanilla() {
        HudSettings s = read("hud:\n  hotbar:\n    enabled: true\n");
        assertEquals(List.of(), s.warnings());
        for (Element e : Element.values()) {
            assertEquals(ElementSettings.defaults(e), s.get(e));
        }
    }

    @Test
    void aliasKeysAreReadLikeTheirCanonicalNames() {
        HudSettings s = read("hud:\n  hearts:\n    layout: grid\n    columns: 2\n    line_gap: 5\n  hotbar:\n    attack: false\n    per_line: 3\n"
                + "  xp:\n    level_x: 4\n");
        assertEquals(List.of(), s.warnings());
        assertEquals(2, s.get(Element.HEARTS).perLine());
        assertEquals(5, s.get(Element.HEARTS).lineGap());
        assertFalse(s.get(Element.HOTBAR).attack());
        assertEquals(3, s.get(Element.HOTBAR).perLine());
        assertEquals(4, s.get(Element.XP).levelX());
        HudSettings both = read("hud:\n  hearts:\n    per-line: 4\n    columns: 2\n");
        assertEquals(4, both.get(Element.HEARTS).perLine());
        assertEquals(1, both.warnings().size());
    }

    @Test
    void offhandFollowsTheOuterSlotOfAReversedHotbar() {
        int w = 427;
        int h = 240;
        var p = LayoutEngine.place(read("hud:\n  hotbar:\n    reverse: true\n"));
        Placement.Geometry hb = p.get(Element.HOTBAR).geometry();
        assertEquals(8, LayoutEngine.edgeSlot(hb, false));
        assertEquals(0, LayoutEngine.edgeSlot(hb, true));
        var grid = LayoutEngine.place(read("hud:\n  hotbar:\n    layout: grid\n    per-line: 3\n    slots:\n      '1':\n        hidden: true\n"));
        assertEquals(3, LayoutEngine.edgeSlot(grid.get(Element.HOTBAR).geometry(), false));
        assertEquals(8, LayoutEngine.edgeSlot(grid.get(Element.HOTBAR).geometry(), true));
        ShaderMirror m = new ShaderMirror(p, w, h);
        assertEquals(0.0, m.offhandDelta(0)[0], 1e-9);
        assertEquals(0.0, m.offhandDelta(1)[0], 1e-9);
        var col = LayoutEngine.place(read("hud:\n  hotbar:\n    layout: column\n    direction: up\n    anchor: center-left\n    x: 40\n"));
        ShaderMirror c = new ShaderMirror(col, w, h);
        double[] slot = c.slotDelta(0);
        double[] off = c.offhandDelta(0);
        assertEquals(slot[0], off[0], 1e-9);
        assertEquals(slot[1], off[1], 1e-9);
    }

    @Test
    void offhandOffsetWorksTogetherWithFollow() {
        HudSettings s = read("hud:\n  offhand:\n    follow: hotbar\n    x: 3\n    y: -2\n");
        assertEquals(List.of(), s.warnings());
        var p = LayoutEngine.place(s);
        assertEquals(Placement.Mode.MOVED, p.get(Element.OFFHAND).mode());
        assertEquals(new Placement.Point(3, -2), LayoutEngine.followOffset(p.get(Element.OFFHAND)));
        HudSettings anchored = read("hud:\n  offhand:\n    follow: hotbar\n    anchor: top-left\n");
        assertEquals(1, anchored.warnings().size());
        assertEquals(Placement.Mode.VANILLA, LayoutEngine.place(anchored).get(Element.OFFHAND).mode());
    }

    @Test
    void nameGlyphsSkipActionbarCollisionsAndKeepVanillaFirst() {
        List<Placement.Point> g = NameGlyphs.select(new java.util.LinkedHashSet<>(List.of(new Placement.Point(-8, 20), new Placement.Point(0, 8),
                new Placement.Point(-13, 30), new Placement.Point(4, 5))));
        assertEquals(NameGlyphs.VANILLA, g.get(0));
        assertTrue(g.contains(new Placement.Point(-8, 20)));
        assertTrue(g.contains(new Placement.Point(4, 5)));
        assertFalse(g.contains(new Placement.Point(-13, 30)));
        assertEquals(3, g.size());
    }

    @Test
    void vanillaGeometryMatchesTheClient() {
        int w = 427;
        int h = 240;
        int cx = w / 2;
        var p = LayoutEngine.place(HudSettings.vanilla());
        assertEquals(new Placement.Point(cx - 91, h - 22), p.get(Element.HOTBAR).topLeft(w, h));
        for (int i = 0; i < 9; i++) {
            assertEquals(new Placement.Point(20 * i, 0), p.get(Element.HOTBAR).geometry().positions().get(i));
        }
        assertEquals(new Placement.Point(cx - 120, h - 23), p.get(Element.OFFHAND).topLeft(w, h));
        assertEquals(new Placement.Point(cx - 91, h - 39), p.get(Element.HEARTS).topLeft(w, h));
        assertEquals(new Placement.Point(0, -10), p.get(Element.HEARTS).geometry().positions().get(10));
        assertEquals(new Placement.Point(8 * 3, -10), p.get(Element.HEARTS).geometry().positions().get(13));
        assertEquals(new Placement.Point(cx - 91, h - 49), p.get(Element.ARMOR).topLeft(w, h));
        Placement food = p.get(Element.FOOD);
        for (int j = 0; j < 10; j++) {
            Placement.Point tl = food.topLeft(w, h);
            assertEquals(cx + 82 - 8 * j, tl.x() + food.geometry().positions().get(j).x());
            assertEquals(h - 39, tl.y() + food.geometry().positions().get(j).y());
        }
        Placement air = p.get(Element.AIR);
        assertEquals(new Placement.Point(cx + 82, h - 49), air.topLeft(w, h).plus(air.geometry().positions().get(0)));
        Placement mount = p.get(Element.MOUNT);
        assertEquals(new Placement.Point(cx + 82 - 8 * 3, h - 39 - 20), mount.topLeft(w, h).plus(mount.geometry().positions().get(23)));
        assertEquals(new Placement.Point(cx - 91, h - 29), p.get(Element.XP).topLeft(w, h));
        assertEquals(new Placement.Point(cx, h - 59), p.get(Element.ITEM_NAME).topLeft(w, h));
        assertEquals(new Placement.Point(0, -10), p.get(Element.HEARTS).geometry().lineStep());
    }

    @Test
    void oddScreenWidthsUseFloorLikeVanilla() {
        var p = LayoutEngine.place(HudSettings.vanilla());
        for (int w = 300; w < 330; w++) {
            assertEquals(w / 2 - 91, p.get(Element.HOTBAR).topLeft(w, 250).x());
            assertEquals(w / 2 - 91, p.get(Element.HEARTS).topLeft(w, 250).x());
            assertEquals(w / 2 + 10, p.get(Element.FOOD).topLeft(w, 250).x());
        }
    }

    @Test
    void anchorWithoutOffsetStartsAtZero() {
        HudSettings s = read("hud:\n  hearts:\n    anchor: top-left\n");
        assertEquals(Anchor.TOP_LEFT, s.get(Element.HEARTS).anchor());
        assertEquals(0, s.get(Element.HEARTS).x());
        assertEquals(0, s.get(Element.HEARTS).y());
        assertEquals(new Placement.Point(0, 0), LayoutEngine.place(s).get(Element.HEARTS).topLeft(427, 240));
        HudSettings r = read("hud:\n  food:\n    anchor: bottom-right\n    x: -2\n    y: -2\n");
        assertEquals(new Placement.Point(427 - 81 - 2, 240 - 9 - 2), LayoutEngine.place(r).get(Element.FOOD).topLeft(427, 240));
    }

    @Test
    void layoutsSetDirectionsAndReverseFlips() {
        HudSettings s = read("hud:\n  hotbar:\n    layout: column\n  hearts:\n    layout: column\n  food:\n    layout: grid\n    reverse: true\n  armor:\n    direction: up\n");
        assertEquals(List.of(), s.warnings());
        assertEquals(Direction.DOWN, s.get(Element.HOTBAR).direction());
        assertEquals(Direction.RIGHT, s.get(Element.HOTBAR).wrap());
        assertEquals(Direction.UP, s.get(Element.HEARTS).direction());
        assertEquals(Direction.RIGHT, s.get(Element.FOOD).direction());
        assertEquals(5, s.get(Element.FOOD).perLine());
        assertEquals(Direction.RIGHT, s.get(Element.ARMOR).wrap());
        var g = LayoutEngine.geometry(s.get(Element.HOTBAR));
        assertEquals(22, g.width());
        assertEquals(182, g.height());
        assertEquals(new Placement.Point(0, 160), g.positions().get(8));
        assertTrue(g.slice());
    }

    @Test
    void badValuesWarnAndFallBack() {
        HudSettings s = read("hud:\n  hotbar:\n    anchor: nowhere\n    x: abc\n    layout: spiral\n    gap: 9999\n    slots:\n      '0':\n        x: 1\n      '3':\n        hidden: maybe\n"
                + "  hearts:\n    direction: right\n    wrap: left\n    colour: red\n  xp:\n    rotate: 45\n  offhand:\n    follow: everywhere\n  bossbar:\n    x: 1\n");
        List<String> w = s.warnings();
        assertEquals(11, w.size(), String.join("\n", w));
        assertEquals(ElementSettings.defaults(Element.HOTBAR).x(), s.get(Element.HOTBAR).x());
        assertEquals(Arrangement.ROW, s.get(Element.HOTBAR).layout());
        assertEquals(0, s.get(Element.HOTBAR).gap());
        assertEquals(Direction.UP, s.get(Element.HEARTS).wrap());
        assertEquals(0, s.get(Element.XP).rotate());
        assertTrue(s.get(Element.HOTBAR).slots().isEmpty());
    }

    @Test
    void slotsUseKeyboardNumbers() {
        HudSettings s = read("hud:\n  hotbar:\n    slots:\n      '1':\n        y: -4\n      '9':\n        hidden: true\n");
        assertEquals(List.of(), s.warnings());
        var g = LayoutEngine.geometry(s.get(Element.HOTBAR));
        assertEquals(new Placement.Point(0, -4), g.positions().get(0));
        assertEquals(1 << 8, g.hiddenMask());
        assertEquals(new Placement.Point(0, -4), g.box());
        assertEquals(new Placement.Point(162, 26), g.boxSize());
    }

    @Test
    void offhandPlacementTurnsFollowOff() {
        assertTrue(read("hud: {}\n").get(Element.OFFHAND).follow());
        assertFalse(read("hud:\n  offhand:\n    x: 40\n").get(Element.OFFHAND).follow());
        assertTrue(read("hud:\n  offhand:\n    x: 40\n    follow: hotbar\n").get(Element.OFFHAND).follow());
        var p = LayoutEngine.place(read("hud:\n  hotbar:\n    y: -10\n"));
        assertEquals(Placement.Mode.MOVED, p.get(Element.OFFHAND).mode());
        assertEquals(Placement.Mode.VANILLA, LayoutEngine.place(read("hud: {}\n")).get(Element.OFFHAND).mode());
    }

    @Test
    void hidingAllSlotsHidesTheHotbar() {
        StringBuilder b = new StringBuilder("hud:\n  hotbar:\n    slots:\n");
        for (int i = 1; i <= 9; i++) {
            b.append("      '").append(i).append("':\n        hidden: true\n");
        }
        assertEquals(Placement.Mode.HIDDEN, LayoutEngine.place(read(b.toString())).get(Element.HOTBAR).mode());
        assertEquals(Placement.Mode.HIDDEN, LayoutEngine.place(read("hud:\n  xp:\n    enabled: false\n")).get(Element.XP).mode());
    }

    @Test
    void settingValuesParse() {
        assertEquals(Boolean.TRUE, Setting.ENABLED.value("да"));
        assertEquals(-12, Setting.X.value("-12"));
        assertEquals(12, Setting.X.value("+12"));
        assertNull(Setting.X.value("99999"));
        assertNull(Setting.PER_LINE.value("0"));
        assertEquals("bottom-left", Setting.ANCHOR.value("BOTTOM_LEFT"));
        assertEquals(270, Setting.ROTATE.value("270"));
        assertNull(Setting.ROTATE.value("100"));
        assertEquals(Setting.PER_LINE, Setting.parse("columns"));
        assertEquals(Element.HEARTS, Element.parse("health"));
        assertEquals(Element.ITEM_NAME, Element.parse("item_name"));
        assertTrue(Setting.ROTATE.appliesTo(Element.XP));
        assertFalse(Setting.ROTATE.appliesTo(Element.HOTBAR));
        assertFalse(Setting.LAYOUT.appliesTo(Element.XP));
    }
}
