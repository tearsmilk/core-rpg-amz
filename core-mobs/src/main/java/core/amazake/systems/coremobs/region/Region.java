package core.amazake.systems.coremobs.region;

import core.amazake.systems.coremobs.geo.BlockPos;
import core.amazake.systems.coremobs.geo.Polygon;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Регион спавна: полигон по X/Z и высота от {@code minY} до {@code maxY} включительно. Неизменяемый: любое изменение
 * через команду создаёт новый объект и подменяет его в хранилище.
 *
 * @param despawnRadius на сколько блоков моб региона может отойти за границу; дальше он пропадает. −1 — не следить
 */
public record Region(String name, String world, Polygon polygon, int minY, int maxY, int despawnRadius,
                     SpawnSettings spawn) {

    public static final Pattern NAME = Pattern.compile("[a-z0-9_\\-]{1,48}");

    public Region {
        if (minY > maxY) {
            final int t = minY;
            minY = maxY;
            maxY = t;
        }
    }

    /** Новый регион из точек выделения: высота от самой низкой точки до самой высокой плюс {@code extraHeight}. */
    public static Region fromPoints(final String name, final String world, final List<BlockPos> points,
                                    final int extraHeight, final int despawnRadius) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (final BlockPos p : points) {
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        return new Region(name, world, new Polygon(points), minY, maxY + extraHeight, despawnRadius, SpawnSettings.DEFAULTS);
    }

    public boolean contains(final double x, final double y, final double z) {
        return y >= this.minY && y < this.maxY + 1 && this.polygon.contains(x, z);
    }

    /**
     * На сколько блоков точка вышла за регион: по горизонтали — от края полигона, по вертикали — от пола или потолка;
     * берётся большее. 0 — внутри.
     */
    public double distanceOutside(final double x, final double y, final double z) {
        final double horizontal = this.polygon.distanceOutside(x, z);
        final double vertical = y < this.minY ? this.minY - y : y > this.maxY + 1 ? y - (this.maxY + 1) : 0.0;
        return Math.max(horizontal, vertical);
    }

    public Region withPoints(final List<BlockPos> points, final int minY, final int maxY) {
        return new Region(this.name, this.world, new Polygon(points), minY, maxY, this.despawnRadius, this.spawn);
    }

    public Region withHeight(final int minY, final int maxY) {
        return new Region(this.name, this.world, this.polygon, minY, maxY, this.despawnRadius, this.spawn);
    }

    public Region withDespawnRadius(final int value) {
        return new Region(this.name, this.world, this.polygon, this.minY, this.maxY, value, this.spawn);
    }

    public Region withSpawn(final SpawnSettings value) {
        return new Region(this.name, this.world, this.polygon, this.minY, this.maxY, this.despawnRadius, value);
    }

    /** Центр рамки региона (для телепорта и задач). */
    public int centerX() {
        return (this.polygon.minX() + this.polygon.maxX()) / 2;
    }

    public int centerZ() {
        return (this.polygon.minZ() + this.polygon.maxZ()) / 2;
    }
}
