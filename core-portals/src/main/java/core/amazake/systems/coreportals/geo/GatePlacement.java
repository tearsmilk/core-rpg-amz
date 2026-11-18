package core.amazake.systems.coreportals.geo;

import java.util.Optional;

/**
 * «Врата междумирья» — готическая объёмная рама {@link #WIDTH}×{@link #HEIGHT} блоков, плоскость перпендикулярна
 * взгляду игрока. Чистая математика, без Bukkit.
 *
 * <p>Место: ряд блоков на {@code distance} шагов впереди (взгляд округлён до стороны света), 7 столбцов по ширине
 * (средний — напротив игрока, центр врат — середина среднего блока). Твёрдая опора нужна под столбцами −3, −2, 0,
 * +2, +3 (под ногами рамы и под серединой). Свободными (проходимыми) должны быть 7 столбцов × 10 блоков в высоту в
 * ряду врат и в трёх рядах перед ними (к игроку) — «пространство перед створками». Уровень земли ищется как у
 * «Портала»: 0, −1, +1, −2, +2… до {@code search} блоков.</p>
 *
 * <p>Вход: хитбокс игрока задевает эллипс проёма (полуоси 2,28 × 4,385, ужатые до 96 %, центр на 4,465 над землёй),
 * толщина ±0,3 по нормали.</p>
 */
public final class GatePlacement {

    /** Ширина рамы, блоков. */
    public static final double WIDTH = 6.0;
    /** Высота рамы, блоков. */
    public static final double HEIGHT = 10.0;
    /** Полуширина проёма внутри рамы, блоков. */
    public static final double INNER_HALF_WIDTH = 2.28;
    /** Высота проёма внутри рамы, блоков. */
    public static final double INNER_HEIGHT = 8.85;

    private GatePlacement() {
    }

    /**
     * @param px       x игрока
     * @param py       y ног игрока
     * @param pz       z игрока
     * @param yaw      yaw взгляда игрока
     * @param distance сколько рядов блоков вперёд (4..8)
     * @param search   на сколько блоков вниз и вверх искать землю (0..8)
     * @return место: {@code (x, y, z)} — точка земли под центром врат, {@code blocks} — 7 столбцов
     *         {@code {x1, z1, … x7, z7}} (средний — четвёртый)
     */
    public static Optional<Spot> find(final double px, final double py, final double pz, final float yaw,
                                      final int distance, final int search, final BlockQuery q) {
        final Facing f = Facing.fromYaw(yaw);
        final int rowX = (int) Math.floor(px) + f.dx() * distance;
        final int rowZ = (int) Math.floor(pz) + f.dz() * distance;
        // Вектор «вбок» — поворот направления взгляда на 90°: (−dz, dx), как в Spot.toWorld.
        final int[] columns = new int[14];
        for (int i = 0; i < 7; i++) {
            columns[2 * i] = rowX - f.dz() * (i - 3);
            columns[2 * i + 1] = rowZ + f.dx() * (i - 3);
        }
        for (int i = 0; i <= search * 2; i++) {
            final int y = (int) Math.floor(py + 1.0E-6) + Placement.order(i);
            boolean fits = true;
            // Опора: под ногами рамы (по два столбца с краёв) и под серединой.
            for (final int side : new int[] {-3, -2, 0, 2, 3}) {
                fits &= q.solid(rowX - f.dz() * side, y - 1, rowZ + f.dx() * side);
            }
            // Свободно: 7 столбцов × 10 блоков в ряду врат (depth 0) и в трёх рядах перед ними (к игроку).
            for (int side = -3; side <= 3 && fits; side++) {
                for (int depth = -3; depth <= 0 && fits; depth++) {
                    for (int up = 0; up < 10; up++) {
                        if (!q.passable(rowX - f.dz() * side + f.dx() * depth, y + up,
                            rowZ + f.dx() * side + f.dz() * depth)) {
                            fits = false;
                            break;
                        }
                    }
                }
            }
            if (fits) {
                return Optional.of(new Spot(rowX + 0.5, y, rowZ + 0.5, f, columns));
            }
        }
        return Optional.empty();
    }

    /**
     * Задевает ли {@code box} (хитбокс игрока) проём врат: по нормали — ±0,3 блока от плоскости, в плоскости —
     * эллипс с полуосями {@link #INNER_HALF_WIDTH} × 4,385, ужатыми до 96 % (рама не телепортирует), центр на
     * 4,465 над землёй (0,08 + 4,385).
     */
    public static boolean touches(final Spot spot, final Box box) {
        final boolean x = spot.facing().alongX();
        final double depthMin = (x ? box.minX() : box.minZ()) - (x ? spot.x() : spot.z());
        final double depthMax = (x ? box.maxX() : box.maxZ()) - (x ? spot.x() : spot.z());
        if (depthMax <= -0.3 || depthMin >= 0.3) {
            return false;
        }
        // Расстояние от хитбокса до центра по сторонам (0 — хитбокс перекрывает центр), в долях полуосей.
        final double sideMin = (x ? box.minZ() : box.minX()) - (x ? spot.z() : spot.x());
        final double sideMax = (x ? box.maxZ() : box.maxX()) - (x ? spot.z() : spot.x());
        final double side = Math.max(0.0, Math.max(sideMin, -sideMax)) / (INNER_HALF_WIDTH * 0.96);
        final double center = spot.y() + 0.08 + 4.385;
        final double up = Math.max(0.0, Math.max(box.minY() - center, center - box.maxY())) / (4.385 * 0.96);
        return side * side + up * up < 1.0;
    }
}
