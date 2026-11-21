package dev.tearsmilk.aurahud.layout;

import dev.tearsmilk.aurahud.layout.Placement.Geometry;
import dev.tearsmilk.aurahud.layout.Placement.Point;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudMirrorTest {

    private static final int[][] SIZES = {{320, 240}, {427, 240}, {481, 271}, {640, 360}, {683, 384}, {960, 509}};

    static Map<Element, Placement> preset(String name) {
        YamlConfiguration y = Presets.builtIn(name);
        HudSettings s = HudSettings.read(y.getConfigurationSection("hud"));
        assertEquals(List.of(), s.warnings(), name);
        return LayoutEngine.place(s);
    }

    static Map<Element, Placement> yaml(String text) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        HudSettings s = HudSettings.read(y.getConfigurationSection("hud"));
        assertEquals(List.of(), s.warnings());
        return LayoutEngine.place(s);
    }

    private static ShaderMirror.Move move(ShaderMirror m, VanillaHud.Quad q, int k) {
        double[] v = q.v()[k];
        return switch (q.kind()) {
            case SPRITE -> m.sprite(v[0], v[1], k, q.white(), q.tex(), q.family());
            case GUI -> m.gui(v[0], v[1], k, q.r(), q.g(), q.b(), q.a());
            case TEXT -> m.text(v[0], v[1], k, q.argb());
        };
    }

    @Test
    void vanillaLayoutMovesNothing() {
        Map<Element, Placement> p = LayoutEngine.place(HudSettings.vanilla());
        assertFalse(GlslConfig.active(p));
        for (Placement e : p.values()) {
            assertTrue(e.vanilla(), e.element().id());
        }
        for (int[] size : SIZES) {
            for (VanillaHud.Scenario sc : VanillaHud.Scenario.all()) {
                VanillaHud hud = VanillaHud.build(size[0], size[1], sc);
                ShaderMirror m = new ShaderMirror(p, size[0], size[1]);
                for (VanillaHud.Quad q : hud.quads) {
                    for (int k = 0; k < 4; k++) {
                        assertEquals(ShaderMirror.Move.NONE, move(m, q, k), q.tag());
                    }
                }
            }
        }
    }

    @Test
    void explicitVanillaCoordinatesAreStillVanilla() {
        Map<Element, Placement> p = yaml("hud:\n  hotbar:\n    anchor: bottom-center\n    x: 0\n    y: 0\n    layout: row\n"
                + "  hearts:\n    x: -51\n    y: -30\n    gap: 0\n  food:\n    direction: left\n  xp:\n    rotate: 0\n    level: true\n");
        assertFalse(GlslConfig.active(p));
    }

    @Test
    void presetsMoveEveryPartTogetherAndLandOnTheirLayout() {
        List<String> names = new ArrayList<>(Presets.BUILT_IN);
        List<Map<Element, Placement>> layouts = new ArrayList<>();
        for (String n : names) {
            layouts.add(preset(n));
        }
        names.add("custom-slots");
        layouts.add(yaml("hud:\n  hotbar:\n    layout: grid\n    per-line: 4\n    gap: 3\n    line-gap: 5\n    reverse: true\n"
                + "    slots:\n      '1':\n        x: 4\n        y: -30\n      '6':\n        hidden: true\n    attack-indicator: false\n"
                + "  offhand:\n    enabled: false\n  hearts:\n    layout: column\n    per-line: 5\n    gap: 2\n"
                + "  xp:\n    rotate: 90\n    anchor: center-right\n    x: -4\n    level: false\n  item-name:\n    anchor: top-left\n    x: 10\n    y: 40\n"));
        names.add("hidden-hotbar");
        layouts.add(yaml("hud:\n  hotbar:\n    enabled: false\n  food:\n    enabled: false\n  air:\n    anchor: top-right\n    x: -3\n    y: 3\n    reverse: true\n"
                + "  mount:\n    layout: grid\n    per-line: 6\n  xp:\n    rotate: 180\n"));
        for (int n = 0; n < names.size(); n++) {
            Map<Element, Placement> p = layouts.get(n);
            for (int[] size : SIZES) {
                for (VanillaHud.Scenario sc : VanillaHud.Scenario.all()) {
                    check(names.get(n), p, VanillaHud.build(size[0], size[1], sc));
                }
            }
        }
    }

    private static void check(String name, Map<Element, Placement> p, VanillaHud hud) {
        ShaderMirror m = new ShaderMirror(p, hud.w, hud.h);
        for (VanillaHud.Quad q : hud.quads) {
            String where = name + " " + hud.w + "x" + hud.h + " " + q.tag();
            ShaderMirror.Move[] mv = new ShaderMirror.Move[4];
            for (int k = 0; k < 4; k++) {
                mv[k] = move(m, q, k);
            }
            String[] t = q.tag().split(":");
            Element e = element(t[0]);
            Placement pe = p.get(e);
            boolean hidden = pe.mode() == Placement.Mode.HIDDEN;
            if (t[0].equals("slot") || t[0].equals("attack")) {
                int i = t[0].equals("attack") ? 0 : Integer.parseInt(t[1]);
                hidden = pe.mode() == Placement.Mode.HIDDEN || (pe.mode() == Placement.Mode.MOVED && (pe.geometry().hiddenMask() >> i & 1) != 0);
                if (t[0].equals("attack")) {
                    hidden = pe.mode() == Placement.Mode.HIDDEN || !pe.geometry().attack();
                }
            }
            if (t[0].equals("level")) {
                hidden = hidden || (pe.mode() != Placement.Mode.VANILLA && !pe.geometry().level());
            }
            for (int k = 0; k < 4; k++) {
                assertEquals(hidden, mv[k].act() == ShaderMirror.HIDDEN, where + " hidden k" + k);
            }
            if (hidden) {
                continue;
            }
            if (pe.mode() == Placement.Mode.VANILLA && !(t[0].equals("offhand") && pe.geometry().follow())) {
                for (int k = 0; k < 4; k++) {
                    double[] r = mv[k].apply(q.v()[k][0], q.v()[k][1]);
                    assertEquals(q.v()[k][0], r[0], 1e-9, where);
                    assertEquals(q.v()[k][1], r[1], 1e-9, where);
                }
                continue;
            }
            if (t[0].equals("hotbar")) {
                Geometry g = pe.geometry();
                Point tl = pe.topLeft(hud.w, hud.h);
                double[][] c = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
                for (int k = 0; k < 4; k++) {
                    double[] r = mv[k].apply(q.v()[k][0], q.v()[k][1]);
                    double ex = g.slice() ? tl.x() + g.box().x() + c[k][0] * g.boxSize().x() : q.v()[k][0] + tl.x() + g.positions().get(0).x() - (hud.cx - 91);
                    double ey = g.slice() ? tl.y() + g.box().y() + c[k][1] * g.boxSize().y() : q.v()[k][1] + tl.y() + g.positions().get(0).y() - (hud.h - 22);
                    assertEquals(ex, r[0], 1e-6, where + " k" + k);
                    assertEquals(ey, r[1], 1e-6, where + " k" + k);
                }
                continue;
            }
            if (t[0].equals("xp")) {
                Point tl = pe.topLeft(hud.w, hud.h);
                int rot = pe.geometry().rotate();
                for (int k = 0; k < 4; k++) {
                    double lx = q.v()[k][0] - (hud.cx - 91);
                    double ly = q.v()[k][1] - (hud.h - 29);
                    double[] l = rot == 90 ? new double[]{5 - ly, lx} : rot == 180 ? new double[]{182 - lx, 5 - ly}
                            : rot == 270 ? new double[]{ly, 182 - lx} : new double[]{lx, ly};
                    double[] r = mv[k].apply(q.v()[k][0], q.v()[k][1]);
                    assertEquals(tl.x() + l[0], r[0], 1e-6, where);
                    assertEquals(tl.y() + l[1], r[1], 1e-6, where);
                }
                continue;
            }
            for (int k = 1; k < 4; k++) {
                assertEquals(mv[0].dx(), mv[k].dx(), 1e-6, where + " rigid x k" + k);
                assertEquals(mv[0].dy(), mv[k].dy(), 1e-6, where + " rigid y k" + k);
            }
            double[] exp = expected(p, hud, q, t);
            assertEquals(exp[0], mv[0].dx(), 1e-6, where + " dx");
            assertEquals(exp[1], mv[0].dy(), 1e-6, where + " dy");
        }
    }

    private static Element element(String tag) {
        return switch (tag) {
            case "slot", "attack", "hotbar" -> Element.HOTBAR;
            case "offhand" -> Element.OFFHAND;
            case "heart" -> Element.HEARTS;
            case "armor" -> Element.ARMOR;
            case "food" -> Element.FOOD;
            case "air" -> Element.AIR;
            case "mount" -> Element.MOUNT;
            case "xp", "level" -> Element.XP;
            case "name" -> Element.ITEM_NAME;
            default -> throw new IllegalArgumentException(tag);
        };
    }

    private static double[] slotDelta(Map<Element, Placement> p, VanillaHud hud, int i) {
        Placement hb = p.get(Element.HOTBAR);
        Point tl = hb.topLeft(hud.w, hud.h);
        Point pos = hb.geometry().positions().get(i);
        return new double[]{tl.x() + pos.x() - (hud.cx - 91 + 20 * i), tl.y() + pos.y() - (hud.h - 22)};
    }

    private static double[] edgeDelta(Map<Element, Placement> p, VanillaHud hud, boolean right) {
        Placement hb = p.get(Element.HOTBAR);
        Point tl = hb.topLeft(hud.w, hud.h);
        Point pos = hb.geometry().positions().get(LayoutEngine.edgeSlot(hb.geometry(), right));
        return new double[]{tl.x() + pos.x() - (hud.cx - 91 + (right ? 160 : 0)), tl.y() + pos.y() - (hud.h - 22)};
    }

    private static double[] iconDelta(Map<Element, Placement> p, VanillaHud hud, Element e, int idx, double ox, double oy, double ex, double ey) {
        Placement pe = p.get(e);
        Point tl = pe.topLeft(hud.w, hud.h);
        Point pos = pe.geometry().positions().get(idx);
        return new double[]{tl.x() + pos.x() + ex - ox, tl.y() + pos.y() + ey - oy};
    }

    private static double[] expected(Map<Element, Placement> p, VanillaHud hud, VanillaHud.Quad q, String[] t) {
        int cx = hud.cx;
        int h = hud.h;
        double ox = q.v()[0][0];
        double oy = q.v()[0][1];
        switch (t[0]) {
            case "slot" -> {
                return slotDelta(p, hud, Integer.parseInt(t[1]));
            }
            case "attack" -> {
                Placement hb = p.get(Element.HOTBAR);
                double[] d = edgeDelta(p, hud, ox >= cx);
                return new double[]{d[0] + hb.geometry().attackOffset().x(), d[1] + hb.geometry().attackOffset().y()};
            }
            case "offhand" -> {
                boolean right = ox > cx;
                Placement off = p.get(Element.OFFHAND);
                if (off.geometry().follow()) {
                    double[] d = edgeDelta(p, hud, right);
                    Point o = LayoutEngine.followOffset(off);
                    return new double[]{d[0] + o.x(), d[1] + o.y()};
                }
                Point tl = off.topLeft(hud.w, hud.h);
                return new double[]{tl.x() - (right ? cx + 91 : cx - 120), tl.y() - (h - 23)};
            }
            case "heart" -> {
                return iconDelta(p, hud, Element.HEARTS, Integer.parseInt(t[1]), ox, oy, 0, Integer.parseInt(t[2]));
            }
            case "armor" -> {
                Point st = p.get(Element.HEARTS).geometry().lineStep();
                double rows = Integer.parseInt(t[2]) / 10.0;
                return iconDelta(p, hud, Element.ARMOR, Integer.parseInt(t[1]), ox, oy, Math.floor(rows * st.x() + 0.5), Math.floor(rows * st.y() + 0.5));
            }
            case "food" -> {
                return iconDelta(p, hud, Element.FOOD, Integer.parseInt(t[1]), ox, oy, 0, Integer.parseInt(t[2]));
            }
            case "air" -> {
                Point st = p.get(Element.MOUNT).geometry().lineStep();
                int rows = Integer.parseInt(t[3]);
                return iconDelta(p, hud, Element.AIR, Integer.parseInt(t[1]), ox, oy, rows * st.x(), Integer.parseInt(t[2]) + rows * st.y());
            }
            case "mount" -> {
                return iconDelta(p, hud, Element.MOUNT, Integer.parseInt(t[1]), ox, oy, 0, 0);
            }
            case "level" -> {
                Placement xp = p.get(Element.XP);
                Point tl = xp.topLeft(hud.w, hud.h);
                Geometry g = xp.geometry();
                return new double[]{tl.x() + g.width() / 2 - cx + g.levelOffset().x(), tl.y() + g.height() / 2 - (h - 27) + g.levelOffset().y()};
            }
            case "name" -> {
                Point tl = p.get(Element.ITEM_NAME).topLeft(hud.w, hud.h);
                return new double[]{tl.x() - cx, tl.y() - (h - 59)};
            }
            default -> throw new IllegalArgumentException(t[0]);
        }
    }

    @Test
    void openContainersStayInPlace() {
        List<String> bad = new ArrayList<>();
        for (String n : Presets.BUILT_IN) {
            Map<Element, Placement> p = preset(n);
            for (int w : new int[]{320, 427, 481}) {
                for (int h = 200; h <= 520; h++) {
                    for (int rows : new int[]{-1, 3, 6}) {
                        int slots = rows < 0 ? 46 : rows * 9 + 36;
                        for (int hover : new int[]{-1, slots - 9, slots - 5, slots - 1, 0}) {
                            ShaderMirror m = new ShaderMirror(p, w, h);
                            for (VanillaHud.Quad q : VanillaHud.container(w, h, rows, hover).quads) {
                                if (overHotbar(q, h)) {
                                    continue;
                                }
                                for (int k = 0; k < 4; k++) {
                                    if (!ShaderMirror.Move.NONE.equals(move(m, q, k))) {
                                        bad.add(n + " " + w + "x" + h + " rows " + rows + " hover " + hover + " " + q.tag() + " k" + k);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), bad.subList(0, Math.min(20, bad.size())), bad.size() + " moved");
    }

    private static boolean overHotbar(VanillaHud.Quad q, int h) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double[] v : q.v()) {
            lo = Math.min(lo, v[1]);
            hi = Math.max(hi, v[1]);
        }
        return hi - lo <= 16 && hi > h - 25;
    }

    @Test
    void containerCountsMoveOnlyWithAMovedItemName() {
        assertEquals(List.of("-1@251", "3@251", "-1@252", "3@252", "-1@253", "3@253", "-1@254", "3@254", "-1@255", "3@255", "-1@256", "3@256",
                "6@277", "6@278", "6@279", "6@280", "6@281", "6@282", "6@305", "6@306", "6@307", "6@308", "6@309", "6@310"), countHits(preset("left-column")));
        assertEquals(List.of(), countHits(yaml("hud:\n  hotbar:\n    layout: column\n    anchor: center-left\n  hearts:\n    anchor: top-left\n")));
    }

    private static List<String> countHits(Map<Element, Placement> p) {
        List<String> bad = new ArrayList<>();
        for (int h = 240; h <= 520; h++) {
            for (int rows : new int[]{-1, 3, 6}) {
                ShaderMirror m = new ShaderMirror(p, 427, h);
                for (VanillaHud.Quad q : VanillaHud.counts(427, h, rows).quads) {
                    if (!ShaderMirror.Move.NONE.equals(move(m, q, 0)) && !bad.contains(rows + "@" + h)) {
                        bad.add(rows + "@" + h);
                    }
                }
            }
        }
        return bad;
    }

    @Test
    void nothingOutsideTheHudMoves() {
        List<Map<Element, Placement>> layouts = new ArrayList<>();
        for (String n : Presets.BUILT_IN) {
            layouts.add(preset(n));
        }
        for (Map<Element, Placement> p : layouts) {
            for (int[] size : SIZES) {
                VanillaHud hud = VanillaHud.screens(size[0], size[1]);
                ShaderMirror m = new ShaderMirror(p, size[0], size[1]);
                for (VanillaHud.Quad q : hud.quads) {
                    for (int k = 0; k < 4; k++) {
                        assertEquals(ShaderMirror.Move.NONE, move(m, q, k), q.tag() + " " + size[0] + "x" + size[1]);
                    }
                }
            }
        }
    }
}
