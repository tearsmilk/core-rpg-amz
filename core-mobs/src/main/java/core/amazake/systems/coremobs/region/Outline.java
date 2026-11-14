package core.amazake.systems.coremobs.region;

import core.amazake.systems.coremobs.geo.BlockPos;
import java.util.ArrayList;
import java.util.List;

/**
 * Контур региона в 3D: нижние рёбра идут по верху блоков, на которые ставили точки (между вершинами — по прямой), и
 * от каждой вершины вверх до потолка региона стоит столбик. Высота линии не зависит от того, где стоит игрок.
 */
public final class Outline {

    /** Над верхом блока, чтобы линия не тонула в траве и плитах. */
    public static final double LIFT = 0.03;

    /** Отрезок контура. {@code index} постоянен для одной и той же формы региона. */
    public record Segment(int index, double ax, double ay, double az, double bx, double by, double bz) {

        public double length() {
            return Math.sqrt((this.bx - this.ax) * (this.bx - this.ax) + (this.by - this.ay) * (this.by - this.ay)
                + (this.bz - this.az) * (this.bz - this.az));
        }
    }

    private Outline() {
    }

    /** Все отрезки: рёбра и столбики, каждый не длиннее {@code maxLength}. */
    public static List<Segment> segments(final Region region, final double maxLength, final boolean posts) {
        final List<Segment> out = new ArrayList<>();
        final List<BlockPos> v = region.polygon().vertices();
        final int n = v.size();
        for (int i = 0; i < n; i++) {
            final BlockPos a = v.get(i);
            final BlockPos b = v.get((i + 1) % n);
            split(out, a.x() + 0.5, a.y() + 1 + LIFT, a.z() + 0.5, b.x() + 0.5, b.y() + 1 + LIFT, b.z() + 0.5, maxLength);
        }
        if (posts) {
            for (final BlockPos p : v) {
                final double bottom = p.y() + 1 + LIFT;
                final double top = region.maxY() + 1.0;
                if (top - bottom > 0.2) {
                    split(out, p.x() + 0.5, bottom, p.z() + 0.5, p.x() + 0.5, top, p.z() + 0.5, maxLength);
                }
            }
        }
        return out;
    }

    private static void split(final List<Segment> out, final double ax, final double ay, final double az,
                              final double bx, final double by, final double bz, final double maxLength) {
        final double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        if (len < 1e-6) {
            return;
        }
        final int parts = Math.max(1, (int) Math.ceil(len / maxLength));
        for (int k = 0; k < parts; k++) {
            final double t0 = (double) k / parts;
            final double t1 = (double) (k + 1) / parts;
            out.add(new Segment(out.size(),
                ax + (bx - ax) * t0, ay + (by - ay) * t0, az + (bz - az) * t0,
                ax + (bx - ax) * t1, ay + (by - ay) * t1, az + (bz - az) * t1));
        }
    }
}
