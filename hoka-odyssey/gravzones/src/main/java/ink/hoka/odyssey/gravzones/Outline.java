package ink.hoka.odyssey.gravzones;

import java.util.ArrayList;
import java.util.List;

/**
 * Точки частиц по рёбрам параллелепипедов (без Minestom — для тестов). Рисуются только части рёбер ближе radius
 * к игроку; на все коробки вместе — не больше budget точек (шаг растёт, если рёбер много), но не чаще minStep.
 */
final class Outline {
    /** Коробка в координатах мира [x0..x1]×[y0..y1]×[z0..z1] и её цвет (0xRRGGBB). */
    record Box(double x0, double y0, double z0, double x1, double y1, double z1, int color) {
        /** Коробка из блоков min..max включительно. */
        static Box blocks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int color) {
            return new Box(Math.min(minX, maxX), Math.min(minY, maxY), Math.min(minZ, maxZ),
                Math.max(minX, maxX) + 1, Math.max(minY, maxY) + 1, Math.max(minZ, maxZ) + 1, color);
        }
    }

    record Point(double x, double y, double z, int color) {
    }

    private record Segment(double ax, double ay, double az, double bx, double by, double bz, int color) {
        double length() {
            return Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        }
    }

    private Outline() {
    }

    static List<Point> points(List<Box> boxes, double px, double py, double pz, double radius, int budget, double minStep) {
        List<Segment> segs = new ArrayList<>();
        double total = 0;
        for (Box b : boxes) {
            double[][] c = {
                {b.x0, b.y0, b.z0, b.x1, b.y0, b.z0}, {b.x0, b.y1, b.z0, b.x1, b.y1, b.z0},
                {b.x0, b.y0, b.z1, b.x1, b.y0, b.z1}, {b.x0, b.y1, b.z1, b.x1, b.y1, b.z1},
                {b.x0, b.y0, b.z0, b.x0, b.y1, b.z0}, {b.x1, b.y0, b.z0, b.x1, b.y1, b.z0},
                {b.x0, b.y0, b.z1, b.x0, b.y1, b.z1}, {b.x1, b.y0, b.z1, b.x1, b.y1, b.z1},
                {b.x0, b.y0, b.z0, b.x0, b.y0, b.z1}, {b.x1, b.y0, b.z0, b.x1, b.y0, b.z1},
                {b.x0, b.y1, b.z0, b.x0, b.y1, b.z1}, {b.x1, b.y1, b.z0, b.x1, b.y1, b.z1},
            };
            for (double[] e : c) {
                Segment s = clip(e, px, py, pz, radius, b.color);
                if (s != null) {
                    segs.add(s);
                    total += s.length();
                }
            }
        }
        List<Point> out = new ArrayList<>();
        if (segs.isEmpty() || budget <= 0) {
            return out;
        }
        double step = Math.max(minStep, total / budget);
        for (Segment s : segs) {
            double len = s.length();
            int n = Math.max(1, (int) Math.floor(len / step) + 1);
            for (int i = 0; i < n && out.size() < budget; i++) {
                double t = n == 1 ? 0.5 : (double) i / (n - 1);
                out.add(new Point(s.ax + (s.bx - s.ax) * t, s.ay + (s.by - s.ay) * t, s.az + (s.bz - s.az) * t, s.color));
            }
        }
        return out;
    }

    /** Часть отрезка внутри шара (или null). */
    private static Segment clip(double[] e, double px, double py, double pz, double r, int color) {
        double dx = e[3] - e[0];
        double dy = e[4] - e[1];
        double dz = e[5] - e[2];
        double fx = e[0] - px;
        double fy = e[1] - py;
        double fz = e[2] - pz;
        double a = dx * dx + dy * dy + dz * dz;
        double b = 2 * (fx * dx + fy * dy + fz * dz);
        double c = fx * fx + fy * fy + fz * fz - r * r;
        if (a == 0) {
            return null;
        }
        double disc = b * b - 4 * a * c;
        if (disc < 0) {
            return null;
        }
        double sq = Math.sqrt(disc);
        double t0 = Math.max(0, (-b - sq) / (2 * a));
        double t1 = Math.min(1, (-b + sq) / (2 * a));
        if (t0 >= t1) {
            return null;
        }
        return new Segment(e[0] + dx * t0, e[1] + dy * t0, e[2] + dz * t0, e[0] + dx * t1, e[1] + dy * t1, e[2] + dz * t1, color);
    }
}
