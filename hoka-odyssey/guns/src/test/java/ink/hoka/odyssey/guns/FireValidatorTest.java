package ink.hoka.odyssey.guns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FireValidatorTest {
    private static ShooterState ready(long now) {
        ShooterState s = new ShooterState();
        s.equip(TestGuns.ak(), 0, "sn", 30, now - 1000);   // достали секунду назад
        s.bucket.reset(now);
        return s;
    }

    @Test
    void notHeldDrawingReloadingEmptyAngle() {
        ShooterState s = new ShooterState();
        assertEquals(FireValidator.Result.NOT_HELD, FireValidator.check(s, 0, 0, 25, 3));
        s.equip(TestGuns.ak(), 0, "sn", 30, 1000);
        assertEquals(FireValidator.Result.DRAWING, FireValidator.check(s, 1100, 0, 25, 3));   // draw 0,35 с
        assertEquals(FireValidator.Result.OK, FireValidator.check(s, 1400, 0, 25, 3));
        s.reloadKind = 1;
        assertEquals(FireValidator.Result.RELOADING, FireValidator.check(s, 5000, 0, 25, 3));
        s.cancelReload();
        s.ammo = 0;
        assertEquals(FireValidator.Result.EMPTY, FireValidator.check(s, 5000, 0, 25, 3));
        s.ammo = 5;
        assertEquals(FireValidator.Result.ANGLE, FireValidator.check(s, 5000, 30, 25, 3));
        assertEquals(FireValidator.Result.ANGLE, FireValidator.check(s, 5000, Double.NaN, 25, 3));
        assertEquals(FireValidator.Result.OK, FireValidator.check(s, 5000, 24.9, 25, 3));
    }

    @Test
    void rateLimitSustained() {
        long t0 = 10_000;
        ShooterState s = ready(t0);
        // 600 в/мин: очередь 10 секунд с интервалом 100 мс — все проходят
        int ok = 0;
        for (int i = 0; i < 100; i++) {
            if (FireValidator.check(s, t0 + i * 100L, 0, 25, 3) == FireValidator.Result.OK) {
                ok++;
                s.ammo = 30;
            }
        }
        assertEquals(100, ok);
        // вдвое быстрее (1200 в/мин) — пропускается не больше темпа (+5 % и ведро)
        ShooterState f = ready(t0);
        int fast = 0;
        for (int i = 0; i < 200; i++) {
            if (FireValidator.check(f, t0 + i * 50L, 0, 25, 3) == FireValidator.Result.OK) {
                fast++;
                f.ammo = 30;
            }
        }
        assertTrue(fast <= 10 * 1.05 * 10 + 3, "прошло " + fast);
        assertTrue(fast >= 100, "прошло " + fast);
    }

    @Test
    void burstFromNetworkJitter() {
        long t0 = 10_000;
        ShooterState s = ready(t0);
        // 300 мс тишины (задержка сети), потом 3 выстрела в одном тике — ведро на 3 пропускает
        assertEquals(FireValidator.Result.OK, FireValidator.check(s, t0 + 300, 0, 25, 3));
        assertEquals(FireValidator.Result.OK, FireValidator.check(s, t0 + 300, 0, 25, 3));
        assertEquals(FireValidator.Result.OK, FireValidator.check(s, t0 + 300, 0, 25, 3));
        assertEquals(FireValidator.Result.RATE, FireValidator.check(s, t0 + 300, 0, 25, 3));
        // макрос «100 выстрелов сразу» — проходит только ведро
        ShooterState m = ready(t0);
        int ok = 0;
        for (int i = 0; i < 100; i++) {
            if (FireValidator.check(m, t0 + 5000, 0, 25, 3) == FireValidator.Result.OK) {
                ok++;
            }
        }
        assertEquals(3, ok);
    }

    @Test
    void angleMath() {
        assertEquals(0, FireValidator.angle(0, 0, 1, 0, 0, 2), 1e-9);
        assertEquals(90, FireValidator.angle(1, 0, 0, 0, 1, 0), 1e-9);
        assertEquals(180, FireValidator.angle(1, 0, 0, -1, 0, 0), 1e-9);
        assertEquals(180, FireValidator.angle(0, 0, 0, 1, 0, 0), 1e-9);
    }

    @Test
    void ammoAndCapacity() {
        ShooterState s = ready(10_000);
        s.ammo = 1;
        assertEquals(FireValidator.Result.OK, FireValidator.check(s, 10_000, 0, 25, 3));
        s.ammo--;
        assertEquals(FireValidator.Result.EMPTY, FireValidator.check(s, 20_000, 0, 25, 3));
    }
}
