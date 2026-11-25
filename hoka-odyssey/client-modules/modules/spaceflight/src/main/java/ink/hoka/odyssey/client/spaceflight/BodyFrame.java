package ink.hoka.odyssey.client.spaceflight;

import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;

/**
 * Математика поворота тела и камеры (без Minecraft — проверяется тестами).
 * <p>
 * Соглашение — как у {@link FreeLook}/spacephys: кватернион Q переводит «свою» систему в мир, своя система — взгляд
 * (лицо) +Z, верх (макушка) +Y, левая рука +X. Так же устроена модель игрока у ядра: при {@code bodyYaw = 0} после
 * поворота {@code tilt} модель смотрит вдоль tilt·(+Z) и стоит вдоль tilt·(+Y) (vanilla setupRotations делает
 * Ry(180° − bodyRot) уже после tilt, а лицо модели в своих осях — −Z; Ry(180°)·(−Z) = +Z).
 * <ul>
 *   <li><b>свободно в пространстве</b> — тело = взгляд: лицом к прицелу, верх тела = верх камеры; лёжа (R) — вдоль
 *       взгляда, макушкой вперёд, грудью к низу камеры; поворот — вокруг середины тела;</li>
 *   <li><b>стоит на поверхности</b> (пол, стена, потолок; нормаль n) — верх тела = n, лицом — по взгляду,
 *       спроецированному на поверхность; ноги — на поверхности. Голова смотрит туда же, куда взгляд.</li>
 * </ul>
 */
final class BodyFrame {
    /** Лёжа: +90° вокруг своей поперечной оси — макушка (+Y) уходит вперёд (+Z), лицо (+Z) — вниз (−Y). */
    static final Quat PRONE = Quat.axisAngle(new Vec3d(1, 0, 0), Math.PI / 2);
    /** Камера игры смотрит вдоль −Z, взгляд FreeLook — вдоль +Z: поворот на 180° вокруг своего верха. */
    static final Quat CAMERA_FLIP = Quat.axisAngle(Quat.UP, Math.PI);
    /** Нормали граней блока; индекс + 1 уходит в биты 2–4 флагов поворота (0 — свободно). */
    static final Vec3d[] NORMALS = {
        new Vec3d(1, 0, 0), new Vec3d(-1, 0, 0), new Vec3d(0, 1, 0),
        new Vec3d(0, -1, 0), new Vec3d(0, 0, 1), new Vec3d(0, 0, -1)};
    /** Лёжа на поверхности: середина тела над ней на столько блоков (полтолщины туловища с запасом). */
    static final double LIE_LIFT = 0.15;
    /**
     * Флаги поворота (op 3): бит 0 — лёжа, бит 1 — действует, биты 2–4 — грань под ногами + 1 (0 — свободно),
     * бит 5 — рамка игрока повёрнута по этой грани ({@link SurfaceBox}: стоит на стене/потолке), бит 6 — тяготение
     * грани ({@link SurfaceGravity}: ходит по ней, как по земле).
     */
    static final int FLAG_PRONE = 1;
    static final int FLAG_ACTIVE = 2;
    static final int FLAG_BOX = 1 << 5;
    static final int FLAG_GRAVITY = 1 << 6;
    static final int SURFACE_SHIFT = 2;
    static final int SURFACE_MASK = 7 << SURFACE_SHIFT;

    private BodyFrame() {}

    static int flags(boolean active, boolean prone, int surface) {
        return flags(active, prone, surface, false);
    }

    /** box — рамка повёрнута по грани surface (только для стен и потолка; на полу рамка и так обычная). */
    static int flags(boolean active, boolean prone, int surface, boolean box) {
        int f = (active ? FLAG_ACTIVE : 0) | (prone ? FLAG_PRONE : 0);
        if (surface >= 0 && surface < NORMALS.length) {
            f |= (surface + 1) << SURFACE_SHIFT;
            if (box && SurfaceBox.turned(surface)) f |= FLAG_BOX;
        }
        return f;
    }

    /** Грань повёрнутой рамки из флагов: стена/потолок или −1 (рамка обычная). */
    static int boxOf(int flags) {
        int s = surfaceOf(flags);
        return (flags & FLAG_BOX) != 0 && SurfaceBox.turned(s) ? s : -1;
    }

    /** Грань под ногами из флагов: 0..5 или −1 (свободно; и для неизвестных значений 7). */
    static int surfaceOf(int flags) {
        int v = (flags & SURFACE_MASK) >>> SURFACE_SHIFT;
        return v >= 1 && v <= NORMALS.length ? v - 1 : -1;
    }

    /** Номер грани (0..5) для единичной нормали по оси; −1 — не нормаль грани. */
    static int normalIndex(Vec3d n) {
        if (n == null) return -1;
        for (int i = 0; i < NORMALS.length; i++) {
            if (NORMALS[i].dot(n) > 0.999) return i;
        }
        return -1;
    }

    /** Нормаль по номеру грани; null — не грань (свободно). */
    static Vec3d normal(int index) {
        return index >= 0 && index < NORMALS.length ? NORMALS[index] : null;
    }

    /**
     * Полный поворот камеры игры (CameraState.rotation) по взгляду FreeLook: Qcam = Qlook·Ry(π). Игра строит его как
     * rotationYXZ(π − yaw, −pitch, roll) — то же самое для углов из {@link FreeLook#decompose()} с roll камеры
     * = −roll FreeLook (см. тест CameraConventionTest).
     */
    static Quat camera(Quat look) {
        return look.mul(CAMERA_FLIP).normalize();
    }

    /** Поворот по направлению «вперёд» и «вверх» (ортонормированные): столбцы матрицы — up×fwd, up, fwd. */
    static Quat fromBasis(Vec3d fwd, Vec3d up) {
        Vec3d x = up.cross(fwd);
        double m00 = x.x(), m10 = x.y(), m20 = x.z();
        double m01 = up.x(), m11 = up.y(), m21 = up.z();
        double m02 = fwd.x(), m12 = fwd.y(), m22 = fwd.z();
        double tr = m00 + m11 + m22;
        double w, qx, qy, qz;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1) * 2;
            w = 0.25 * s;
            qx = (m21 - m12) / s;
            qy = (m02 - m20) / s;
            qz = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1 + m00 - m11 - m22) * 2;
            w = (m21 - m12) / s;
            qx = 0.25 * s;
            qy = (m01 + m10) / s;
            qz = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1 + m11 - m00 - m22) * 2;
            w = (m02 - m20) / s;
            qx = (m01 + m10) / s;
            qy = 0.25 * s;
            qz = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1 + m22 - m00 - m11) * 2;
            w = (m10 - m01) / s;
            qx = (m02 + m20) / s;
            qy = (m12 + m21) / s;
            qz = 0.25 * s;
        }
        return new Quat(w, qx, qy, qz).normalize();
    }

    /**
     * Тело, стоящее на поверхности с нормалью n: верх = n, лицом — по взгляду вдоль поверхности. Смотришь почти прямо
     * в поверхность (или от неё) — «вперёд» берётся по верху камеры: в пол — верх камеры, в небо — обратно ему
     * (непрерывно с обычным случаем при крене 0).
     */
    static Quat onSurface(Quat look, Vec3d n) {
        Vec3d f = look.forward();
        Vec3d fd = tangent(f, n);
        if (fd.lengthSquared() < 1e-3) {
            Vec3d u = tangent(look.up(), n);
            fd = f.dot(n) > 0 ? u.negate() : u;
            if (fd.lengthSquared() < 1e-9) fd = tangent(look.right().cross(n), n);  // на всякий случай
        }
        return fromBasis(fd.normalize(), n);
    }

    /** Основа тела (без «лёжа»): surface — номер грани под ногами или −1 (свободно). */
    static Quat base(Quat look, int surface) {
        Vec3d n = normal(surface);
        return n == null ? look : onSurface(look, n);
    }

    /** Тело с долей «лёжа» (0 — стоя, 1 — лёжа). */
    static Quat body(Quat base, double prone) {
        if (prone <= 0) return base;
        Quat lying = base.mul(PRONE);
        return prone >= 1 ? lying : FreeLook.slerp(base, lying, prone);
    }

    /**
     * Где должна быть середина модели (точка (0, h/2, 0) её системы) относительно позиции сущности (ног рамки):
     * свободно — середина рамки; стоя на поверхности — так, чтобы ноги были в точке касания (середина рамки − n ×
     * полразмера рамки вдоль n); лёжа на поверхности — середина тела чуть над ней.
     */
    static Vec3d centre(int surface, boolean prone, double height, double width) {
        Vec3d n = normal(surface);
        double half = n == null ? 0 : Math.abs(n.y()) * height / 2 + (Math.abs(n.x()) + Math.abs(n.z())) * width / 2;
        return centre(surface, prone, new Vec3d(0, height / 2, 0), half, height);
    }

    /**
     * То же по настоящей рамке сущности (она может быть повёрнута — {@link SurfaceBox}): mid — сдвиг позиция →
     * середина рамки, halfN — полразмера рамки вдоль нормали грани. Повёрнутая рамка стоит вдоль n — ноги модели
     * на поверхности, середина модели = середина рамки.
     */
    static Vec3d centre(int surface, boolean prone, Vec3d mid, double halfN, double height) {
        Vec3d n = normal(surface);
        if (n == null) return mid;
        Vec3d contact = mid.sub(n.scale(halfN));
        return contact.add(n.scale(prone ? LIE_LIFT : height / 2));
    }

    /** Сдвиг модели (мир, от ног сущности), чтобы её середина после поворота body оказалась в centre. */
    static Vec3d offset(Quat body, Vec3d centre, double height) {
        return centre.sub(body.rotate(0, height / 2, 0));
    }

    /** Поворот головы в системе тела по взгляду в мире: {yaw, pitch} в градусах (как у Minecraft). */
    static float[] head(Quat body, Vec3d worldLook) {
        Vec3d l = body.conjugate().rotate(worldLook);
        double len = l.length();
        // взгляд почти вдоль верха тела: курс головы не определён — 0 (иначе шум округления крутит голову вокруг шеи)
        double yaw = Math.hypot(l.x(), l.z()) < 1e-6 * len ? 0 : Math.toDegrees(Math.atan2(-l.x(), l.z()));
        double pitch = len < 1e-9 ? 0 : Math.toDegrees(Math.asin(FreeLook.clamp(-l.y() / len, -1, 1)));
        return new float[] {(float) yaw, (float) pitch};
    }

    /** Направление взгляда Minecraft по углам (как Entity#getViewVector). */
    static Vec3d dir(double yawDeg, double pitchDeg) {
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg), cp = Math.cos(p);
        return new Vec3d(-Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp);
    }

    static Vec3d tangent(Vec3d a, Vec3d n) {
        return a.sub(n.scale(a.dot(n)));
    }

    static Vec3d lerp(Vec3d a, Vec3d b, double t) {
        return a.add(b.sub(a).scale(t));
    }
}
