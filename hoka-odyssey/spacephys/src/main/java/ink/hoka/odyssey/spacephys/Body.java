package ink.hoka.odyssey.spacephys;

import java.util.Objects;

/**
 * Неподвижное притягивающее тело карты.
 *
 * @param kind      вид тела — от него зависит закон притяжения (см. {@link Kind})
 * @param name      имя (для конфигов, логов и HUD); не участвует в физике
 * @param x         центр, блоки
 * @param y         центр, блоки
 * @param z         центр, блоки
 * @param mu        гравитационный параметр μ = G·M, блоки³/с² (≥ 0)
 * @param radius    радиус шара (PLANET/STAR): внутри поле однородного шара; 0 — точечная масса
 * @param softening длина смягчения ε (SMALL, закон Пламмера); у PLANET/STAR с radius = 0 тоже смягчает центр
 * @param horizon   радиус горизонта r_s (HOLE): падение внутрь — захват; у остальных не используется (0)
 * @param soi       радиус сферы влияния: дальше поле тела плавно (smoothstep) гаснет до нуля; 0 — без отсечки.
 *                  Нужен только малым телам ради скорости, см. {@link Units#sphereOfInfluence}
 */
public record Body(Kind kind, String name, double x, double y, double z,
                   double mu, double radius, double softening, double horizon, double soi) {

    /** Вид тела. */
    public enum Kind {
        /** Планета или луна: снаружи −μ·r̂/r², внутри — однородный шар −μ·r/R³. */
        PLANET,
        /** Звезда: тот же закон, что у планеты (отдельный вид — для неба и конфигов). */
        STAR,
        /** Малое тело (станция, астероид): потенциал Пламмера, −μ·r/(r² + ε²)^{3/2}. */
        SMALL,
        /** Чёрная дыра: псевдоньютоновский потенциал Пачинского–Виита, −μ·r̂/(r − r_s)². */
        HOLE
    }

    public Body {
        Objects.requireNonNull(kind, "kind");
        name = name == null ? "" : name;
        requireFinite("x", x);
        requireFinite("y", y);
        requireFinite("z", z);
        requireNonNegative("mu", mu);
        requireNonNegative("radius", radius);
        requireNonNegative("softening", softening);
        requireNonNegative("horizon", horizon);
        requireNonNegative("soi", soi);
        if (kind == Kind.HOLE && !(horizon > 0)) {
            throw new IllegalArgumentException("у чёрной дыры «" + name + "» должен быть horizon > 0");
        }
    }

    /** Планета (или луна) радиуса R. */
    public static Body planet(String name, double x, double y, double z, double mu, double radius) {
        return new Body(Kind.PLANET, name, x, y, z, mu, radius, 0, 0, 0);
    }

    /** Планета по ускорению на поверхности g_s (блоки/с²): μ = g_s·R². */
    public static Body planetFromSurfaceGravity(String name, double x, double y, double z,
                                                double surfaceGravity, double radius) {
        return planet(name, x, y, z, Units.muFromSurfaceGravity(surfaceGravity, radius), radius);
    }

    /** Звезда радиуса R. */
    public static Body star(String name, double x, double y, double z, double mu, double radius) {
        return new Body(Kind.STAR, name, x, y, z, mu, radius, 0, 0, 0);
    }

    /** Малое тело (станция, астероид) со смягчением ε и сферой влияния soi (0 — без отсечки). */
    public static Body small(String name, double x, double y, double z, double mu, double softening, double soi) {
        return new Body(Kind.SMALL, name, x, y, z, mu, 0, softening, 0, soi);
    }

    /** Чёрная дыра с горизонтом r_s. */
    public static Body hole(String name, double x, double y, double z, double mu, double horizon) {
        return new Body(Kind.HOLE, name, x, y, z, mu, 0, 0, horizon, 0);
    }

    public Vec3d position() { return new Vec3d(x, y, z); }

    /** Расстояние от центра тела до точки. */
    public double distance(double px, double py, double pz) {
        double dx = px - x, dy = py - y, dz = pz - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public boolean isHole() { return kind == Kind.HOLE; }

    /** Точка внутри горизонта (только для HOLE; у остальных всегда false). */
    public boolean insideHorizon(double px, double py, double pz) {
        return kind == Kind.HOLE && distance(px, py, pz) <= horizon;
    }

    /** Копия с другим центром (для перемещения тела командой). */
    public Body withPosition(double nx, double ny, double nz) {
        return new Body(kind, name, nx, ny, nz, mu, radius, softening, horizon, soi);
    }

    /** Копия с другой сферой влияния. */
    public Body withSoi(double newSoi) {
        return new Body(kind, name, x, y, z, mu, radius, softening, horizon, newSoi);
    }

    private static void requireFinite(String what, double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(what + " = " + v + ": нужно конечное число");
        }
    }

    private static void requireNonNegative(String what, double v) {
        if (!(v >= 0) || !Double.isFinite(v)) {
            throw new IllegalArgumentException(what + " = " + v + ": нужно конечное число ≥ 0");
        }
    }
}
