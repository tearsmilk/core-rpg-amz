package dev.tearsmilk.aurahud.layout;

import dev.tearsmilk.aurahud.layout.Placement.Geometry;
import dev.tearsmilk.aurahud.layout.Placement.Mode;
import dev.tearsmilk.aurahud.layout.Placement.Point;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class LayoutEngine {

    public static final int HOTBAR_PIECE = 22;
    public static final int HOTBAR_PITCH = 20;

    private LayoutEngine() {
    }

    public static Map<Element, Placement> place(HudSettings settings) {
        Map<Element, Placement> out = new EnumMap<>(Element.class);
        for (Element e : Element.values()) {
            out.put(e, place(settings.get(e), out.get(Element.HOTBAR)));
        }
        return out;
    }

    public static Placement place(ElementSettings s, Placement hotbar) {
        Element e = s.element();
        Geometry g = geometry(s);
        Geometry v = geometry(ElementSettings.defaults(e));
        Mode mode;
        if (!s.enabled()) {
            mode = Mode.HIDDEN;
        } else if (e == Element.OFFHAND && s.follow()) {
            mode = (hotbar == null || sameGeometry(hotbar.geometry(), geometry(ElementSettings.defaults(Element.HOTBAR))))
                    && g.base().equals(v.base()) ? Mode.VANILLA : Mode.MOVED;
        } else if (e == Element.OFFHAND) {
            mode = Mode.MOVED;
        } else if (e == Element.HOTBAR && g.hiddenMask() == (1 << ElementSettings.SLOTS) - 1) {
            mode = Mode.HIDDEN;
        } else {
            mode = g.equals(v) ? Mode.VANILLA : Mode.MOVED;
        }
        return new Placement(e, mode, g);
    }

    public static int edgeSlot(Geometry hotbar, boolean right) {
        int best = -1;
        for (int i = 0; i < hotbar.positions().size(); i++) {
            if ((hotbar.hiddenMask() >> i & 1) != 0) {
                continue;
            }
            int x = hotbar.positions().get(i).x();
            if (best < 0 || (right ? x >= hotbar.positions().get(best).x() : x < hotbar.positions().get(best).x())) {
                best = i;
            }
        }
        return best < 0 ? (right ? ElementSettings.SLOTS - 1 : 0) : best;
    }

    public static Point followOffset(Placement offhand) {
        return offhand.geometry().base().minus(geometry(ElementSettings.defaults(Element.OFFHAND)).base());
    }

    private static boolean sameGeometry(Geometry a, Geometry b) {
        return a.anchor() == b.anchor() && a.base().equals(b.base()) && a.positions().equals(b.positions());
    }

    public static Geometry geometry(ElementSettings s) {
        Element e = s.element();
        Point offset = new Point(s.x(), s.y());
        return switch (e.shape()) {
            case ICONS -> icons(s, offset);
            case BOX -> box(s, offset, e.width(), e.height(), 0);
            case BAR -> box(s, offset, s.rotate() % 180 == 0 ? e.width() : e.height(), s.rotate() % 180 == 0 ? e.height() : e.width(), s.rotate());
            case POINT -> box(s, offset, 0, 0, 0);
        };
    }

    private static Geometry box(ElementSettings s, Point offset, int w, int h, int rotate) {
        Point base = offset.minus(new Point(s.anchor().x(w), s.anchor().y(h)));
        boolean xp = s.element() == Element.XP;
        return new Geometry(s.anchor(), base, w, h, List.of(), 0, false, new Point(0, 0), new Point(w, h),
                s.element() != Element.OFFHAND || s.follow(), true, new Point(0, 0), rotate, !xp || s.level(),
                xp ? new Point(s.levelX(), s.levelY()) : new Point(0, 0), new Point(0, 0));
    }

    private static Geometry icons(ElementSettings s, Point offset) {
        Element e = s.element();
        Element.Icons ic = e.icons();
        int per = Math.max(1, s.perLine());
        int pitch = ic.pitch() + s.gap();
        int linePitch = ic.linePitch() + s.lineGap();
        Direction d = s.direction();
        Direction w = s.wrap();
        List<Point> raw = new ArrayList<>();
        for (int i = 0; i < ic.count(); i++) {
            int line = i / per;
            int p = i % per;
            raw.add(new Point(d.dx() * p * pitch + w.dx() * line * linePitch, d.dy() * p * pitch + w.dy() * line * linePitch));
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < Math.min(ic.base(), raw.size()); i++) {
            Point p = raw.get(i);
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x() + ic.width());
            maxY = Math.max(maxY, p.y() + ic.height());
        }
        Point shift = new Point(-minX, -minY);
        List<Point> pos = new ArrayList<>();
        int hidden = 0;
        for (int i = 0; i < raw.size(); i++) {
            Point p = raw.get(i).plus(shift);
            ElementSettings.Slot slot = s.slots().get(i);
            if (slot != null) {
                p = p.plus(new Point(slot.x(), slot.y()));
                if (slot.hidden()) {
                    hidden |= 1 << i;
                }
            }
            pos.add(p);
        }
        int width = maxX - minX;
        int height = maxY - minY;
        Point base = offset.minus(new Point(s.anchor().x(width), s.anchor().y(height)));
        boolean slice = false;
        Point box = new Point(0, 0);
        Point boxSize = new Point(width, height);
        if (e == Element.HOTBAR) {
            for (int i = 0; i < pos.size(); i++) {
                slice |= !pos.get(i).equals(new Point(HOTBAR_PITCH * i, 0));
            }
            slice |= hidden != 0;
            int bx0 = Integer.MAX_VALUE;
            int by0 = Integer.MAX_VALUE;
            int bx1 = Integer.MIN_VALUE;
            int by1 = Integer.MIN_VALUE;
            for (int i = 0; i < pos.size(); i++) {
                if ((hidden >> i & 1) != 0) {
                    continue;
                }
                Point p = pos.get(i);
                bx0 = Math.min(bx0, p.x());
                by0 = Math.min(by0, p.y());
                bx1 = Math.max(bx1, p.x() + HOTBAR_PIECE);
                by1 = Math.max(by1, p.y() + HOTBAR_PIECE);
            }
            if (bx0 <= bx1) {
                box = new Point(bx0, by0);
                boxSize = new Point(bx1 - bx0, by1 - by0);
            }
        }
        return new Geometry(s.anchor(), base, width, height, pos, hidden, slice, box, boxSize, true,
                e != Element.HOTBAR || s.attack(), new Point(s.attackX(), s.attackY()), 0, true, new Point(0, 0),
                new Point(w.dx() * linePitch, w.dy() * linePitch));
    }
}
