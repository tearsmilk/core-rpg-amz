package ink.hoka.odyssey.gravzones;

/**
 * Пересчёт силы зоны в атрибуты игрока и скорость выброса (без Minestom — для тестов). Физика ванильного клиента:
 * за тик y += vy, затем vy = (vy − гравитация) × 0.98; прыжок с земли задаёт vy = jump_strength.
 */
final class Physics {
    /** Ванильная гравитация игрока, блоков/тик². */
    static final double VANILLA_GRAVITY = 0.08;
    /** Ванильная сила прыжка (атрибут jump_strength), блоков/тик. */
    static final double VANILLA_JUMP = 0.42;
    static final double DRAG = 0.98;
    static final double MIN_G = 0.1;
    static final double MAX_G = 10;
    static final double MAX_VACUUM = 13;
    static final int TPS = 20;

    private Physics() {
    }

    /** Допустимая сила: 0.1…10 или вакуум (больше 10, до 13). */
    static boolean valid(double g) {
        return Double.isFinite(g) && g >= MIN_G && g <= MAX_VACUUM;
    }

    static boolean isVacuum(double g) {
        return g > MAX_G;
    }

    /** Модификатор ADD_MULTIPLIED_TOTAL к гравитации: значение × (1 + amount) = 0.08 × g. */
    static double gravityAmount(double g) {
        return g - 1;
    }

    /**
     * Во сколько раз меняется сила прыжка. Чтобы прыжок в слабой гравитации был плавным, а не «выстрелом» на десятки
     * блоков, сила прыжка уменьшается вместе с гравитацией: множитель g^weak (g &lt; 1) или g^strong (g &gt; 1).
     * Высота прыжка ≈ v²/2g ∝ g^(2·e − 1), время в воздухе ≈ 2v/g ∝ g^(e − 1).
     */
    static double jumpFactor(double g, double weakExponent, double strongExponent) {
        if (g == 1) {
            return 1;
        }
        return Math.pow(g, g < 1 ? weakExponent : strongExponent);
    }

    /** Прыжок с ровной земли: {высота в блоках, тиков в воздухе}. */
    static double[] jump(double jumpStrength, double gravityPerTick) {
        double y = 0;
        double v = jumpStrength;
        double top = 0;
        int ticks = 0;
        while (ticks < 20 * 60) {
            y += v;
            v = (v - gravityPerTick) * DRAG;
            ticks++;
            top = Math.max(top, y);
            if (y <= 0) {
                break;
            }
        }
        return new double[]{top, ticks};
    }

    /** Прыжок в зоне силой g: {высота, тиков}. */
    static double[] jumpIn(double g, double weakExponent, double strongExponent) {
        return jump(VANILLA_JUMP * jumpFactor(g, weakExponent, strongExponent), VANILLA_GRAVITY * g);
    }

    /**
     * Во сколько раз увеличить safe_fall_distance: если прыжок в зоне выше обычного, падение с высоты своего прыжка
     * не должно ранить. Не меньше 1.
     */
    static double safeFallFactor(double g, double weakExponent, double strongExponent) {
        double h = jumpIn(g, weakExponent, strongExponent)[0];
        double h0 = jump(VANILLA_JUMP, VANILLA_GRAVITY)[0];
        return Math.max(1, h / h0);
    }

    /**
     * Скорость выброса (блоков/сек) для вакуумной зоны: до 11 — как у 11, между 11 и 12, 12 и 13 — линейно.
     */
    static double launchSpeed(double g, double at11, double at12, double at13) {
        if (g <= 11) {
            return at11;
        }
        if (g <= 12) {
            return at11 + (at12 - at11) * (g - 11);
        }
        return at12 + (at13 - at12) * (Math.min(g, MAX_VACUUM) - 12);
    }

    static long volume(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return (long) (Math.abs(maxX - minX) + 1) * (Math.abs(maxY - minY) + 1) * (Math.abs(maxZ - minZ) + 1);
    }

    /** Число для сообщений: без лишних нулей. */
    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) {
            return String.valueOf((long) v);
        }
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
