package ink.hoka.odyssey.gravzones;

/**
 * Зона гравитации: параллелепипед из блоков [min..max] включительно на карте map.
 *
 * @param name    имя (уникально в пределах карты)
 * @param map     имя карты из maps/
 * @param g       сила: 0.1…10 — множитель ванильной гравитации, больше 10 (11…13) — вакуумный выброс
 * @param created порядковый номер создания — при равном объёме главнее более новая зона
 */
record Zone(String name, String map, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, double g, long created) {

    Zone {
        int ax = Math.min(minX, maxX);
        int bx = Math.max(minX, maxX);
        int ay = Math.min(minY, maxY);
        int by = Math.max(minY, maxY);
        int az = Math.min(minZ, maxZ);
        int bz = Math.max(minZ, maxZ);
        minX = ax;
        maxX = bx;
        minY = ay;
        maxY = by;
        minZ = az;
        maxZ = bz;
    }

    /** Точка (координаты сущности, не блока) внутри зоны: блок max включается целиком. */
    boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
    }

    long volume() {
        return Physics.volume(minX, minY, minZ, maxX, maxY, maxZ);
    }

    boolean vacuum() {
        return Physics.isVacuum(g);
    }

    Zone withG(double newG) {
        return new Zone(name, map, minX, minY, minZ, maxX, maxY, maxZ, newG, created);
    }

    /** Пересекается ли с другой зоной (на той же карте). */
    boolean intersects(Zone o) {
        return map.equals(o.map) && minX <= o.maxX && o.minX <= maxX && minY <= o.maxY && o.minY <= maxY
            && minZ <= o.maxZ && o.minZ <= maxZ;
    }

    /** Главнее ли эта зона другой там, где они пересекаются: меньше по объёму, при равенстве — созданная позже. */
    boolean beats(Zone o) {
        long a = volume();
        long b = o.volume();
        return a != b ? a < b : created > o.created;
    }

    String bounds() {
        return minX + " " + minY + " " + minZ + " … " + maxX + " " + maxY + " " + maxZ;
    }
}
