package ink.hoka.odyssey.gravzones;

/** Выделение игрока: две точки (блоки) на одной карте. Точка на другой карте сбрасывает вторую. */
final class Selection {
    String map;
    int[] p1;
    int[] p2;

    void set(int which, String mapName, int x, int y, int z) {
        if (map != null && !map.equals(mapName)) {
            p1 = null;
            p2 = null;
        }
        map = mapName;
        int[] p = {x, y, z};
        if (which == 1) {
            p1 = p;
        } else {
            p2 = p;
        }
    }

    boolean complete() {
        return p1 != null && p2 != null;
    }

    long volume() {
        return complete() ? Physics.volume(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2]) : 0;
    }

    /** Размер вида 5×4×6. */
    String size() {
        if (!complete()) {
            return "";
        }
        return (Math.abs(p2[0] - p1[0]) + 1) + "×" + (Math.abs(p2[1] - p1[1]) + 1) + "×" + (Math.abs(p2[2] - p1[2]) + 1);
    }
}
