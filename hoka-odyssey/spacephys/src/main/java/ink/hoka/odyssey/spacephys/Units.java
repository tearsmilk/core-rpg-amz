package ink.hoka.odyssey.spacephys;

/**
 * Единицы и опорные константы. 1 блок = 1 м, 20 тиков в секунду (dt = 0,05 с).
 * <p>
 * Вся библиотека считает «в секундах»: скорость — блоки/с, ускорение — блоки/с², μ = G·M — блоки³/с².
 * Vanilla-клиент Minecraft хранит скорость в блоках/тик — для него есть {@link #toPerTick}/{@link #fromPerTick}
 * и {@link #accelToPerTick}/{@link #accelFromPerTick}. Minestom ({@code Entity#setVelocity}) — уже в блоках/с.
 */
public final class Units {
    /** Тиков в секунду. */
    public static final int TPS = 20;
    /** Длительность тика, с. */
    public static final double TICK = 1.0 / TPS;

    // --- значения по умолчанию из проекта (конфиги модулей могут их менять) ---
    /** Ускорение ранца, блоки/с² (0,005 блоков/тик²). */
    public static final double THRUST_ACCEL = 2.0;
    /** Запас Δv одного баллона, блоки/с. */
    public static final double DELTA_V_CAPACITY = 60.0;
    /** Доля «динамического времени» на один подшаг интегратора. */
    public static final double ETA = 0.03;
    /** Наибольшее число подшагов за тик. */
    public static final int MAX_SUBSTEPS = 16;
    /** Характерный размер тела игрока для приливов, блоки. */
    public static final double BODY_LENGTH = 1.8;
    /** Порог приливного урона, блоки/с². */
    public static final double TIDAL_DAMAGE_THRESHOLD = 5.0;
    /** Порог урона от удара по нормальной составляющей Δv, блоки/с. */
    public static final double IMPACT_DAMAGE_THRESHOLD = 8.0;
    /** Коэффициент восстановления при отскоке от блока. */
    public static final double RESTITUTION = 0.2;

    private Units() {}

    /** блоки/с → блоки/тик */
    public static double toPerTick(double perSecond) { return perSecond / TPS; }

    /** блоки/тик → блоки/с */
    public static double fromPerTick(double perTick) { return perTick * TPS; }

    /** блоки/с² → блоки/тик² (делим на 400) */
    public static double accelToPerTick(double perSecond2) { return perSecond2 / (TPS * TPS); }

    /** блоки/тик² → блоки/с² */
    public static double accelFromPerTick(double perTick2) { return perTick2 * (TPS * TPS); }

    public static Vec3d toPerTick(Vec3d v) { return v.scale(1.0 / TPS); }

    public static Vec3d fromPerTick(Vec3d v) { return v.scale(TPS); }

    /** μ по ускорению свободного падения на поверхности g_s (блоки/с²) и радиусу R: μ = g_s·R². */
    public static double muFromSurfaceGravity(double surfaceGravity, double radius) {
        return surfaceGravity * radius * radius;
    }

    /** μ по круговой орбите радиуса r с периодом T (с): μ = 4π²r³/T². */
    public static double muFromOrbit(double r, double period) {
        return 4 * Math.PI * Math.PI * r * r * r / (period * period);
    }

    /** Ускорение на поверхности по μ и R: g_s = μ/R². */
    public static double surfaceGravity(double mu, double radius) { return mu / (radius * radius); }

    /**
     * Радиус сферы влияния малого тела: r_SOI = a·(m/M)^{2/5}, где a — расстояние до главного тела,
     * m/M = μ_малого/μ_главного. StrictMath — чтобы сервер и клиент получили одно и то же число.
     */
    public static double sphereOfInfluence(double distanceToPrimary, double muSmall, double muPrimary) {
        return distanceToPrimary * StrictMath.pow(muSmall / muPrimary, 0.4);
    }
}
