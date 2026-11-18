package core.amazake.systems.coremechanics.physics;

import java.util.Arrays;

/**
 * Статичный треугольный меш секции 16×16×16 из коллизионных боксов блоков (перенесён из PasToSmith без изменений
 * логики). Грань на границе блока, закрытая соседним ПОЛНЫМ блоком, не строится. Координаты вершин — локальные в
 * секции (0..16), тело ставится в мировой точке начала секции. Порядок вершин: (v1−v0)×(v2−v0) смотрит НАРУЖУ —
 * Jolt по нормали решает, куда выталкивать тела.
 */
public final class SectionMesher {

    /** Канонический полный куб (сравнивается по ссылке). */
    public static final float[] FULL = {0f, 0f, 0f, 1f, 1f, 1f};
    public static final float[] EMPTY = new float[0];

    private SectionMesher() {
    }

    /** Треугольники (по 9 float) или null, если граней нет; число треугольников; хэш геометрии. */
    public record Result(float[] triangles, int triangleCount, long hash) {
    }

    /** Меш секции с мировым началом (ox, oy, oz), кратным 16. */
    public static Result mesh(final BlockSampler s, final int ox, final int oy, final int oz) {
        final FloatList out = new FloatList(2048);
        long hash = 1469598103934665603L;
        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    final int x = ox + lx, y = oy + ly, z = oz + lz;
                    final float[] b = s.boxes(x, y, z);
                    hash = mixHash(hash, lx, ly, lz, b);
                    if (b == null || b.length == 0) {
                        continue;
                    }
                    for (int i = 0; i + 5 < b.length; i += 6) {
                        final float x0 = lx + b[i], y0 = ly + b[i + 1], z0 = lz + b[i + 2];
                        final float x1 = lx + b[i + 3], y1 = ly + b[i + 4], z1 = lz + b[i + 5];
                        final boolean onTop = b[i + 4] >= 1f, onBottom = b[i + 1] <= 0f;
                        final boolean onEast = b[i + 3] >= 1f, onWest = b[i] <= 0f;
                        final boolean onSouth = b[i + 5] >= 1f, onNorth = b[i + 2] <= 0f;
                        if (!(onTop && s.full(x, y + 1, z))) {          // +Y
                            quad(out, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
                        }
                        if (!(onBottom && s.full(x, y - 1, z))) {       // −Y
                            quad(out, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
                        }
                        if (!(onEast && s.full(x + 1, y, z))) {         // +X
                            quad(out, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
                        }
                        if (!(onWest && s.full(x - 1, y, z))) {         // −X
                            quad(out, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
                        }
                        if (!(onSouth && s.full(x, y, z + 1))) {        // +Z
                            quad(out, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
                        }
                        if (!(onNorth && s.full(x, y, z - 1))) {        // −Z
                            quad(out, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
                        }
                    }
                }
            }
        }
        final int tris = out.size / 9;
        return new Result(tris == 0 ? null : out.toArray(), tris, hash);
    }

    private static long mixHash(long h, final int lx, final int ly, final int lz, final float[] b) {
        if (b == null || b.length == 0) {
            return h;   // воздух не вносит вклад
        }
        h ^= (lx | (ly << 4) | (lz << 8));
        h *= 1099511628211L;
        h ^= (b == FULL) ? 0x5bd1e995L : Arrays.hashCode(b);
        h *= 1099511628211L;
        return h;
    }

    /** Четырёхугольник c0..c3 (CCW снаружи) → два треугольника. */
    private static void quad(final FloatList o,
                             final float ax, final float ay, final float az, final float bx, final float by, final float bz,
                             final float cx, final float cy, final float cz, final float dx, final float dy, final float dz) {
        o.add9(ax, ay, az, bx, by, bz, cx, cy, cz);
        o.add9(ax, ay, az, cx, cy, cz, dx, dy, dz);
    }

    /** Растущий float-массив без боксинга. */
    static final class FloatList {
        float[] a;
        int size;

        FloatList(final int cap) {
            this.a = new float[Math.max(9, cap)];
        }

        void add9(final float v0, final float v1, final float v2, final float v3, final float v4, final float v5,
                  final float v6, final float v7, final float v8) {
            if (this.size + 9 > this.a.length) {
                this.a = Arrays.copyOf(this.a, this.a.length * 2);
            }
            final float[] x = this.a;
            final int s = this.size;
            x[s] = v0;
            x[s + 1] = v1;
            x[s + 2] = v2;
            x[s + 3] = v3;
            x[s + 4] = v4;
            x[s + 5] = v5;
            x[s + 6] = v6;
            x[s + 7] = v7;
            x[s + 8] = v8;
            this.size += 9;
        }

        float[] toArray() {
            return Arrays.copyOf(this.a, this.size);
        }
    }
}
