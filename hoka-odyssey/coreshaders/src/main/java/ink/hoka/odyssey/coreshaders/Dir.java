package ink.hoka.odyssey.coreshaders;

import java.util.Locale;

public enum Dir {
    UP(0, 1, 0),
    DOWN(0, -1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0);

    public final int dx;
    public final int dy;
    public final int dz;

    Dir(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public Dir opposite() {
        return switch (this) {
            case UP -> DOWN;
            case DOWN -> UP;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    public boolean vertical() {
        return this == UP || this == DOWN;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Dir parse(String s) {
        if (s == null) {
            return null;
        }
        for (Dir d : values()) {
            if (d.name().equalsIgnoreCase(s.trim())) {
                return d;
            }
        }
        return null;
    }
}
