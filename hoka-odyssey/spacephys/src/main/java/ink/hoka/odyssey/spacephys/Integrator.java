package ink.hoka.odyssey.spacephys;

/**
 * Интеграторы движения в поле {@link Field} на фиксированных тиках.
 * <ul>
 *   <li>{@link #step} — kick-drift-kick (Velocity Verlet) с подшагами: n = ceil(dt / (η·τ)), τ = √(r³/μ) — самое
 *       короткое динамическое время в точке, n ≤ maxSubsteps. Симплектичен: энергия не «уплывает», момент импульса
 *       в центральном поле сохраняется до округления. Для сервера: предметы, прогноз орбиты.
 *       Схема {@link Scheme#YOSHIDA4} (по умолчанию) — тот же KDK, составленный из трёх шагов по Ёсиде: 4-й порядок,
 *       три вычисления поля на подшаг. Простой KDK 2-го порядка на орбите r = 50, T = 60 с за 100 оборотов отстаёт
 *       по фазе на ≈ 0,6 % (ошибка периода ≈ (ω·dt)²/3), Ёсида — на ~10⁻⁷. Простой KDK — {@link #VERLET}.</li>
 *   <li>{@link #kick}/{@link #stepEuler} — симплектический Эйлер: v += a(x)·dt, затем x += v·dt. Так считает
 *       клиент: {@code kick} меняет скорость, а сдвиг делает vanilla move с коллизиями.</li>
 * </ul>
 * Захват чёрной дырой: каждый сдвиг проверяется как отрезок (а не только конечная точка), поэтому быстрое падение
 * не «прошивает» горизонт. При захвате позиция ставится в точку входа на горизонт, скорость не меняется, метод
 * возвращает дыру. Тяга (ax, ay, az) — постоянное добавочное ускорение на весь тик, блоки/с².
 * <p>
 * Неизменяем и потокобезопасен (сам по себе; {@link State} — нет).
 */
public final class Integrator {
    /** Схема шага {@link #step}. */
    public enum Scheme {
        /** Kick-drift-kick (Velocity Verlet), 2-й порядок, одно вычисление поля на подшаг. */
        VERLET(new double[] {1}),
        /** Композиция Ёсиды из трёх KDK, 4-й порядок, три вычисления поля на подшаг. */
        YOSHIDA4(yoshida());

        private final double[] weights;

        Scheme(double[] weights) { this.weights = weights; }

        private static double[] yoshida() {
            double cbrt2 = StrictMath.cbrt(2);
            double w1 = 1 / (2 - cbrt2);
            double w0 = -cbrt2 / (2 - cbrt2);
            return new double[] {w1, w0, w1};
        }
    }

    /** Ёсида 4-го порядка, η = {@link Units#ETA}, не больше {@link Units#MAX_SUBSTEPS} подшагов. */
    public static final Integrator DEFAULT = new Integrator(Scheme.YOSHIDA4, Units.ETA, Units.MAX_SUBSTEPS);
    /** Простой KDK 2-го порядка (дешевле втрое), η = {@link Units#ETA}. */
    public static final Integrator VERLET = new Integrator(Scheme.VERLET, Units.ETA, Units.MAX_SUBSTEPS);

    private final Scheme scheme;
    private final double eta;
    private final int maxSubsteps;

    /** Ёсида 4-го порядка с заданными η и пределом подшагов. */
    public Integrator(double eta, int maxSubsteps) { this(Scheme.YOSHIDA4, eta, maxSubsteps); }

    public Integrator(Scheme scheme, double eta, int maxSubsteps) {
        if (scheme == null) {
            throw new IllegalArgumentException("scheme = null");
        }
        this.scheme = scheme;
        if (!(eta > 0) || !Double.isFinite(eta)) {
            throw new IllegalArgumentException("eta = " + eta + ": нужно число > 0");
        }
        if (maxSubsteps < 1) {
            throw new IllegalArgumentException("maxSubsteps = " + maxSubsteps + ": нужно ≥ 1");
        }
        this.eta = eta;
        this.maxSubsteps = maxSubsteps;
    }

    public Scheme scheme() { return scheme; }

    public double eta() { return eta; }

    public int maxSubsteps() { return maxSubsteps; }

    /** Число подшагов для шага dt из точки (x, y, z): от 1 до maxSubsteps. */
    public int substeps(Field field, double x, double y, double z, double dt) {
        double tau = field.dynamicalTime(x, y, z);
        if (!(tau < Double.POSITIVE_INFINITY)) {
            return 1;
        }
        double k = Math.ceil(dt / (eta * tau));
        if (!(k >= 1)) {
            return 1;
        }
        return k >= maxSubsteps ? maxSubsteps : (int) k;
    }

    /** Шаг KDK без тяги. @return захватившая дыра или null */
    public Body step(Field field, State s, double dt) { return step(field, s, dt, 0, 0, 0); }

    /** Шаг KDK с постоянной тягой. @return захватившая дыра или null */
    public Body step(Field field, State s, double dt, Vec3d thrust) {
        return step(field, s, dt, thrust.x(), thrust.y(), thrust.z());
    }

    /**
     * Шаг kick-drift-kick (по схеме {@link #scheme()}) длиной dt (с) с подшагами и постоянным добавочным ускорением (ax, ay, az).
     *
     * @return захватившая дыра (позиция — на её горизонте) или null
     */
    public Body step(Field field, State s, double dt, double ax, double ay, double az) {
        Body inside = field.horizonAt(s.x, s.y, s.z);
        if (inside != null) {
            return inside;
        }
        if (field.isEmpty()) {
            // без тел — точная прямая (и без лишних округлений: при нулевой тяге скорость не меняется вовсе)
            if (ax != 0 || ay != 0 || az != 0) {
                double h2 = dt * 0.5;
                s.vx += ax * h2;
                s.vy += ay * h2;
                s.vz += az * h2;
                drift(s, dt);
                s.vx += ax * h2;
                s.vy += ay * h2;
                s.vz += az * h2;
            } else {
                drift(s, dt);
            }
            return null;
        }
        int n = substeps(field, s.x, s.y, s.z, dt);
        double h = dt / n;
        double[] w = scheme.weights;
        double[] a = new double[3];
        field.acceleration(s.x, s.y, s.z, a, 0);   // ускорение конца шага = начало следующего (одно на стык)
        for (int i = 0; i < n; i++) {
            for (double wk : w) {
                double hk = h * wk;
                double half = hk * 0.5;
                s.vx += (a[0] + ax) * half;
                s.vy += (a[1] + ay) * half;
                s.vz += (a[2] + az) * half;
                Body hole = driftChecked(field, s, hk);
                if (hole != null) {
                    return hole;
                }
                field.acceleration(s.x, s.y, s.z, a, 0);
                s.vx += (a[0] + ax) * half;
                s.vy += (a[1] + ay) * half;
                s.vz += (a[2] + az) * half;
            }
        }
        return null;
    }

    /**
     * Только скорость, как у клиента: v += (a(x) + тяга)·dt. Позицию двигает vanilla move.
     * Дыры здесь не проверяются — клиент проверяет {@link Field#horizonAt} после move.
     */
    public void kick(Field field, State s, double dt, double ax, double ay, double az) {
        double[] a = new double[3];
        field.acceleration(s.x, s.y, s.z, a, 0);
        s.vx += (a[0] + ax) * dt;
        s.vy += (a[1] + ay) * dt;
        s.vz += (a[2] + az) * dt;
    }

    /** {@link #kick} без тяги. */
    public void kick(Field field, State s, double dt) { kick(field, s, dt, 0, 0, 0); }

    /**
     * Полный шаг симплектического Эйлера без коллизий — то же, что делает клиент (kick + move), для «призрака»
     * валидатора и прогнозов. @return захватившая дыра или null
     */
    public Body stepEuler(Field field, State s, double dt, double ax, double ay, double az) {
        Body inside = field.horizonAt(s.x, s.y, s.z);
        if (inside != null) {
            return inside;
        }
        kick(field, s, dt, ax, ay, az);
        return driftChecked(field, s, dt);
    }

    public Body stepEuler(Field field, State s, double dt) { return stepEuler(field, s, dt, 0, 0, 0); }

    private static void drift(State s, double h) {
        s.x += s.vx * h;
        s.y += s.vy * h;
        s.z += s.vz * h;
    }

    /** Сдвиг x += v·h с проверкой пересечения горизонтов отрезком; при захвате позиция — точка входа. */
    private static Body driftChecked(Field field, State s, double h) {
        double x0 = s.x, y0 = s.y, z0 = s.z;
        double dx = s.vx * h, dy = s.vy * h, dz = s.vz * h;
        s.x = x0 + dx;
        s.y = y0 + dy;
        s.z = z0 + dz;
        Body hit = null;
        double best = 2;
        for (Body b : field.bodies()) {
            if (!b.isHole()) {
                continue;
            }
            double t = segmentEntry(x0 - b.x(), y0 - b.y(), z0 - b.z(), dx, dy, dz, b.horizon());
            if (t < best) {
                best = t;
                hit = b;
            }
        }
        if (hit != null) {
            s.x = x0 + dx * best;
            s.y = y0 + dy * best;
            s.z = z0 + dz * best;
        }
        return hit;
    }

    /**
     * Первый параметр t ∈ [0, 1], при котором точка f + t·d попадает в шар радиуса rs вокруг начала координат;
     * 2 — не попадает.
     */
    static double segmentEntry(double fx, double fy, double fz, double dx, double dy, double dz, double rs) {
        double c = fx * fx + fy * fy + fz * fz - rs * rs;
        if (c <= 0) {
            return 0;
        }
        double a = dx * dx + dy * dy + dz * dz;
        if (a == 0) {
            return 2;
        }
        double b = 2 * (fx * dx + fy * dy + fz * dz);
        if (b >= 0) {
            return 2; // удаляемся от центра или летим по касательной
        }
        double disc = b * b - 4 * a * c;
        if (disc < 0) {
            return 2;
        }
        double t = (-b - Math.sqrt(disc)) / (2 * a);
        return t >= 0 && t <= 1 ? t : 2;
    }
}
