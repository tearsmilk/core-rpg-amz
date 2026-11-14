package core.amazake.systems.coreportals.geo;

/**
 * Сторона света, куда смотрит игрок (и куда «смотрит» плоскость портала). Портал ставится по сетке блоков, поэтому
 * взгляд округляется до четырёх направлений.
 */
public enum Facing {
    SOUTH(0, 1, 0f),
    WEST(-1, 0, 90f),
    NORTH(0, -1, 180f),
    EAST(1, 0, 270f);

    private final int dx;
    private final int dz;
    private final float yaw;

    Facing(final int dx, final int dz, final float yaw) {
        this.dx = dx;
        this.dz = dz;
        this.yaw = yaw;
    }

    /** Направление по yaw Bukkit (0 — юг, +z; 90 — запад, −x; 180 — север; 270/−90 — восток). */
    public static Facing fromYaw(final float yaw) {
        final int i = Math.floorMod(Math.round(yaw / 90f), 4);
        return values()[i];
    }

    /** Шаг вперёд по x. */
    public int dx() {
        return this.dx;
    }

    /** Шаг вперёд по z. */
    public int dz() {
        return this.dz;
    }

    /** Yaw Bukkit этого направления. */
    public float yaw() {
        return this.yaw;
    }

    /** Нормаль плоскости портала лежит по оси x (игрок смотрит на восток или запад). */
    public boolean alongX() {
        return this.dx != 0;
    }

    /** Направление к игроку, стоящему перед порталом. */
    public Facing opposite() {
        return values()[(this.ordinal() + 2) % 4];
    }
}
