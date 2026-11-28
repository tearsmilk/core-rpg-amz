package ink.hoka.odyssey.spacephys;

/**
 * Единичный кватернион ориентации (w + xi + yj + zk). Соглашение Minecraft: при единичном кватернионе взгляд
 * вдоль +Z (yaw = 0 — на юг), верх +Y, правая рука −X; {@link #right()} = forward × up.
 * Повороты в своих осях — умножение справа: {@code q.mul(Quat.axisAngle(...))}.
 */
public record Quat(double w, double x, double y, double z) {
    public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

    public static final Vec3d FORWARD = new Vec3d(0, 0, 1);
    public static final Vec3d UP = new Vec3d(0, 1, 0);
    public static final Vec3d RIGHT = new Vec3d(-1, 0, 0);

    /** Поворот на angle радиан вокруг оси axis (ось нормируется; правило правой руки). */
    public static Quat axisAngle(Vec3d axis, double angle) {
        Vec3d n = axis.normalize();
        double s = StrictMath.sin(angle * 0.5);
        return new Quat(StrictMath.cos(angle * 0.5), n.x() * s, n.y() * s, n.z() * s);
    }

    /**
     * Из углов Minecraft в градусах: yaw (0 — +Z, растёт к −X), pitch (+ — вниз), roll (+ — по часовой,
     * если смотреть из-за спины). Q = Ry(−yaw)·Rx(pitch)·Rz(roll); forward совпадает с
     * {@code Entity#getLookAngle}: (−sin yaw·cos pitch, −sin pitch, cos yaw·cos pitch).
     */
    public static Quat fromYawPitchRoll(double yawDeg, double pitchDeg, double rollDeg) {
        Quat qy = axisAngle(UP, -Math.toRadians(yawDeg));
        Quat qx = axisAngle(new Vec3d(1, 0, 0), Math.toRadians(pitchDeg));
        Quat qz = axisAngle(FORWARD, Math.toRadians(rollDeg));
        return qy.mul(qx).mul(qz);
    }

    /** this · o (сначала o, потом this — при повороте векторов). */
    public Quat mul(Quat o) {
        return new Quat(
                w * o.w - x * o.x - y * o.y - z * o.z,
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w);
    }

    public Quat conjugate() { return new Quat(w, -x, -y, -z); }

    public double norm() { return Math.sqrt(w * w + x * x + y * y + z * z); }

    public Quat normalize() {
        double n = norm();
        return n == 0 ? IDENTITY : new Quat(w / n, x / n, y / n, z / n);
    }

    /** Повернуть вектор: q·v·q* (кватернион считается единичным). */
    public Vec3d rotate(Vec3d v) {
        return rotate(v.x(), v.y(), v.z());
    }

    public Vec3d rotate(double vx, double vy, double vz) {
        // t = 2·(q.xyz × v); v' = v + w·t + q.xyz × t
        double tx = 2 * (y * vz - z * vy);
        double ty = 2 * (z * vx - x * vz);
        double tz = 2 * (x * vy - y * vx);
        return new Vec3d(
                vx + w * tx + (y * tz - z * ty),
                vy + w * ty + (z * tx - x * tz),
                vz + w * tz + (x * ty - y * tx));
    }

    /** Направление взгляда. */
    public Vec3d forward() { return rotate(FORWARD); }

    /** Верх (макушка). */
    public Vec3d up() { return rotate(UP); }

    /** Правая рука. */
    public Vec3d right() { return rotate(RIGHT); }
}
