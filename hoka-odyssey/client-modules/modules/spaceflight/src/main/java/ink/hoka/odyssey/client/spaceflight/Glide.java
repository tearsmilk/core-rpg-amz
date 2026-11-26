package ink.hoka.odyssey.client.spaceflight;

import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;

/**
 * Плавный переезд точки (глаз камеры) после смены рамки (без Minecraft — проверяется тестами). Позиция игрока при
 * смене грани сдвигается сразу (рамку и столкновения считает игра), а камера едет от прежнего места к новому за
 * заданное время с мягким стартом и остановкой ({@link SurfaceGravity#ease}).
 * <p>
 * Переход на соседнюю грань поворачивает камеру на угол θ вокруг оси a (кратчайший поворот n → n'). Тогда глаза идут
 * по дуге вокруг ребра: есть единственная точка P (в плоскости поперёк a), поворот вокруг которой на θ переводит
 * прежние глаза в новые, — глаза поворачиваются вокруг неё ровно так же, как камера (вдоль a — по прямой). Внешний
 * угол — дуга снаружи вокруг ребра, внутренний — внутри угла комнаты; без поворота (вход, выход) — по прямой.
 * <p>
 * {@link #offset} — сдвиг «показанные глаза − новые глаза» (мир) в момент now: прибавляется к настоящим глазам, так
 * что игрок может идти, пока камера доезжает.
 */
final class Glide {
    /** Поворот меньше этого (радианы) — по прямой: центр дуги ушёл бы далеко. */
    static final double MIN_ARC = Math.toRadians(5);

    private final Vec3d from;
    private final Vec3d to;
    private final Vec3d axis;
    private final double angle;
    private final Vec3d pivot;
    private final long t0;
    private final long durNs;

    private Glide(Vec3d from, Vec3d to, Vec3d axis, double angle, Vec3d pivot, long t0, long durNs) {
        this.from = from;
        this.to = to;
        this.axis = axis;
        this.angle = angle;
        this.pivot = pivot;
        this.t0 = t0;
        this.durNs = durNs;
    }

    /**
     * @param from прежние (показанные) глаза, мир
     * @param to   новые глаза, мир
     * @param turn поворот камеры при переходе (мир), {@link Quat#IDENTITY} — без поворота
     */
    static Glide of(Vec3d from, Vec3d to, Quat turn, long t0, double seconds) {
        long dur = Math.max(1, (long) (seconds * 1e9));
        Quat q = turn.w() < 0 ? new Quat(-turn.w(), -turn.x(), -turn.y(), -turn.z()) : turn;
        double sinHalf = Math.sqrt(q.x() * q.x() + q.y() * q.y() + q.z() * q.z());
        double angle = 2 * Math.atan2(sinHalf, q.w());
        if (angle < MIN_ARC || sinHalf < 1e-9) return new Glide(from, to, null, 0, null, t0, dur);
        Vec3d a = new Vec3d(q.x() / sinHalf, q.y() / sinHalf, q.z() / sinHalf);
        // центр дуги в плоскости поперёк a: (I − R)·P = to⊥ − R·from⊥, (I − R)⁻¹w = ((1 − c)·w + s·a×w) / (2 − 2c)
        Vec3d f = perp(from, a), t = perp(to, a);
        double c = Math.cos(angle), s = Math.sin(angle);
        Vec3d w = t.sub(rotate(f, a, c, s));
        Vec3d p = w.scale(1 - c).add(a.cross(w).scale(s)).scale(1 / (2 - 2 * c));
        return new Glide(from, to, a, angle, p, t0, dur);
    }

    /** Пройденная доля 0..1 (с мягким стартом и остановкой). */
    double progress(long now) {
        double s = (now - t0) / (double) durNs;
        return s < 0 ? 1 : SurfaceGravity.ease(s);
    }

    boolean done(long now) { return progress(now) >= 1; }

    /** Где сейчас показанная точка, мир. */
    Vec3d at(long now) {
        double e = progress(now);
        if (e >= 1) return to;
        if (axis == null) return BodyFrame.lerp(from, to, e);
        double th = angle * e;
        Vec3d f = perp(from, axis);
        Vec3d around = pivot.add(rotate(f.sub(pivot), axis, Math.cos(th), Math.sin(th)));
        double along = from.dot(axis) + (to.dot(axis) - from.dot(axis)) * e;
        return around.add(axis.scale(along));
    }

    /** Сдвиг показанной точки от новой (мир); ZERO — доехали. */
    Vec3d offset(long now) {
        return done(now) ? Vec3d.ZERO : at(now).sub(to);
    }

    private static Vec3d perp(Vec3d v, Vec3d a) {
        return v.sub(a.scale(v.dot(a)));
    }

    /** Поворот вектора поперёк единичной оси a на угол с косинусом c и синусом s. */
    private static Vec3d rotate(Vec3d v, Vec3d a, double c, double s) {
        return v.scale(c).add(a.cross(v).scale(s));
    }
}
