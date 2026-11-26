package ink.hoka.odyssey.guns;

/**
 * Проверка выстрела на сервере: оружие в руке, не достаёт, не перезаряжает, есть патрон, направление близко к взгляду
 * и темп не выше rpm («ведро» на rate-burst выстрелов: пачки из-за задержек сети пропускаем, длинную очередь быстрее
 * темпа — нет). Только логика, без Minestom.
 */
final class FireValidator {

    enum Result {
        OK(0), RATE(1), EMPTY(2), RELOADING(3), DRAWING(4), ANGLE(5), NOT_HELD(6);

        final int code;

        Result(int code) {
            this.code = code;
        }
    }

    /** Темп на сервере чуть выше rpm: часы клиента и сервера немного расходятся. */
    static final double RATE_SLACK = 1.05;

    private FireValidator() {
    }

    /**
     * Проверить один выстрел. Ничего не меняет, кроме «ведра» (при OK из него берётся выстрел).
     *
     * @param angleDeg   угол между присланным направлением и взглядом игрока на сервере
     * @param maxAngle   предел угла (max-aim-deviation)
     * @param burst      ёмкость ведра (rate-burst)
     */
    static Result check(ShooterState s, long nowMs, double angleDeg, double maxAngle, double burst) {
        GunDef g = s.gun;
        if (g == null) {
            return Result.NOT_HELD;
        }
        if (s.drawing(nowMs)) {
            return Result.DRAWING;
        }
        if (s.reloading()) {
            return Result.RELOADING;
        }
        if (s.ammo <= 0) {
            return Result.EMPTY;
        }
        if (!(angleDeg <= maxAngle)) {
            return Result.ANGLE;
        }
        if (!s.bucket.take(nowMs, g.rpm() / 60.0 * RATE_SLACK, Math.max(1, burst))) {
            return Result.RATE;
        }
        return Result.OK;
    }

    /** Угол между векторами (градусы); a и b не обязаны быть единичными. */
    static double angle(double ax, double ay, double az, double bx, double by, double bz) {
        double la = Math.sqrt(ax * ax + ay * ay + az * az);
        double lb = Math.sqrt(bx * bx + by * by + bz * bz);
        if (la < 1e-9 || lb < 1e-9) {
            return 180;
        }
        double c = (ax * bx + ay * by + az * bz) / (la * lb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }

    /**
     * «Ведро» выстрелов: наполняется со скоростью rpm/60 в секунду до ёмкости burst; выстрел забирает одну единицу.
     * Сразу после того как оружие взяли в руку — одна единица (первый выстрел без ожидания).
     */
    static final class Bucket {
        double tokens = 1;
        long last = -1;

        void reset(long now) {
            tokens = 1;
            last = now;
        }

        boolean take(long now, double perSecond, double capacity) {
            if (last < 0) {
                last = now;
            }
            if (now > last) {
                tokens = Math.min(capacity, tokens + (now - last) / 1000.0 * perSecond);
                last = now;
            }
            if (tokens >= 1 - 1e-9) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
