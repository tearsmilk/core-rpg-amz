package core.amazake.systems.coreportals.geo;

/** Прямоугольный объём в координатах мира. */
public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    /** Пересекаются ли объёмы (касание гранью не считается). */
    public boolean overlaps(final double oMinX, final double oMinY, final double oMinZ,
                            final double oMaxX, final double oMaxY, final double oMaxZ) {
        return oMinX < this.maxX && oMaxX > this.minX
            && oMinY < this.maxY && oMaxY > this.minY
            && oMinZ < this.maxZ && oMaxZ > this.minZ;
    }
}
