package ink.hoka.odyssey.gravity;

/**
 * Состояние одного игрока в вакууме (без Minestom — для тестов). Вертикаль ведём по модели: сервер сам знает,
 * какие импульсы послал, а клиент гасит скорость предсказуемо (×0.98 за тик), поэтому пинг не раскачивает
 * управление. Модель сбрасывается, когда игрок упёрся (стоит на земле или долго не двигается по вертикали).
 * Горизонтальную скорость берём из замеров позиции — по ней только поддерживаем инерцию.
 */
final class Floater {
    /** Сколько тиков подряд модель ждёт движения по вертикали, прежде чем решить, что игрок упёрся (пинг ≤ ~0,6 с). */
    static final int STUCK_TICKS = 12;
    /** Скачок позиции больше этого (блоков) — телепорт, замеры сбрасываются. */
    static final double TELEPORT_DISTANCE = 8;
    /** Тиков после сброса, когда модель вертикали берётся из замеров позиции. */
    static final int SEED_TICKS = 3;

    /** Модель вертикальной скорости клиента, блоков/тик. */
    double vy;
    /** Сглаженная замеренная горизонтальная скорость, блоков/тик. */
    double mx;
    double mz;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean hasLast;
    private int stuck;
    /** Сколько тиков после сброса модель берёт скорость из замеров (вошёл в вакуум в падении — гасим и это падение). */
    private int seed = SEED_TICKS;

    /** Импульс {x, y, z}, который надо добавить к скорости клиента; null — ничего не слать. */
    record Impulse(double x, double y, double z) {}

    /** Клавиши игрока. */
    record Keys(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift) {
        static final Keys NONE = new Keys(false, false, false, false, false, false);
    }

    /** Забыть всё (телепорт, смена карты, включение). */
    void reset() {
        vy = 0;
        mx = 0;
        mz = 0;
        hasLast = false;
        stuck = 0;
        seed = SEED_TICKS;
    }

    /** Один тик сервера. */
    Impulse tick(Settings s, double x, double y, double z, float yaw, Keys k, boolean onGround, boolean flying) {
        double dx = 0;
        double dy = 0;
        double dz = 0;
        if (hasLast) {
            dx = x - lastX;
            dy = y - lastY;
            dz = z - lastZ;
            if (dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
                reset();
                dx = 0;
                dy = 0;
                dz = 0;
            } else if (seed > 0) {
                seed--;
                vy = dy;                                // уже двигался (например, падал) — модель с этой скорости
            }
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        hasLast = true;
        mx = mx * 0.5 + dx * 0.5;
        mz = mz * 0.5 + dz * 0.5;

        if (flying) {                                   // креативный полёт — у клиента своя физика
            vy = 0;
            stuck = 0;
            return null;
        }
        if (onGround && vy < 0) {
            vy = 0;
        }
        if (Math.abs(dy) < 1.0e-3 && Math.abs(vy) > 0.03) {
            if (++stuck >= STUCK_TICKS) {               // упёрся в потолок/пол, а модель всё ещё «летит»
                vy = 0;
                stuck = 0;
            }
        } else {
            stuck = 0;
        }

        int dir = k.jump() == k.shift() ? 0 : k.jump() ? 1 : -1;
        double iy = onGround && dir <= 0 ? 0 : Motion.verticalImpulse(vy, dir, s);
        vy = Motion.nextVertical(vy, iy);

        int forward = (k.forward() ? 1 : 0) - (k.backward() ? 1 : 0);
        int strafe = (k.left() ? 1 : 0) - (k.right() ? 1 : 0);
        double[] h = Motion.horizontalImpulse(yaw, forward, strafe, mx, mz, onGround, s);
        if (iy == 0 && h[0] == 0 && h[1] == 0) {
            return null;
        }
        return new Impulse(h[0], iy, h[1]);
    }
}
