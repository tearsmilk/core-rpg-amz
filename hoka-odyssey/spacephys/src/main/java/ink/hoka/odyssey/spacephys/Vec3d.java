package ink.hoka.odyssey.spacephys;

/**
 * Неизменяемый трёхмерный вектор (double). Единицы — какие передали: обычно блоки, блоки/с, блоки/с².
 * Горячие циклы ({@link Field}, {@link Integrator}) работают на примитивах без аллокаций; этот тип — для удобства API.
 */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0, 0, 0);

    public Vec3d add(Vec3d o) { return new Vec3d(x + o.x, y + o.y, z + o.z); }

    public Vec3d add(double ox, double oy, double oz) { return new Vec3d(x + ox, y + oy, z + oz); }

    public Vec3d sub(Vec3d o) { return new Vec3d(x - o.x, y - o.y, z - o.z); }

    public Vec3d scale(double k) { return new Vec3d(x * k, y * k, z * k); }

    public Vec3d negate() { return new Vec3d(-x, -y, -z); }

    /** this + o·k */
    public Vec3d addScaled(Vec3d o, double k) { return new Vec3d(x + o.x * k, y + o.y * k, z + o.z * k); }

    public double dot(Vec3d o) { return x * o.x + y * o.y + z * o.z; }

    public Vec3d cross(Vec3d o) { return new Vec3d(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }

    public double lengthSquared() { return x * x + y * y + z * z; }

    public double length() { return Math.sqrt(lengthSquared()); }

    public double distance(Vec3d o) { return sub(o).length(); }

    /** Единичный вектор того же направления; нулевой вектор остаётся нулевым. */
    public Vec3d normalize() {
        double l = length();
        return l == 0 ? ZERO : new Vec3d(x / l, y / l, z / l);
    }

    public boolean isFinite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
}
