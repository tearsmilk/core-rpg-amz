package core.amazake.systems.coreportals.geo;

import java.util.Optional;

/**
 * Куда поставить портал перед игроком. Чистая математика, без Bukkit.
 *
 * <p>Портал стоит по сетке блоков: в ряду блоков на {@code distance} шагов впереди игрока (по округлённому до
 * стороны света взгляду), занимает два столбца по ширине (граница между ними — ближайшая к игроку линия сетки)
 * и три блока в высоту. Под обоими столбцами должна быть твёрдая опора, а все шесть блоков проёма — свободны.
 * Уровень земли ищется от ног игрока: 0, −1, +1, −2, +2… до {@code search} блоков.</p>
 */
public final class Placement {

    /** Высота проёма в блоках, которые должны быть свободны. */
    public static final int CLEAR_HEIGHT = 3;

    private Placement() {
    }

    /**
     * @param px       x игрока
     * @param py       y ног игрока
     * @param pz       z игрока
     * @param yaw      yaw взгляда игрока
     * @param distance сколько рядов блоков вперёд (1..6)
     * @param search   на сколько блоков вниз и вверх искать землю (0..8)
     */
    public static Optional<Spot> find(final double px, final double py, final double pz, final float yaw,
                                      final int distance, final int search, final BlockQuery blocks) {
        final Facing f = Facing.fromYaw(yaw);
        final int bx = (int) Math.floor(px);
        final int bz = (int) Math.floor(pz);
        final int x1;
        final int z1;
        final int x2;
        final int z2;
        final double cx;
        final double cz;
        if (f.alongX()) {
            final int row = bx + f.dx() * distance;
            final int edge = (int) Math.round(pz);   // граница между двумя столбцами
            x1 = row;
            x2 = row;
            z1 = edge - 1;
            z2 = edge;
            cx = row + 0.5;
            cz = edge;
        } else {
            final int row = bz + f.dz() * distance;
            final int edge = (int) Math.round(px);
            z1 = row;
            z2 = row;
            x1 = edge - 1;
            x2 = edge;
            cx = edge;
            cz = row + 0.5;
        }
        final int y0 = (int) Math.floor(py + 1.0e-6);
        for (int i = 0; i <= search * 2; i++) {
            final int y = y0 + order(i);
            if (fits(blocks, x1, z1, y) && fits(blocks, x2, z2, y)) {
                return Optional.of(new Spot(cx, y, cz, f, new int[] {x1, z1, x2, z2}));
            }
        }
        return Optional.empty();
    }

    /** Порядок поиска уровня: 0, −1, +1, −2, +2 … (вниз — раньше: портал «из земли» под горкой). */
    static int order(final int i) {
        if (i == 0) {
            return 0;
        }
        final int k = (i + 1) / 2;
        return (i % 2 == 1) ? -k : k;
    }

    private static boolean fits(final BlockQuery q, final int x, final int z, final int y) {
        if (!q.solid(x, y - 1, z)) {
            return false;
        }
        for (int h = 0; h < CLEAR_HEIGHT; h++) {
            if (!q.passable(x, y + h, z)) {
                return false;
            }
        }
        return true;
    }
}
