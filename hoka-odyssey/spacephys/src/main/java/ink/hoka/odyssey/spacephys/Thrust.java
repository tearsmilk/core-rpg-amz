package ink.hoka.odyssey.spacephys;

/**
 * Ранец 6DoF: тяга по осям ориентации (вперёд/вправо/вверх) с ограниченным запасом Δv и SAS.
 * Один объект — один игрок (хранит остаток топлива); не потокобезопасен.
 * <p>
 * Расход честный: за тик тратится |a|·dt блоков/с запаса. Когда запас кончился — тяги нет, пока не заправят
 * ({@link #refill}: станции/зоны). Безлимитный режим — {@link Spec#unlimited}.
 */
public final class Thrust {

    /**
     * Параметры ранца.
     *
     * @param acceleration наибольшее ускорение, блоки/с²
     * @param capacity     запас Δv полного баллона, блоки/с; {@code +∞} — без ограничения
     */
    public record Spec(double acceleration, double capacity) {
        /** 2 блока/с², 60 блоков/с запаса. */
        public static final Spec DEFAULT = new Spec(Units.THRUST_ACCEL, Units.DELTA_V_CAPACITY);

        public Spec {
            if (!(acceleration >= 0) || !Double.isFinite(acceleration)) {
                throw new IllegalArgumentException("acceleration = " + acceleration + ": нужно конечное число ≥ 0");
            }
            if (!(capacity >= 0)) {
                throw new IllegalArgumentException("capacity = " + capacity + ": нужно число ≥ 0 (или +∞)");
            }
        }

        /** Ранец без ограничения топлива. */
        public static Spec unlimited(double acceleration) { return new Spec(acceleration, Double.POSITIVE_INFINITY); }

        public boolean isUnlimited() { return capacity == Double.POSITIVE_INFINITY; }
    }

    private final Spec spec;
    private double fuel;

    /** Полный баллон. */
    public Thrust(Spec spec) {
        this.spec = spec;
        this.fuel = spec.capacity();
    }

    public Thrust() { this(Spec.DEFAULT); }

    public Spec spec() { return spec; }

    /** Остаток Δv, блоки/с ({@code +∞} — без ограничения). */
    public double fuel() { return fuel; }

    /** Доля остатка 0…1 (для HUD); у безлимитного — 1. */
    public double fuelFraction() {
        if (spec.isUnlimited()) {
            return 1;
        }
        return spec.capacity() == 0 ? 0 : fuel / spec.capacity();
    }

    public boolean isEmpty() { return !(fuel > 0); }

    /** Заправить полностью. */
    public void refill() { fuel = spec.capacity(); }

    /** Долить amount блоков/с, не больше ёмкости. */
    public void refill(double amount) {
        if (amount > 0) {
            fuel = Math.min(spec.capacity(), fuel + amount);
        }
    }

    /** Задать остаток (например, из сохранения или от сервера), обрезается в [0, capacity]. */
    public void setFuel(double value) {
        fuel = Math.max(0, Math.min(spec.capacity(), value));
    }

    /**
     * Ускорение тяги по вводу в базисе ориентации; тратит топливо.
     *
     * @param forward ввод вдоль forward, −1…1 (W/S)
     * @param right   ввод вдоль right, −1…1 (D/A)
     * @param up      ввод вдоль up, −1…1 (Space/Shift)
     * @param basisF  единичный вектор «вперёд» в мире
     * @param basisR  единичный вектор «вправо»
     * @param basisU  единичный вектор «вверх»
     * @param dt      длительность тика, с
     * @return ускорение в мире, блоки/с² (ZERO — нет ввода или топлива)
     */
    public Vec3d accelerate(double forward, double right, double up,
                            Vec3d basisF, Vec3d basisR, Vec3d basisU, double dt) {
        double f = clamp(forward), r = clamp(right), u = clamp(up);
        double len = Math.sqrt(f * f + r * r + u * u);
        if (len == 0) {
            return Vec3d.ZERO;
        }
        if (len > 1) { // диагональ не быстрее одной оси
            f /= len;
            r /= len;
            u /= len;
        }
        double ax = (basisF.x() * f + basisR.x() * r + basisU.x() * u) * spec.acceleration();
        double ay = (basisF.y() * f + basisR.y() * r + basisU.y() * u) * spec.acceleration();
        double az = (basisF.z() * f + basisR.z() * r + basisU.z() * u) * spec.acceleration();
        return burn(ax, ay, az, dt);
    }

    /** То же в базисе кватерниона ориентации. */
    public Vec3d accelerate(double forward, double right, double up, Quat orientation, double dt) {
        return accelerate(forward, right, up, orientation.forward(), orientation.right(), orientation.up(), dt);
    }

    /**
     * SAS: гасит скорость относительно опорного тела (обычно {@link Field#dominant}); не перелетает через ноль —
     * за тик снимает не больше |v|. Тратит топливо как обычная тяга.
     *
     * @param relativeVelocity скорость относительно тела, блоки/с
     * @return ускорение, блоки/с²
     */
    public Vec3d stabilize(Vec3d relativeVelocity, double dt) {
        double v = relativeVelocity.length();
        if (v == 0 || !(dt > 0)) {
            return Vec3d.ZERO;
        }
        double a = Math.min(spec.acceleration(), v / dt);
        double k = -a / v;
        return burn(relativeVelocity.x() * k, relativeVelocity.y() * k, relativeVelocity.z() * k, dt);
    }

    /**
     * Обычное управление за тик: есть ввод — тяга по вводу; ввода нет и SAS включён — {@link #stabilize}.
     */
    public Vec3d control(double forward, double right, double up, Quat orientation,
                         boolean sas, Vec3d relativeVelocity, double dt) {
        if (forward != 0 || right != 0 || up != 0) {
            return accelerate(forward, right, up, orientation, dt);
        }
        return sas ? stabilize(relativeVelocity, dt) : Vec3d.ZERO;
    }

    /** Списать |a|·dt из запаса; не хватает — ускорение уменьшается до остатка. */
    private Vec3d burn(double ax, double ay, double az, double dt) {
        double a = Math.sqrt(ax * ax + ay * ay + az * az);
        if (a == 0 || !(dt > 0)) {
            return Vec3d.ZERO;
        }
        if (spec.isUnlimited()) {
            return new Vec3d(ax, ay, az);
        }
        double need = a * dt;
        if (need <= fuel) {
            fuel -= need;
            return new Vec3d(ax, ay, az);
        }
        if (!(fuel > 0)) {
            fuel = 0;
            return Vec3d.ZERO;
        }
        double k = fuel / need;
        fuel = 0;
        return new Vec3d(ax * k, ay * k, az * k);
    }

    private static double clamp(double v) {
        if (!(v == v)) {
            return 0; // NaN
        }
        return v < -1 ? -1 : v > 1 ? 1 : v;
    }
}
