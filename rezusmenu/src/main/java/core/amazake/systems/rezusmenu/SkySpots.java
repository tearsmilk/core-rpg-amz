package core.amazake.systems.rezusmenu;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Раздаёт открытым меню клетки неба {@link #CELL} × {@link #CELL} блоков: каждому игроку — свободная клетка, ближайшая
 * к нему (своя, если свободна; иначе по спирали). Так игроки, открывшие меню рядом, оказываются далеко друг от друга
 * и не видят друг друга сквозь меню. Потокобезопасен (игроки открывают меню каждый в своём потоке).
 */
public final class SkySpots {

    /** Размер клетки, блоков: дальше любого дисплея меню и непрозрачного фона. */
    public static final int CELL = 24;
    private static final int MAX_RING = 64;

    private record Cell(String world, int x, int z) { }

    private final Map<UUID, Cell> taken = new HashMap<>();

    /** Занять клетку для игрока (прежняя освобождается). Возвращает упакованные координаты клетки. */
    public synchronized long claim(final UUID player, final String world, final double x, final double z) {
        this.taken.remove(player);
        final int cx = Math.floorDiv((int) Math.floor(x), CELL);
        final int cz = Math.floorDiv((int) Math.floor(z), CELL);
        for (int ring = 0; ring <= MAX_RING; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    final Cell c = new Cell(world, cx + dx, cz + dz);
                    if (!this.taken.containsValue(c)) {
                        this.taken.put(player, c);
                        return pack(c.x(), c.z());
                    }
                }
            }
        }
        final Cell c = new Cell(world, cx, cz);
        this.taken.put(player, c);
        return pack(cx, cz);
    }

    public synchronized void release(final UUID player) {
        this.taken.remove(player);
    }

    public synchronized boolean isTaken(final String world, final int cx, final int cz) {
        return this.taken.containsValue(new Cell(Objects.requireNonNull(world), cx, cz));
    }

    static long pack(final int cx, final int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int cellX(final long cell) {
        return (int) (cell >> 32);
    }

    public static int cellZ(final long cell) {
        return (int) cell;
    }

    public static double centerX(final long cell) {
        return cellX(cell) * (double) CELL + CELL / 2.0;
    }

    public static double centerZ(final long cell) {
        return cellZ(cell) * (double) CELL + CELL / 2.0;
    }
}
