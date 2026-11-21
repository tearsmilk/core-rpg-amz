package dev.tearsmilk.aurahud.layout;

import dev.tearsmilk.aurahud.layout.Placement.Geometry;
import dev.tearsmilk.aurahud.layout.Placement.Point;

import java.util.List;
import java.util.Map;

final class ShaderMirror {

    static final int VANILLA = 0;
    static final int MOVED = 1;
    static final int HIDDEN = 2;

    record Move(int act, double dx, double dy) {

        static final Move NONE = new Move(VANILLA, 0, 0);

        double[] apply(double x, double y) {
            if (act == HIDDEN) {
                return null;
            }
            return act == MOVED ? new double[]{x + dx, y + dy} : new double[]{x, y};
        }
    }

    static final int TEX_GUI = 0;
    static final int TEX_ITEMS = 1;
    static final int TEX_256 = 2;
    static final int TEX_OTHER = 3;

    private final Map<Element, Placement> p;
    private final List<Point> glyphs;
    private final boolean active;
    private final double w;
    private final double h;
    private final double cx;

    ShaderMirror(Map<Element, Placement> placements, int w, int h) {
        this(placements, w, h, NameGlyphs.DEFAULT);
    }

    ShaderMirror(Map<Element, Placement> placements, int w, int h, List<Point> glyphs) {
        this.p = placements;
        this.glyphs = glyphs;
        this.active = GlslConfig.active(placements);
        this.w = w;
        this.h = h;
        this.cx = Math.floor(w * 0.5);
    }

    private int mode(Element e) {
        return p.get(e).mode().code();
    }

    private Geometry g(Element e) {
        return p.get(e).geometry();
    }

    static boolean eq(double a, double b) {
        return Math.abs(a - b) < 0.01;
    }

    static boolean in(double v, double lo, double hi) {
        return v > lo - 0.01 && v < hi + 0.01;
    }

    static int index(double v, int count) {
        double r = Math.rint(v);
        return Math.abs(v - r) < 0.01 && r > -0.5 && r < count - 0.5 ? (int) r : -1;
    }

    static double[] blitCorner(int k) {
        return new double[]{k >= 2 ? 1 : 0, (k == 1 || k == 2) ? 1 : 0};
    }

    static double[] fillCorner(int k) {
        return new double[]{k <= 1 ? 1 : 0, (k == 0 || k == 3) ? 1 : 0};
    }

    private double[] anchor(Anchor a) {
        double fx = a.fx();
        double fy = a.fy();
        return new double[]{fx < 0.25 ? 0 : fx < 0.75 ? Math.floor(w * 0.5) : w, fy < 0.25 ? 0 : fy < 0.75 ? Math.floor(h * 0.5) : h};
    }

    double[] tl(Element e) {
        double[] a = anchor(g(e).anchor());
        return new double[]{a[0] + g(e).base().x(), a[1] + g(e).base().y()};
    }

    private double[] pos(Element e, int i) {
        Point q = g(e).positions().get(i);
        double[] t = tl(e);
        return new double[]{t[0] + q.x(), t[1] + q.y()};
    }

    double[] slotDelta(int i) {
        double[] t = pos(Element.HOTBAR, i);
        return new double[]{t[0] - (cx - 91 + 20 * i), t[1] - (h - 22)};
    }

    int slotAct(int i) {
        int m = mode(Element.HOTBAR);
        if (m == HIDDEN) {
            return HIDDEN;
        }
        if (m == MOVED) {
            return (g(Element.HOTBAR).hiddenMask() >> i & 1) != 0 ? HIDDEN : MOVED;
        }
        return VANILLA;
    }

    int edge(boolean right) {
        return LayoutEngine.edgeSlot(g(Element.HOTBAR), right);
    }

    double[] edgeDelta(boolean right) {
        double[] t = pos(Element.HOTBAR, edge(right));
        return new double[]{t[0] - (cx - 91 + (right ? 160 : 0)), t[1] - (h - 22)};
    }

    double[] offhandDelta(int side) {
        if (g(Element.OFFHAND).follow()) {
            double[] d = edgeDelta(side != 0);
            Point o = LayoutEngine.followOffset(p.get(Element.OFFHAND));
            return new double[]{d[0] + o.x(), d[1] + o.y()};
        }
        double[] t = tl(Element.OFFHAND);
        return new double[]{t[0] - (side == 0 ? cx - 120 : cx + 91), t[1] - (h - 23)};
    }

    Move slotMove(int slot) {
        if (slot < 9) {
            double[] d = slotDelta(slot);
            return new Move(slotAct(slot), d[0], d[1]);
        }
        double[] d = offhandDelta(slot - 9);
        return new Move(mode(Element.OFFHAND), d[0], d[1]);
    }

    int slotAt(double x, double lo, double hi) {
        double r = x - (cx - 88);
        double i = Math.rint((r - 0.5 * (lo + hi)) / 20.0);
        if (i > -0.5 && i < 8.5 && in(r - 20 * i, lo, hi)) {
            return (int) i;
        }
        if (in(x - (cx - 117), lo, hi)) {
            return 9;
        }
        if (in(x - (cx + 101), lo, hi)) {
            return 10;
        }
        return -1;
    }

    double[] xpPlace(double x, double y) {
        double lx = x - (cx - 91);
        double ly = y - (h - 29);
        double[] l = switch (g(Element.XP).rotate() / 90) {
            case 1 -> new double[]{5 - ly, lx};
            case 2 -> new double[]{182 - lx, 5 - ly};
            case 3 -> new double[]{ly, 182 - lx};
            default -> new double[]{lx, ly};
        };
        double[] t = tl(Element.XP);
        return new double[]{t[0] + l[0], t[1] + l[1]};
    }

    double[] levelDelta() {
        double[] t = tl(Element.XP);
        Geometry x = g(Element.XP);
        return new double[]{t[0] + Math.floor(x.width() * 0.5) - cx + x.levelOffset().x(),
                t[1] + Math.floor(x.height() * 0.5) - (h - 27) + x.levelOffset().y()};
    }

    double[] nameDelta() {
        double[] t = tl(Element.ITEM_NAME);
        return new double[]{t[0] - cx, t[1] - (h - 59)};
    }

    Move sprite(double x, double y, int k, boolean white, int tex, int family) {
        if (!active) {
            return Move.NONE;
        }
        double[] c = blitCorner(k);
        if (white) {
            Move m = attack(x, y, c);
            if (m != null) {
                return m;
            }
        }
        boolean items = white && tex == TEX_ITEMS;
        if (items) {
            double ry = y - (h - 19);
            boolean yok = c[1] < 0.5 ? in(ry, -6, 0) : in(ry, 16, 18);
            if (yok) {
                int slot = c[0] < 0.5 ? slotAt(x, 0, 4) : slotAt(x, 12, 16);
                if (slot >= 0) {
                    return slotMove(slot);
                }
            }
        }
        if (!items && family > 0) {
            return marked(family, x, y, c);
        }
        if (white) {
            Move m = hotbar(x, y, c, items);
            if (m != null) {
                return m;
            }
        }
        boolean guiTex = tex == TEX_GUI || (tex == TEX_ITEMS && !white);
        if (!items && guiTex && xpMember(x, y, c, white) && mode(Element.XP) != 0) {
            double[] n = xpPlace(x, y);
            return new Move(mode(Element.XP), n[0] - x, n[1] - y);
        }
        return Move.NONE;
    }

    private Move attack(double x, double y, double[] c) {
        double ax = x - c[0] * 18;
        boolean right = eq(ax, cx + 97);
        if ((right || eq(ax, cx - 113)) && (c[1] > 0.5 ? eq(y, h - 2) : in(y, h - 20, h - 2))) {
            Geometry hb = g(Element.HOTBAR);
            int act = mode(Element.HOTBAR) == HIDDEN || !hb.attack() ? HIDDEN : mode(Element.HOTBAR);
            double[] d = edgeDelta(right);
            return new Move(act, d[0] + hb.attackOffset().x(), d[1] + hb.attackOffset().y());
        }
        return null;
    }

    private Move hotbar(double x, double y, double[] c, boolean items) {
        double ox = x - c[0] * 182;
        double oy = y - c[1] * 22;
        if (eq(ox, cx - 91) && eq(oy, h - 22)) {
            Geometry hb = g(Element.HOTBAR);
            if (hb.slice()) {
                double[] t = tl(Element.HOTBAR);
                double kx = hb.box().x() + c[0] * hb.boxSize().x();
                double ky = hb.box().y() + c[1] * hb.boxSize().y();
                return new Move(mode(Element.HOTBAR), t[0] + kx - x, t[1] + ky - y);
            }
            double[] d = slotDelta(0);
            return new Move(mode(Element.HOTBAR), d[0], d[1]);
        }
        ox = x - c[0] * 24;
        oy = y - c[1] * 23;
        if (!items && eq(oy, h - 23)) {
            int i = index((ox - (cx - 92)) / 20.0, 9);
            if (i >= 0) {
                double[] d = slotDelta(i);
                return new Move(slotAct(i), d[0], d[1]);
            }
        }
        ox = x - c[0] * 29;
        oy = y - c[1] * 24;
        if (eq(oy, h - 23) && (eq(ox, cx - 120) || eq(ox, cx + 91))) {
            double[] d = offhandDelta(eq(ox, cx - 120) ? 0 : 1);
            return new Move(mode(Element.OFFHAND), d[0], d[1]);
        }
        return null;
    }

    private Move marked(int fam, double x, double y, double[] c) {
        double ox = x - c[0] * 9;
        double oy = y - c[1] * 9;
        switch (fam) {
            case 1 -> {
                if (mode(Element.HEARTS) == 0) {
                    return Move.NONE;
                }
                int col = index((ox - (cx - 91)) / 8.0, 10);
                if (col >= 0) {
                    double yrel = oy - (h - 39);
                    int row = Math.max(0, Math.min(3, (int) Math.rint(-yrel / 10.0)));
                    double[] t = pos(Element.HEARTS, row * 10 + col);
                    return new Move(mode(Element.HEARTS), t[0] - ox, t[1] + yrel + 10 * row - oy);
                }
            }
            case 2 -> {
                if (mode(Element.ARMOR) == 0) {
                    return Move.NONE;
                }
                int j = index((ox - (cx - 91)) / 8.0, 10);
                if (j >= 0) {
                    double rows = -(oy - (h - 49)) / 10.0;
                    Point st = g(Element.HEARTS).lineStep();
                    double[] t = pos(Element.ARMOR, j);
                    return new Move(mode(Element.ARMOR), t[0] + Math.floor(rows * st.x() + 0.5) - ox, t[1] + Math.floor(rows * st.y() + 0.5) - oy);
                }
            }
            case 3 -> {
                if (mode(Element.FOOD) == 0) {
                    return Move.NONE;
                }
                int j = index((cx + 82 - ox) / 8.0, 10);
                double jit = oy - (h - 39);
                if (j >= 0 && in(jit, -1, 1)) {
                    double[] t = pos(Element.FOOD, j);
                    return new Move(mode(Element.FOOD), t[0] - ox, t[1] + jit - oy);
                }
            }
            case 4 -> {
                if (mode(Element.AIR) == 0) {
                    return Move.NONE;
                }
                int j = index((cx + 82 - ox) / 8.0, 10);
                double yrel = oy - (h - 49);
                double rows = Math.rint(yrel / 10.0);
                double jit = yrel - 10 * rows;
                if (j >= 0 && in(jit, 0, 1)) {
                    Point st = g(Element.MOUNT).lineStep();
                    double[] t = pos(Element.AIR, j);
                    return new Move(mode(Element.AIR), t[0] - rows * st.x() - ox, t[1] + jit - rows * st.y() - oy);
                }
            }
            case 5 -> {
                if (mode(Element.MOUNT) == 0) {
                    return Move.NONE;
                }
                int j = index((cx + 82 - ox) / 8.0, 10);
                int row = index((h - 39 - oy) / 10.0, 3);
                if (j >= 0 && row >= 0) {
                    double[] t = pos(Element.MOUNT, row * 10 + j);
                    return new Move(mode(Element.MOUNT), t[0] - ox, t[1] - oy);
                }
            }
            case 6 -> {
                if (mode(Element.XP) == 0) {
                    return Move.NONE;
                }
                double[] n = xpPlace(x, y);
                return new Move(mode(Element.XP), n[0] - x, n[1] - y);
            }
            default -> {
            }
        }
        return Move.NONE;
    }

    private boolean xpMember(double x, double y, double[] c, boolean white) {
        if (white && in(x, cx - 91, cx + 91) && eq(y, c[1] < 0.5 ? h - 29 : h - 24)) {
            return true;
        }
        if (!in(x, cx - 100, cx + 100)) {
            return false;
        }
        return c[1] < 0.5 ? (eq(y, h - 35) || eq(y, h - 23)) : (eq(y, h - 30) || eq(y, h - 18));
    }

    Move gui(double x, double y, int k, int r, int gr, int b, int a) {
        if (!active) {
            return Move.NONE;
        }
        double[] c = fillCorner(k);
        int slot = -1;
        if (r == 0 && gr == 0 && b == 0 && a == 255 && eq(y, c[1] < 0.5 ? h - 6 : h - 4)) {
            double sx = c[0] < 0.5 ? 2 : 15;
            slot = slotAt(x, sx, sx);
        }
        if (slot < 0 && a == 255 && eq(y, c[1] < 0.5 ? h - 6 : h - 5)) {
            slot = slotAt(x, 2, c[0] < 0.5 ? 2 : 15);
        }
        if (slot < 0 && r == 255 && gr == 255 && b == 255 && a == 127 && (c[1] < 0.5 ? in(y, h - 19, h - 3) : eq(y, h - 3))) {
            double sx = c[0] < 0.5 ? 0 : 16;
            slot = slotAt(x, sx, sx);
        }
        if (slot >= 0) {
            return slotMove(slot);
        }
        if (r == 0 && gr == 0 && b == 0 && a > 0 && a < 255 && (eq(y, c[1] < 0.5 ? h - 61 : h - 48) || eq(y, c[1] < 0.5 ? h - 47 : h - 34))
                && mode(Element.ITEM_NAME) != 0) {
            double[] d = nameDelta();
            return new Move(mode(Element.ITEM_NAME), d[0], d[1]);
        }
        return Move.NONE;
    }

    boolean nameEdge(double y, double cy) {
        for (Point g : glyphs) {
            double e = g.x() + cy * g.y();
            double a = y - (h - 59) - e;
            double b = y - (h - 45) - e;
            if (eq(a, 0) || eq(a, 1) || eq(b, 0) || eq(b, 1)) {
                return true;
            }
        }
        return false;
    }

    Move text(double x, double y, int k, int argb) {
        if (!active) {
            return Move.NONE;
        }
        double[] c = blitCorner(k);
        double top = y - c[1] * 8;
        if ((argb == 0xFFFFFFFF && eq(top, h - 10)) || (argb == 0xFF3F3F3F && eq(top, h - 9))) {
            int slot = slotAt(x, 5, 22);
            return slot >= 0 ? slotMove(slot) : Move.NONE;
        }
        if ((argb == 0xFF80FF20 || argb == 0xFF000000) && in(top, h - 36, h - 34) && in(x, cx - 60, cx + 60)) {
            if (mode(Element.XP) == 0) {
                return Move.NONE;
            }
            double[] d = levelDelta();
            return new Move(g(Element.XP).level() ? mode(Element.XP) : HIDDEN, d[0], d[1]);
        }
        if (nameEdge(y, c[1])) {
            if (mode(Element.ITEM_NAME) == 0) {
                return Move.NONE;
            }
            double[] d = nameDelta();
            return new Move(mode(Element.ITEM_NAME), d[0], d[1]);
        }
        return Move.NONE;
    }
}
