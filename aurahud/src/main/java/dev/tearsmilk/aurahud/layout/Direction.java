package dev.tearsmilk.aurahud.layout;

import java.util.Locale;

public enum Direction {
    RIGHT("right", 1, 0),
    LEFT("left", -1, 0),
    UP("up", 0, -1),
    DOWN("down", 0, 1);

    private final String key;
    private final int dx;
    private final int dy;

    Direction(String key, int dx, int dy) {
        this.key = key;
        this.dx = dx;
        this.dy = dy;
    }

    public String key() {
        return key;
    }

    public int dx() {
        return dx;
    }

    public int dy() {
        return dy;
    }

    public Direction opposite() {
        return switch (this) {
            case RIGHT -> LEFT;
            case LEFT -> RIGHT;
            case UP -> DOWN;
            case DOWN -> UP;
        };
    }

    public boolean perpendicular(Direction o) {
        return dx * o.dx + dy * o.dy == 0;
    }

    public static Direction parse(String s) {
        if (s == null) {
            return null;
        }
        String k = s.trim().toLowerCase(Locale.ROOT);
        for (Direction d : values()) {
            if (d.key.equals(k)) {
                return d;
            }
        }
        return null;
    }
}
