package ink.hoka.odyssey.spacephys;

import java.util.List;

/**
 * Гравитационное поле карты — сумма по всем её телам, без отсечки по «reach» (отсечка только по {@link Body#soi()}).
 * Неизменяемо и потокобезопасно: при изменении тел строится новый Field ({@link #of}), старый можно дочитать.
 * <p>
 * Законы (r — расстояние до центра тела, r̂ — направление от центра):
 * <ul>
 *   <li>PLANET/STAR снаружи (r ≥ R): a = −μ·r̂/r²; внутри (r &lt; R): a = −μ·r/R³ (однородный шар);</li>
 *   <li>SMALL: Пламмер, a = −μ·r/(r² + ε²)^{3/2};</li>
 *   <li>HOLE: Пачинский–Виита, a = −μ·r̂/(r − r_s)²; ISCO ≈ 3·r_s. Внутри горизонта поле конечно
 *       (зажато у горизонта) — захват ловит {@link Integrator}.</li>
 * </ul>
 * Только {@code +−*÷} и {@code Math.sqrt} (корректно округляемый по IEEE 754): одинаковые входы дают побитово
 * одинаковый результат на сервере (Java 25) и клиенте (Java 21).
 */
public final class Field {
    /** Пустое поле (тел нет). */
    public static final Field EMPTY = new Field(List.of());

    /** Ширина плавного затухания у границы сферы влияния, доля soi. */
    static final double SOI_FADE = 0.25;
    /** Минимальный зазор над горизонтом, доля r_s: ближе ускорение не растёт (без деления на 0). */
    static final double HORIZON_GAP = 1e-6;

    private final List<Body> bodies;
    private final int n;
    private final int[] kind;          // 0 — шар (PLANET/STAR), 1 — Пламмер, 2 — дыра
    private final double[] bx, by, bz, mu, radius, eps2, horizon, soi;

    private Field(List<Body> list) {
        bodies = List.copyOf(list);
        n = bodies.size();
        kind = new int[n];
        bx = new double[n];
        by = new double[n];
        bz = new double[n];
        mu = new double[n];
        radius = new double[n];
        eps2 = new double[n];
        horizon = new double[n];
        soi = new double[n];
        for (int i = 0; i < n; i++) {
            Body b = bodies.get(i);
            kind[i] = switch (b.kind()) {
                case PLANET, STAR -> 0;
                case SMALL -> 1;
                case HOLE -> 2;
            };
            bx[i] = b.x();
            by[i] = b.y();
            bz[i] = b.z();
            mu[i] = b.mu();
            radius[i] = b.radius();
            eps2[i] = b.softening() * b.softening();
            horizon[i] = b.horizon();
            soi[i] = b.soi();
        }
    }

    /** Поле из списка тел (список копируется). */
    public static Field of(List<Body> bodies) {
        return bodies.isEmpty() ? EMPTY : new Field(bodies);
    }

    public static Field of(Body... bodies) { return of(List.of(bodies)); }

    /** Тела поля (неизменяемый список). */
    public List<Body> bodies() { return bodies; }

    public boolean isEmpty() { return n == 0; }

    /**
     * Ускорение в точке, блоки/с², без аллокаций: результат пишется в out[off..off+2].
     */
    public void acceleration(double x, double y, double z, double[] out, int off) {
        double ax = 0, ay = 0, az = 0;
        for (int i = 0; i < n; i++) {
            double dx = x - bx[i], dy = y - by[i], dz = z - bz[i];
            double r2 = dx * dx + dy * dy + dz * dz;
            double r = Math.sqrt(r2);
            double k = accelK(i, r, r2);
            ax -= k * dx;
            ay -= k * dy;
            az -= k * dz;
        }
        out[off] = ax;
        out[off + 1] = ay;
        out[off + 2] = az;
    }

    /** Ускорение в точке, блоки/с². */
    public Vec3d acceleration(double x, double y, double z) {
        double[] a = new double[3];
        acceleration(x, y, z, a, 0);
        return new Vec3d(a[0], a[1], a[2]);
    }

    public Vec3d acceleration(Vec3d p) { return acceleration(p.x(), p.y(), p.z()); }

    /**
     * Потенциал φ, блоки²/с² (a = −∇φ; ноль на бесконечности). Для проверки энергии и HUD.
     * Затухание по soi здесь не учитывается: у тел с soi > 0 энергия вблизи границы сферы не сохраняется точно.
     */
    public double potential(double x, double y, double z) {
        double phi = 0;
        for (int i = 0; i < n; i++) {
            double dx = x - bx[i], dy = y - by[i], dz = z - bz[i];
            double r2 = dx * dx + dy * dy + dz * dz;
            double r = Math.sqrt(r2);
            phi -= switch (kind[i]) {
                case 0 -> {
                    double big = radius[i];
                    if (big > 0 && r < big) {
                        yield mu[i] * (3 * big * big - r2) / (2 * big * big * big);
                    }
                    double q = r2 + eps2[i];
                    yield q == 0 ? 0 : mu[i] / Math.sqrt(q);
                }
                case 1 -> {
                    double q = r2 + eps2[i];
                    yield q == 0 ? 0 : mu[i] / Math.sqrt(q);
                }
                default -> mu[i] / Math.max(r - horizon[i], horizon[i] * HORIZON_GAP);
            };
        }
        return phi;
    }

    public double potential(Vec3d p) { return potential(p.x(), p.y(), p.z()); }

    /**
     * Приливное растяжение тела длины L, блоки/с²: Δa ≈ Σ |∂a/∂r|·L (для шара снаружи — 2μL/r³).
     * Урон — при Δa &gt; {@link Units#TIDAL_DAMAGE_THRESHOLD}.
     */
    public double tidal(double x, double y, double z, double length) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double dx = x - bx[i], dy = y - by[i], dz = z - bz[i];
            double r2 = dx * dx + dy * dy + dz * dz;
            double r = Math.sqrt(r2);
            double w = fade(i, r);
            if (w == 0) {
                continue;
            }
            double g = switch (kind[i]) {
                case 0 -> {
                    double big = radius[i];
                    if (big > 0 && r < big) {
                        yield mu[i] / (big * big * big);
                    }
                    double q = r2 + eps2[i];
                    yield q == 0 ? 0 : 2 * mu[i] / (q * Math.sqrt(q));
                }
                case 1 -> {
                    double q = r2 + eps2[i];
                    if (q == 0) {
                        yield 0;
                    }
                    double q32 = q * Math.sqrt(q);
                    double radial = Math.abs(mu[i] * (eps2[i] - 2 * r2) / (q32 * q));
                    yield Math.max(radial, mu[i] / q32);
                }
                default -> {
                    double d = Math.max(r - horizon[i], horizon[i] * HORIZON_GAP);
                    yield 2 * mu[i] / (d * d * d);
                }
            };
            sum += w * g;
        }
        return sum * length;
    }

    /** Приливное растяжение игрока (L = {@link Units#BODY_LENGTH}). */
    public double tidal(double x, double y, double z) { return tidal(x, y, z, Units.BODY_LENGTH); }

    /** Тело с наибольшим вкладом в ускорение в точке (для SAS и HUD); null — поле пустое или везде ноль. */
    public Body dominant(double x, double y, double z) {
        Body best = null;
        double bestA = 0;
        for (int i = 0; i < n; i++) {
            double dx = x - bx[i], dy = y - by[i], dz = z - bz[i];
            double r2 = dx * dx + dy * dy + dz * dz;
            double a = accelK(i, Math.sqrt(r2), r2) * Math.sqrt(r2);
            if (a > bestA) {
                bestA = a;
                best = bodies.get(i);
            }
        }
        return best;
    }

    /** Чёрная дыра, в горизонте которой находится точка; null — ни в одной. */
    public Body horizonAt(double x, double y, double z) {
        for (int i = 0; i < n; i++) {
            if (kind[i] == 2) {
                double dx = x - bx[i], dy = y - by[i], dz = z - bz[i];
                if (dx * dx + dy * dy + dz * dz <= horizon[i] * horizon[i]) {
                    return bodies.get(i);
                }
            }
        }
        return null;
    }

    /**
     * Наименьшее «динамическое время» τ = √(L³/μ) по всем телам в точке, с (L — расстояние, для дыры — зазор
     * до горизонта, внутри шара — его радиус). По нему {@link Integrator} выбирает число подшагов.
     * {@code +∞} — тел нет.
     */
    public double dynamicalTime(double x, double y, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (mu[i] <= 0) {
                continue;
            }
            double dx = x - bx[i], dy = y - by[i], dz = z - bz[i];
            double r2 = dx * dx + dy * dy + dz * dz;
            double r = Math.sqrt(r2);
            if (soi[i] > 0 && r >= soi[i]) {
                continue;
            }
            double len = switch (kind[i]) {
                case 0 -> Math.max(Math.max(r, radius[i]), Math.sqrt(eps2[i]));
                case 1 -> Math.sqrt(r2 + eps2[i]);
                default -> Math.max(r - horizon[i], horizon[i] * HORIZON_GAP);
            };
            if (len == 0) {
                continue;
            }
            double tau = Math.sqrt(len * len * len / mu[i]);
            if (tau < best) {
                best = tau;
            }
        }
        return best;
    }

    /** Множитель k вклада тела i (a = −k·(p − центр)), с учётом затухания по soi. */
    private double accelK(int i, double r, double r2) {
        double w = fade(i, r);
        if (w == 0) {
            return 0;
        }
        double k = switch (kind[i]) {
            case 0 -> {
                // шар: снаружи μ/r³, внутри μ/R³; без радиуса — точка (со смягчением, если задано)
                double big = radius[i];
                if (big > 0 && r < big) {
                    yield mu[i] / (big * big * big);
                }
                double q = r2 + eps2[i];
                yield q == 0 ? 0 : mu[i] / (q * Math.sqrt(q));
            }
            case 1 -> {
                double q = r2 + eps2[i];
                yield q == 0 ? 0 : mu[i] / (q * Math.sqrt(q));
            }
            default -> {
                if (r == 0) {
                    yield 0;
                }
                double d = Math.max(r - horizon[i], horizon[i] * HORIZON_GAP);
                yield mu[i] / (d * d * r);
            }
        };
        return k * w;
    }

    /** Затухание у границы сферы влияния: 1 внутри (1 − SOI_FADE)·soi, 0 за soi, между — smoothstep. */
    private double fade(int i, double r) {
        double s = soi[i];
        if (s <= 0) {
            return 1;
        }
        if (r >= s) {
            return 0;
        }
        double t = (s - r) / (s * SOI_FADE);
        if (t >= 1) {
            return 1;
        }
        return t * t * (3 - 2 * t);
    }
}
