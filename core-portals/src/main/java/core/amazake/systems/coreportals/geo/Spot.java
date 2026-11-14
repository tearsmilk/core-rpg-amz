package core.amazake.systems.coreportals.geo;

/**
 * Где стоит портал: {@code (x, y, z)} — середина нижней кромки проёма на уровне земли (y — верх опорного блока),
 * {@code facing} — куда смотрит игрок, открывший портал (плоскость портала к нему перпендикулярна).
 *
 * @param blocks два столбца проёма по ширине: {@code {x1, z1, x2, z2}}
 */
public record Spot(double x, int y, double z, Facing facing, int[] blocks) {

    /** Ширина проёма портала (по рамке), блоков. */
    public static final double WIDTH = 2.0;
    /** Высота портала (по рамке), блоков. */
    public static final double HEIGHT = 3.0;
    /** Полуширина светящейся поверхности внутри рамки. */
    public static final double SURFACE_HALF_WIDTH = 1.17;
    /** Высота светящейся поверхности. */
    public static final double SURFACE_HEIGHT = 2.92;
    /** Полутолщина объёма, касание которого телепортирует. */
    public static final double ENTRY_HALF_DEPTH = 0.3;

    /** Объём поверхности: игрок, задевший его хитбоксом, входит в портал. */
    public Box surfaceBox() {
        final double w = SURFACE_HALF_WIDTH;
        final double d = ENTRY_HALF_DEPTH;
        if (this.facing.alongX()) {
            return new Box(this.x - d, this.y, this.z - w, this.x + d, this.y + SURFACE_HEIGHT, this.z + w);
        }
        return new Box(this.x - w, this.y, this.z - d, this.x + w, this.y + SURFACE_HEIGHT, this.z + d);
    }

    /**
     * Точка мира по локальным координатам портала: {@code side} — вбок вдоль проёма, {@code up} — вверх от земли,
     * {@code depth} — по нормали (к игроку, открывшему портал, — отрицательно).
     */
    public double[] toWorld(final double side, final double up, final double depth) {
        final Facing f = this.facing;
        // Вектор «вбок» — поворот направления взгляда на 90°: (−dz, dx).
        final double sx = -f.dz();
        final double sz = f.dx();
        return new double[] {
            this.x + sx * side + f.dx() * depth,
            this.y + up,
            this.z + sz * side + f.dz() * depth,
        };
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof Spot s && s.x == this.x && s.y == this.y && s.z == this.z && s.facing == this.facing
            && java.util.Arrays.equals(s.blocks, this.blocks);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(this.x, this.y, this.z, this.facing, java.util.Arrays.hashCode(this.blocks));
    }

    @Override
    public String toString() {
        return "Spot[" + this.x + ", " + this.y + ", " + this.z + ", " + this.facing + "]";
    }
}
