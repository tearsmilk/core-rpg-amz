package ink.hoka.odyssey.spacephys;

/**
 * Орбиты: элементы кеплеровой орбиты относительно тела, опорные скорости и численный прогноз траектории (HUD).
 */
public final class Orbit {
    private Orbit() {}

    /**
     * Элементы орбиты.
     *
     * @param mu              μ тела, блоки³/с²
     * @param semiMajorAxis   большая полуось a, блоки (+∞ — парабола; отрицательная — гипербола)
     * @param eccentricity    эксцентриситет e
     * @param periapsis       перицентр (наименьшее расстояние до центра), блоки
     * @param apoapsis        апоцентр, блоки; +∞ — орбита незамкнута (e ≥ 1)
     * @param period          период, с; +∞ — незамкнута
     * @param energy          удельная энергия ½v² − μ/r, блоки²/с²
     * @param angularMomentum удельный момент импульса r × v, блоки²/с
     * @param inclination     наклон плоскости орбиты к горизонтали карты (ось +Y), радианы, 0…π
     */
    public record Elements(double mu, double semiMajorAxis, double eccentricity, double periapsis, double apoapsis,
                           double period, double energy, Vec3d angularMomentum, double inclination) {
        /** Орбита замкнута (эллипс). */
        public boolean bound() { return eccentricity < 1 && energy < 0; }
    }

    /** Элементы по положению и скорости относительно центра тела (точечный кеплеров закон μ/r²). */
    public static Elements elements(Vec3d relPosition, Vec3d relVelocity, double mu) {
        double r = relPosition.length();
        double v2 = relVelocity.lengthSquared();
        Vec3d h = relPosition.cross(relVelocity);
        double h2 = h.lengthSquared();
        double energy = 0.5 * v2 - mu / r;
        Vec3d ev = relVelocity.cross(h).scale(1 / mu).sub(relPosition.scale(1 / r));
        double e = ev.length();
        double a = energy == 0 ? Double.POSITIVE_INFINITY : -mu / (2 * energy);
        double peri = h2 == 0 ? 0 : h2 / (mu * (1 + e));
        double apo = e < 1 ? h2 / (mu * (1 - e)) : Double.POSITIVE_INFINITY;
        double period = e < 1 && a > 0 ? 2 * Math.PI * Math.sqrt(a * a * a / mu) : Double.POSITIVE_INFINITY;
        double hl = Math.sqrt(h2);
        double inc = hl == 0 ? 0 : StrictMath.acos(Math.max(-1, Math.min(1, h.y() / hl)));
        return new Elements(mu, a, e, peri, apo, period, energy, h, inc);
    }

    /** Элементы орбиты состояния s относительно тела. */
    public static Elements elements(Body body, State s) {
        return elements(new Vec3d(s.x - body.x(), s.y - body.y(), s.z - body.z()), s.velocity(), body.mu());
    }

    /** Круговая скорость √(μ/r), блоки/с. */
    public static double circularSpeed(double mu, double r) { return Math.sqrt(mu / r); }

    /** Вторая космическая √(2μ/r), блоки/с. */
    public static double escapeSpeed(double mu, double r) { return Math.sqrt(2 * mu / r); }

    /** Период круговой орбиты 2π√(r³/μ), с. */
    public static double period(double mu, double r) { return 2 * Math.PI * Math.sqrt(r * r * r / mu); }

    /** Круговая скорость у чёрной дыры (Пачинский–Виита): v = √(μ·r)/(r − r_s). */
    public static double circularSpeedHole(double mu, double horizon, double r) {
        return Math.sqrt(mu * r) / (r - horizon);
    }

    /** Вторая космическая у чёрной дыры (Пачинский–Виита): √(2μ/(r − r_s)). */
    public static double escapeSpeedHole(double mu, double horizon, double r) {
        return Math.sqrt(2 * mu / (r - horizon));
    }

    /** Последняя устойчивая круговая орбита дыры (Пачинский–Виита): 3·r_s. */
    public static double isco(double horizon) { return 3 * horizon; }

    /**
     * Прогноз траектории.
     *
     * @param points   координаты x,y,z подряд (count точек; первая — начальная позиция)
     * @param count    число точек
     * @param captured дыра, поглотившая траекторию (последняя точка — на горизонте), или null
     */
    public record Prediction(double[] points, int count, Body captured) {
        public Vec3d point(int i) {
            if (i < 0 || i >= count) {
                throw new IndexOutOfBoundsException(i);
            }
            return new Vec3d(points[3 * i], points[3 * i + 1], points[3 * i + 2]);
        }

        public Vec3d last() { return point(count - 1); }
    }

    /**
     * Прогноз из состояния start на steps шагов по dt (start не меняется), каждая stride-я точка.
     * Например, HUD на 30 с: {@code predict(field, s, 600, Units.TICK, 10)} — 61 точка.
     */
    public static Prediction predict(Field field, State start, int steps, double dt, int stride) {
        return predict(field, start, steps, dt, stride, Integrator.DEFAULT);
    }

    public static Prediction predict(Field field, State start, int steps, double dt, int stride,
                                     Integrator integrator) {
        if (steps < 0 || stride < 1) {
            throw new IllegalArgumentException("steps ≥ 0, stride ≥ 1");
        }
        State s = start.copy();
        double[] pts = new double[3 * (steps / stride + 2)];
        int count = 0;
        pts[0] = s.x;
        pts[1] = s.y;
        pts[2] = s.z;
        count++;
        Body captured = null;
        for (int i = 1; i <= steps; i++) {
            captured = integrator.step(field, s, dt);
            if (captured != null || i % stride == 0 || i == steps) {
                pts[3 * count] = s.x;
                pts[3 * count + 1] = s.y;
                pts[3 * count + 2] = s.z;
                count++;
            }
            if (captured != null) {
                break;
            }
        }
        return new Prediction(pts, count, captured);
    }
}
