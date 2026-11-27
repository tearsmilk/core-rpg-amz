package ink.hoka.odyssey.spacephys;

/**
 * Отдача выстрела в свободном полёте (вакуум, без опоры): отдельная затухающая скорость поверх скорости полёта.
 * Выстрел задаёт не скорость, а путь — на сколько блоков отнесёт стрелка ({@link #add}). Скорость отдачи гаснет
 * за тик в r раз (по умолчанию {@link #DECAY}, постоянная времени {@link #TAU}), и сумма сдвигов по тикам ровно равна этому пути:
 * v₀·dt·(1 + r + r² + …) = v₀·dt / (1 − r) = путь. Стабилизатор и ранец её не трогают — она не в скорости полёта,
 * поэтому путь один и тот же при любых клавишах. Выстрелы складываются (очередь АК уносит дальше).
 * <p>
 * Порядок за тик: {@link #velocity()} — прибавить к скорости этого тика, потом {@link #step()}.
 */
public final class Recoil {
    /** Постоянная времени затухания, с: большая часть пути — за ~1 с. */
    public static final double TAU = 0.35;
    /** Во сколько раз скорость отдачи гаснет за тик. */
    public static final double DECAY = Math.exp(-Units.TICK / TAU);
    /**
     * Хвост: скорость отдачи меньше этой (блоки/с; 0,0035 блока/тик) — весь остаток пути одним тиком и конец. Иначе
     * vanilla обнулил бы скорость меньше 0,003 блока/тик ({@link VanillaMotion#NEGLIGIBLE}) и ~0,02 блока потерялось бы.
     */
    public static final double TAIL = 0.07;

    private double vx;
    private double vy;
    private double vz;
    /** Сейчас — последний тик (скорость = весь остаток пути). */
    private boolean tail;
    /** Во сколько раз скорость гаснет за тик. */
    private final double decay;

    /** Затухание {@link #DECAY} (клиентский модуль spaceflight). */
    public Recoil() {
        this(DECAY);
    }

    /**
     * Своё затухание за тик (0..1). Сервер для обычного клиента берёт затухание самого клиента в воздухе (×0,91):
     * тогда, если пакет скорости опоздал на тик, клиент сам погасит скорость ровно так же — путь тот же.
     */
    public Recoil(double decay) {
        if (!(decay > 0 && decay < 1)) {
            throw new IllegalArgumentException("decay " + decay);
        }
        this.decay = decay;
    }

    /**
     * Выстрел: отнести на вектор (dx, dy, dz) блоков — направление и путь (длина вектора).
     */
    public void add(double dx, double dy, double dz) {
        if (!(Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz))) {
            return;
        }
        if (tail) {                                            // хвост ещё не ушёл — вернуть обычную скорость
            vx *= 1 - decay;
            vy *= 1 - decay;
            vz *= 1 - decay;
            tail = false;
        }
        double k = (1 - decay) / Units.TICK;
        vx += dx * k;
        vy += dy * k;
        vz += dz * k;
    }

    /** Начальная скорость отдачи (блоки/с), которая за всё время унесёт на distance блоков. */
    public static double startSpeed(double distance) {
        return distance * (1 - DECAY) / Units.TICK;
    }

    /** Скорость отдачи на этот тик, блоки/с. */
    public Vec3d velocity() {
        return new Vec3d(vx, vy, vz);
    }

    /** Тик прошёл: скорость гаснет; малая — остаток пути следующим тиком целиком, потом ноль. */
    public void step() {
        if (tail) {
            clear();
            return;
        }
        vx *= decay;
        vy *= decay;
        vz *= decay;
        if (vx * vx + vy * vy + vz * vz < TAIL * TAIL) {
            double k = 1 / (1 - decay);                        // v·dt/(1 − r) — весь остаток за тик
            vx *= k;
            vy *= k;
            vz *= k;
            tail = true;
        }
    }

    /** Есть ли отдача. */
    public boolean active() {
        return vx != 0 || vy != 0 || vz != 0;
    }

    /** Остаток пути, блоки (если дальше ничего не мешает). */
    public double remaining() {
        double v = Math.sqrt(vx * vx + vy * vy + vz * vz) * Units.TICK;
        return tail ? v : v / (1 - decay);
    }

    /** Упёрся в блок или встал на опору — отдача кончилась. */
    public void clear() {
        vx = 0;
        vy = 0;
        vz = 0;
        tail = false;
    }

    /**
     * Своя скорость (без отдачи) по оси после сверки с vanilla ({@link VanillaMotion#reconcile}), блоки/тик: в прошлый
     * тик игроку поставили свою скорость + отдачу sent, сверка вернула base. Упёрся в блок (ось обнулена) — 0, иначе
     * отдачу прошлого тика вычесть (толчок сервера, если был, остаётся).
     *
     * @param base     скорость после сверки
     * @param expected поставленная в прошлый тик (с отдачей)
     * @param sent     отдача в ней
     */
    public static double own(double base, double expected, double sent) {
        if (sent == 0) {
            return base;
        }
        if (base == 0 && expected != 0) {
            return 0;                                          // удар о блок — ось обнулена
        }
        return base - sent;
    }
}
