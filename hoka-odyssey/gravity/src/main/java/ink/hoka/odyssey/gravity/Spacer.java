package ink.hoka.odyssey.gravity;

import ink.hoka.odyssey.spacephys.State;
import ink.hoka.odyssey.spacephys.Thrust;

/**
 * Игрок на карте с настоящей физикой вакуума. Без Minestom: только состояние, логика тика — в {@link GravityModule}.
 * Скорость {@link #state} — «настоящая», блоки/с (сервер её ведёт, клиент её получает пакетом); позиция в state —
 * центр игрока (ноги + {@link GravityModule#PLAYER_CENTER}) из последнего пакета движения.
 */
final class Spacer {
    /** Скачок позиции больше этого за тик (блоков) — телепорт: скорость с нуля. */
    static final double TELEPORT_DISTANCE = 16;
    /** Сколько последних сдвигов усреднять для замеренной скорости (пакеты приходят пачками). */
    static final int SAMPLES = 4;
    /** Тиков после толчка от блока, когда «на земле» от клиента не верим (флаг ещё старый). */
    static final int PUSH_GRACE = 6;

    final State state = new State();
    /** Ранец (после /hograv reload — новый, с новыми настройками). */
    Thrust pack;
    /** Игрок с модом spaceflight: физику считает его клиент, сервер только проверяет. */
    boolean modded;
    /** Клиенту spaceflight уже сказали «физика вкл.» (op 2). */
    boolean clientOn;
    /** Игрок стоял на земле в прошлый тик (физику делал клиент). */
    boolean grounded;
    /** Прошлый тик пробел был нажат (толчок — по нажатию, а не пока держишь). */
    boolean wasJump;
    int pushGrace;
    /** Тик последнего удара (урон не дважды за одно столкновение). */
    long lastImpact = Long.MIN_VALUE / 2;
    /** Последняя скорость, посланная клиенту, — нулевую повторно не шлём. */
    boolean sentZero;
    /** Значение атрибута гравитации, которое сейчас стоит игроку, блоков/тик². */
    double gravityAttr = Double.NaN;
    final Inertia.MoveGuard guard = new Inertia.MoveGuard();
    /** Тиков, пока действует запас проверки скорости у блоков (ходьба по стенам с модом). */
    int surfaceTicks;
    /**
     * Клиент сменил рамку (стоит на стене/потолке или сошёл — {@link PlayerBox}): следующий пакет движения сдвигает
     * позицию; пишет поток clientmods, читает обработчик движения.
     */
    volatile boolean boxShift;
    /** Прошлая замеренная скорость по пакетам движения (для удара у игроков с модом), блоки/с. */
    double lastMoveSpeed;
    /** Проверка скорости: нарушений с последней записи в лог, тик записи и тик последнего ограничения скорости. */
    int violations;
    long lastViolationLog = Long.MIN_VALUE / 2;
    long lastClamp = Long.MIN_VALUE / 2;

    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean hasLast;
    private final double[] sx = new double[SAMPLES];
    private final double[] sy = new double[SAMPLES];
    private final double[] sz = new double[SAMPLES];
    private int samples;
    private int head;

    Spacer(Thrust.Spec spec) {
        pack = new Thrust(spec);
    }

    /** Забыть движение (вход, смена карты, телепорт): скорость 0, ранец полный. */
    void reset() {
        state.setVelocity(0, 0, 0);
        hasLast = false;
        samples = 0;
        grounded = false;
        pushGrace = 0;
        sentZero = false;
        guard.reset(0);
        lastMoveSpeed = 0;
        surfaceTicks = 0;
        pack.refill();
    }

    /**
     * Новая позиция центра игрока за тик. false — скачок (телепорт): замеры сброшены.
     */
    boolean observe(double x, double y, double z) {
        boolean ok = true;
        if (hasLast) {
            double dx = x - lastX;
            double dy = y - lastY;
            double dz = z - lastZ;
            if (dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
                samples = 0;
                ok = false;
            } else {
                sx[head] = dx;
                sy[head] = dy;
                sz[head] = dz;
                head = (head + 1) % SAMPLES;
                samples = Math.min(SAMPLES, samples + 1);
            }
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        hasLast = true;
        state.setPosition(x, y, z);
        return ok;
    }

    /** Скачок позиции не в счёт (смена рамки): следующий замер — от новой позиции, без «скорости» сдвига. */
    void rebase() {
        hasLast = false;
    }

    /** Средняя замеренная скорость {x, y, z}, блоки/с (нули, если замеров нет). */
    double[] measured() {
        double[] v = new double[3];
        if (samples == 0) {
            return v;
        }
        for (int i = 0; i < samples; i++) {
            int k = (head - 1 - i + SAMPLES) % SAMPLES;
            v[0] += sx[k];
            v[1] += sy[k];
            v[2] += sz[k];
        }
        double k = (double) Settings.TPS / samples;
        v[0] *= k;
        v[1] *= k;
        v[2] *= k;
        return v;
    }

    /** Сдвиг за последний тик, блоки (0 — замеров нет). */
    double lastStep() {
        if (samples == 0) {
            return 0;
        }
        int k = (head - 1 + SAMPLES) % SAMPLES;
        return Math.sqrt(sx[k] * sx[k] + sy[k] * sy[k] + sz[k] * sz[k]);
    }

    /** Толчок от другого модуля (VacuumService.push), блоков/тик — к настоящей скорости. */
    void push(double vx, double vy, double vz) {
        state.vx += vx * Settings.TPS;
        state.vy += vy * Settings.TPS;
        state.vz += vz * Settings.TPS;
        grounded = false;
        pushGrace = PUSH_GRACE;
    }
}
