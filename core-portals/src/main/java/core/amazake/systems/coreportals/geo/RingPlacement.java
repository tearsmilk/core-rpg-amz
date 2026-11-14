package core.amazake.systems.coreportals.geo;

import java.util.Optional;

/**
 * Изумрудный портал — вертикальное кольцо ≈ 3 блока в диаметре, центр на {@link #CENTER_UP} над землёй, плоскость
 * кольца перпендикулярна взгляду игрока. Чистая математика, без Bukkit.
 *
 * <p>Место: ряд блоков на {@code distance} шагов впереди (взгляд округлён до стороны света), три столбца по
 * ширине — средний под игроком (центр кольца — середина среднего блока), {@link #CLEAR_HEIGHT} блока в высоту.
 * Под средним столбцом — твёрдая опора, все 3×4 блока — свободны. Уровень земли ищется как у «Портала»:
 * 0, −1, +1, −2, +2… до {@code search} блоков.</p>
 *
 * <p>Вход: хитбокс игрока задевает внутренний диск радиуса {@link #ENTRY_RADIUS} (толщина ±{@link #ENTRY_HALF_DEPTH}
 * по нормали).</p>
 */
public final class RingPlacement {

    /** Высота центра кольца над землёй, блоков. */
    public static final double CENTER_UP = 1.8;
    /** Внешний радиус яркого кольца, блоков (диаметр ≈ 3). */
    public static final double RADIUS = 1.5;
    /** Радиус внутреннего диска (пустоты), касание которого телепортирует. */
    public static final double ENTRY_RADIUS = 1.2;
    /** Полутолщина диска входа по нормали. */
    public static final double ENTRY_HALF_DEPTH = 0.3;
    /** Ширина места (столбцов). */
    public static final int CLEAR_WIDTH = 3;
    /** Высота места (блоков): кольцо от 0,3 до 3,3 над землёй. */
    public static final int CLEAR_HEIGHT = 4;

    private RingPlacement() {
    }

    /**
     * @param px       x игрока
     * @param py       y ног игрока
     * @param pz       z игрока
     * @param yaw      yaw взгляда игрока
     * @param distance сколько рядов блоков вперёд (1..6)
     * @param search   на сколько блоков вниз и вверх искать землю (0..8)
     * @return место: {@code (x, y, z)} — точка земли под центром кольца, {@code blocks} — три столбца
     *         {@code {x1, z1, x2, z2, x3, z3}} (средний — второй)
     */
    public static Optional<Spot> find(final double px, final double py, final double pz, final float yaw,
                                      final int distance, final int search, final BlockQuery blocks) {
        final Facing f = Facing.fromYaw(yaw);
        final int bx = (int) Math.floor(px);
        final int bz = (int) Math.floor(pz);
        final int[] cols = new int[6];
        final double cx;
        final double cz;
        if (f.alongX()) {
            final int row = bx + f.dx() * distance;
            for (int i = 0; i < 3; i++) {
                cols[2 * i] = row;
                cols[2 * i + 1] = bz - 1 + i;
            }
            cx = row + 0.5;
            cz = bz + 0.5;
        } else {
            final int row = bz + f.dz() * distance;
            for (int i = 0; i < 3; i++) {
                cols[2 * i] = bx - 1 + i;
                cols[2 * i + 1] = row;
            }
            cx = bx + 0.5;
            cz = row + 0.5;
        }
        final int y0 = (int) Math.floor(py + 1.0e-6);
        for (int i = 0; i <= search * 2; i++) {
            final int y = y0 + Placement.order(i);
            if (fits(blocks, cols, y)) {
                return Optional.of(new Spot(cx, y, cz, f, cols));
            }
        }
        return Optional.empty();
    }

    private static boolean fits(final BlockQuery q, final int[] cols, final int y) {
        if (!q.solid(cols[2], y - 1, cols[3])) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            for (int h = 0; h < CLEAR_HEIGHT; h++) {
                if (!q.passable(cols[2 * i], y + h, cols[2 * i + 1])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Задевает ли объём (хитбокс игрока) внутренний диск кольца: объём пересекает слой ±{@link #ENTRY_HALF_DEPTH}
     * у плоскости кольца, и его проекция на плоскость ближе {@link #ENTRY_RADIUS} к центру.
     */
    public static boolean touches(final Spot spot, final double minX, final double minY, final double minZ,
                                  final double maxX, final double maxY, final double maxZ) {
        final Facing f = spot.facing();
        // Локальные оси: «вглубь» = (dx, dz), «вбок» = (−dz, dx). По каждой оси одна из координат мира.
        final double d0;
        final double d1;
        final double s0;
        final double s1;
        if (f.alongX()) {
            d0 = (minX - spot.x()) * f.dx();
            d1 = (maxX - spot.x()) * f.dx();
            s0 = (minZ - spot.z()) * f.dx();
            s1 = (maxZ - spot.z()) * f.dx();
        } else {
            d0 = (minZ - spot.z()) * f.dz();
            d1 = (maxZ - spot.z()) * f.dz();
            s0 = (minX - spot.x()) * -f.dz();
            s1 = (maxX - spot.x()) * -f.dz();
        }
        if (Math.max(d0, d1) <= -ENTRY_HALF_DEPTH || Math.min(d0, d1) >= ENTRY_HALF_DEPTH) {
            return false;
        }
        final double cy = spot.y() + CENTER_UP;
        final double u = nearestToZero(Math.min(s0, s1), Math.max(s0, s1));
        final double v = nearestToZero(minY - cy, maxY - cy);
        return u * u + v * v < ENTRY_RADIUS * ENTRY_RADIUS;
    }

    /** Ближайшая к нулю точка отрезка {@code [lo, hi]}. */
    private static double nearestToZero(final double lo, final double hi) {
        return lo > 0 ? lo : (hi < 0 ? hi : 0);
    }
}
