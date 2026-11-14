package core.amazake.systems.coremobs.geo;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Полигон региона на плоскости X/Z. Вершины — блоки, которые выделили топориком; ребро соединяет центры этих блоков,
 * и блоки, через которые проходит ребро, входят в регион целиком (запас полблока). Неизменяемый, потокобезопасный.
 *
 * <p>Точек от 3 и больше; форма любая, в том числе невыпуклая. Самопересекающийся контур считается по правилу
 * чёт-нечет, как обычно в геометрии.</p>
 */
public final class Polygon {

    /** Запас вокруг рёбер: блок, через центр которого проходит граница, входит в регион. */
    public static final double EDGE = 0.5;

    private final List<BlockPos> vertices;
    private final double[] xs;
    private final double[] zs;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;

    public Polygon(final List<BlockPos> vertices) {
        if (vertices.size() < 3) {
            throw new IllegalArgumentException("нужно минимум 3 точки, а их " + vertices.size());
        }
        this.vertices = List.copyOf(vertices);
        final int n = vertices.size();
        this.xs = new double[n];
        this.zs = new double[n];
        int x0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int z0 = Integer.MAX_VALUE;
        int z1 = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            final BlockPos v = vertices.get(i);
            this.xs[i] = v.x() + 0.5;
            this.zs[i] = v.z() + 0.5;
            x0 = Math.min(x0, v.x());
            x1 = Math.max(x1, v.x());
            z0 = Math.min(z0, v.z());
            z1 = Math.max(z1, v.z());
        }
        this.minX = x0;
        this.maxX = x1;
        this.minZ = z0;
        this.maxZ = z1;
    }

    public List<BlockPos> vertices() {
        return this.vertices;
    }

    public int minX() {
        return this.minX;
    }

    public int maxX() {
        return this.maxX;
    }

    public int minZ() {
        return this.minZ;
    }

    public int maxZ() {
        return this.maxZ;
    }

    /** Точка внутри по правилу чёт-нечет (без запаса). */
    private boolean insideStrict(final double x, final double z) {
        boolean inside = false;
        final int n = this.xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double xi = this.xs[i];
            final double zi = this.zs[i];
            final double xj = this.xs[j];
            final double zj = this.zs[j];
            if ((zi > z) != (zj > z)) {
                final double cross = (xj - xi) * (z - zi) / (zj - zi) + xi;
                if (x < cross) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /** Расстояние от точки до ближайшего ребра контура. */
    public double distanceToEdge(final double x, final double z) {
        double best = Double.MAX_VALUE;
        final int n = this.xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            best = Math.min(best, segmentDistance(x, z, this.xs[j], this.zs[j], this.xs[i], this.zs[i]));
        }
        return best;
    }

    /** Точка (в координатах мира) внутри региона: внутри контура или не дальше полублока от ребра. */
    public boolean contains(final double x, final double z) {
        return this.insideStrict(x, z) || this.distanceToEdge(x, z) <= EDGE + 1e-9;
    }

    /** Блок внутри региона (по его центру). */
    public boolean containsBlock(final int x, final int z) {
        return this.contains(x + 0.5, z + 0.5);
    }

    /** На сколько блоков точка вышла за границу региона по горизонтали; 0 — внутри. */
    public double distanceOutside(final double x, final double z) {
        if (this.insideStrict(x, z)) {
            return 0.0;
        }
        return Math.max(0.0, this.distanceToEdge(x, z) - EDGE);
    }

    /** Площадь в блоках (число блоков внутри). Для больших регионов считается приблизительно по формуле площади. */
    public long blockArea() {
        final long box = (long) (this.maxX - this.minX + 1) * (this.maxZ - this.minZ + 1);
        if (box <= 40_000) {
            long count = 0;
            for (int x = this.minX; x <= this.maxX; x++) {
                for (int z = this.minZ; z <= this.maxZ; z++) {
                    if (this.containsBlock(x, z)) {
                        count++;
                    }
                }
            }
            return count;
        }
        double sum = 0;
        final int n = this.xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            sum += this.xs[j] * this.zs[i] - this.xs[i] * this.zs[j];
        }
        return Math.round(Math.abs(sum) / 2.0);
    }

    /** Случайный блок внутри региона; null, если за {@code attempts} попыток не нашёлся (очень узкий контур). */
    public BlockPos randomBlock(final RandomGenerator random, final int attempts) {
        for (int i = 0; i < attempts; i++) {
            final int x = this.minX + random.nextInt(this.maxX - this.minX + 1);
            final int z = this.minZ + random.nextInt(this.maxZ - this.minZ + 1);
            if (this.containsBlock(x, z)) {
                return new BlockPos(x, 0, z);
            }
        }
        return null;
    }

    /** Точки контура с шагом {@code step} (для частиц), ребро за ребром, замыкая последнюю вершину с первой. */
    public List<double[]> outline(final double step) {
        final List<double[]> out = new ArrayList<>();
        final int n = this.xs.length;
        for (int i = 0; i < n; i++) {
            final int j = (i + 1) % n;
            edgePoints(this.xs[i], this.zs[i], this.xs[j], this.zs[j], step, out);
        }
        return out;
    }

    /** Точки отрезка a→b с шагом step, без конечной точки (её добавит следующее ребро). */
    public static void edgePoints(final double ax, final double az, final double bx, final double bz, final double step,
                                  final List<double[]> out) {
        final double len = Math.hypot(bx - ax, bz - az);
        final int parts = Math.max(1, (int) Math.ceil(len / step));
        for (int k = 0; k < parts; k++) {
            final double t = (double) k / parts;
            out.add(new double[] {ax + (bx - ax) * t, az + (bz - az) * t});
        }
    }

    private static double segmentDistance(final double px, final double pz, final double ax, final double az,
                                          final double bx, final double bz) {
        final double dx = bx - ax;
        final double dz = bz - az;
        final double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }
}
