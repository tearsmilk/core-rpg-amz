package ink.hoka.odyssey.gravity;

/**
 * Чёрная дыра на карте (как в config.yml, единицы — секунды).
 *
 * @param name     имя (уникально в пределах карты)
 * @param x        центр
 * @param y        центр
 * @param z        центр
 * @param horizon  радиус горизонта, блоков: предмет внутри поглощается
 * @param reach    радиус действия, блоков: дальше дыра не притягивает
 * @param pull     ускорение притяжения у края горизонта, блоков/с²
 * @param maxSpeed предел скорости падения в дыру, блоков/с
 */
record BlackHole(String name, double x, double y, double z, double horizon, double reach, double pull, double maxSpeed) {

    /** Расстояние от точки до центра. */
    double distance(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
