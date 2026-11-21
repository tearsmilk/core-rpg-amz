package dev.tearsmilk.aurahud.layout;

import dev.tearsmilk.aurahud.layout.Placement.Geometry;
import dev.tearsmilk.aurahud.layout.Placement.Point;

import java.util.List;
import java.util.Map;

public final class GlslConfig {

    public static final String PATH = "aurahud/shaders/include/ah_config.glsl";

    private GlslConfig() {
    }

    public static boolean active(Map<Element, Placement> placements) {
        for (Placement p : placements.values()) {
            if (!p.vanilla()) {
                return true;
            }
        }
        return false;
    }

    public static String generate(Map<Element, Placement> placements) {
        return generate(placements, NameGlyphs.DEFAULT);
    }

    public static String generate(Map<Element, Placement> placements, List<Point> glyphs) {
        StringBuilder b = new StringBuilder("#version 330\n\n");
        b.append("#define AH_ACTIVE ").append(active(placements) ? 1 : 0).append('\n');
        for (Element e : Element.values()) {
            Placement p = placements.get(e);
            Geometry g = p.geometry();
            String n = "AH_" + e.glsl();
            b.append('\n');
            define(b, n, p.mode().code());
            switch (e) {
                case HOTBAR -> {
                    define(b, n + "_SLICE", g.slice() ? 1 : 0);
                    define(b, n + "_HIDDEN", g.hiddenMask());
                    define(b, n + "_ATTACK", g.attack() ? 1 : 0);
                    define(b, n + "_EDGE_L", LayoutEngine.edgeSlot(g, false));
                    define(b, n + "_EDGE_R", LayoutEngine.edgeSlot(g, true));
                }
                case OFFHAND -> define(b, n + "_FOLLOW", g.follow() ? 1 : 0);
                case ITEM_NAME -> define(b, n + "_GLYPH_COUNT", glyphs.size());
                case XP -> {
                    define(b, n + "_ROTATE", g.rotate() / 90);
                    define(b, n + "_LEVEL", g.level() ? 1 : 0);
                }
                default -> {
                }
            }
            b.append("const vec2 ").append(n).append("_ANCHOR = vec2(").append(fraction(g.anchor().fx())).append(", ")
                    .append(fraction(g.anchor().fy())).append(");\n");
            vec(b, n + "_BASE", g.base());
            switch (e) {
                case HOTBAR -> {
                    vec(b, n + "_BOX", g.box());
                    vec(b, n + "_BOX_SIZE", g.boxSize());
                    vec(b, n + "_ATTACK_OFFSET", g.attackOffset());
                }
                case ARMOR -> vec(b, n + "_ROW_STEP", placements.get(Element.HEARTS).geometry().lineStep());
                case AIR -> vec(b, n + "_ROW_STEP", placements.get(Element.MOUNT).geometry().lineStep());
                case XP -> {
                    vec(b, n + "_SIZE", new Point(g.width(), g.height()));
                    vec(b, n + "_LEVEL_OFFSET", g.levelOffset());
                }
                case OFFHAND -> vec(b, n + "_FOLLOW_OFFSET", g.follow() ? LayoutEngine.followOffset(p) : new Point(0, 0));
                case ITEM_NAME -> array(b, n + "_GLYPHS", glyphs);
                default -> {
                }
            }
            if (e.shape() == Element.Shape.ICONS) {
                array(b, n + "_POS", g.positions());
            }
        }
        return b.toString();
    }

    private static void define(StringBuilder b, String name, int value) {
        b.append("#define ").append(name).append(' ').append(value).append('\n');
    }

    private static void vec(StringBuilder b, String name, Point p) {
        b.append("const vec2 ").append(name).append(" = ").append(v(p)).append(";\n");
    }

    private static void array(StringBuilder b, String name, List<Point> list) {
        b.append("const vec2 ").append(name).append('[').append(list.size()).append("] = vec2[").append(list.size()).append("](");
        for (int i = 0; i < list.size(); i++) {
            b.append(i % 5 == 0 ? "\n    " : " ").append(v(list.get(i))).append(i + 1 < list.size() ? "," : "");
        }
        b.append("\n);\n");
    }

    private static String v(Point p) {
        return "vec2(" + p.x() + ".0, " + p.y() + ".0)";
    }

    private static String fraction(float f) {
        return f == 0f ? "0.0" : f == 1f ? "1.0" : "0.5";
    }
}
