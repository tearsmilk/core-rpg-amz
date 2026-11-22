package ink.hoka.odyssey.client.spaceflight;

import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;

/**
 * Свободная ориентация взгляда на кватернионе (без «шарнирного замка»). Соглашение spacephys/Minecraft:
 * Q = Ry(−yaw)·Rx(pitch)·Rz(roll), взгляд +Z, повороты в своих осях — умножение справа.
 * Без Minecraft — чистая математика.
 */
final class FreeLook {
    private static final Vec3d AXIS_X = new Vec3d(1, 0, 0);

    private Quat q = Quat.IDENTITY;

    /** Углы последнего разложения (градусы), см. {@link #decompose()}. */
    float yaw, pitch, roll;

    Quat orientation() { return q; }

    void set(Quat o) { q = o.normalize(); }

    void reset(double yawDeg, double pitchDeg) { q = Quat.fromYawPitchRoll(yawDeg, pitchDeg, 0); }

    /** Поворот мышью в своих осях: рыскание вокруг своего верха, тангаж вокруг своей поперечной оси. */
    void turn(double dYawDeg, double dPitchDeg) {
        if (dYawDeg != 0) q = q.mul(Quat.axisAngle(Quat.UP, -Math.toRadians(dYawDeg)));
        if (dPitchDeg != 0) q = q.mul(Quat.axisAngle(AXIS_X, Math.toRadians(dPitchDeg)));
        q = q.normalize();
    }

    /** Крен вокруг оси взгляда. */
    void roll(double dRollDeg) {
        if (dRollDeg == 0) return;
        q = q.mul(Quat.axisAngle(Quat.FORWARD, Math.toRadians(dRollDeg))).normalize();
    }

    /**
     * Разложить на yaw/pitch/roll Minecraft. pitch всегда в [−90; 90]: если взгляд ушёл «за голову», это
     * получается само — yaw +180° и roll +180°, так что петля через верх проходит без рывка. Композиция
     * углов в точности даёт исходную ориентацию, поэтому вблизи вертикали (где yaw и roll вырождены)
     * картинка всё равно непрерывна.
     */
    void decompose() {
        Vec3d f = q.forward();
        Vec3d u = q.up();
        double sp = clamp(-f.y(), -1, 1);
        double p = Math.asin(sp);
        double y;
        double r;
        if (Math.sqrt(f.x() * f.x() + f.z() * f.z()) > 1e-6) {
            y = Math.atan2(-f.x(), f.z());
            Quat q0 = Quat.fromYawPitchRoll(Math.toDegrees(y), Math.toDegrees(p), 0);
            Vec3d u0 = q0.up();
            Vec3d r0 = q0.right();
            r = Math.atan2(u.dot(r0), u.dot(u0));
        } else if (sp > 0) {                         // строго вниз: верх смотрит туда, куда «yaw»
            y = Math.atan2(-u.x(), u.z());
            r = 0;
        } else {                                     // строго вверх
            y = Math.atan2(u.x(), -u.z());
            r = 0;
        }
        yaw = (float) Math.toDegrees(y);
        pitch = (float) Math.toDegrees(p);
        roll = (float) Math.toDegrees(r);
    }

    /**
     * Выровнять крен по поверхности: повернуть вокруг оси взгляда так, чтобы верх камеры смотрел как можно ближе
     * к нормали n (доля t ∈ [0; 1] от нужного угла). Тангаж не трогается — мышь по-прежнему двигает взгляд
     * относительно экрана. Если смотришь почти прямо на поверхность или от неё, крен не определён — ничего.
     */
    void alignRoll(Vec3d n, double t) {
        if (t <= 0) return;
        Vec3d f = q.forward();
        Vec3d u = q.up();
        Vec3d np = n.sub(f.scale(f.dot(n)));
        double len = np.length();
        if (len < 0.2) return;
        np = np.scale(1 / len);
        double ang = Math.atan2(f.dot(u.cross(np)), clamp(u.dot(np), -1, 1));
        if (Math.abs(ang) < 1e-5) return;
        double k = t * Math.min(1, (len - 0.2) / 0.3);         // у вырожденного случая — мягко
        Quat q1 = Quat.axisAngle(f, ang * k).mul(q).normalize();
        Quat q2 = Quat.axisAngle(f, -ang * k).mul(q).normalize();
        // берём поворот, который действительно приближает верх к нормали (иначе камеру раскручивало на 360°)
        Quat best = q1.up().dot(np) >= q2.up().dot(np) ? q1 : q2;
        if (best.up().dot(np) > u.dot(np)) q = best;
    }

    /** Плавно повернуть к ориентации target: доля t ∈ [0; 1]. @return угол до цели после шага (радианы) */
    double approach(Quat target, double t) {
        q = slerp(q, target, t);
        return angle(q, target);
    }

    static double angle(Quat a, Quat b) {
        double d = Math.abs(a.w() * b.w() + a.x() * b.x() + a.y() * b.y() + a.z() * b.z());
        return 2 * Math.acos(clamp(d, 0, 1));
    }

    static Quat slerp(Quat a, Quat b, double t) {
        double d = a.w() * b.w() + a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
        double bw = b.w(), bx = b.x(), by = b.y(), bz = b.z();
        if (d < 0) {                                  // короткий путь
            d = -d;
            bw = -bw; bx = -bx; by = -by; bz = -bz;
        }
        double ka;
        double kb;
        if (d > 0.9995) {
            ka = 1 - t;
            kb = t;
        } else {
            double th = Math.acos(d);
            double s = Math.sin(th);
            ka = Math.sin((1 - t) * th) / s;
            kb = Math.sin(t * th) / s;
        }
        return new Quat(a.w() * ka + bw * kb, a.x() * ka + bx * kb, a.y() * ka + by * kb, a.z() * ka + bz * kb)
                .normalize();
    }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
