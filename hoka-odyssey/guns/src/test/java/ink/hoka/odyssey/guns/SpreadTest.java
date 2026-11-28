package ink.hoka.odyssey.guns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class SpreadTest {
    @Test
    void stanceOrder() {
        GunDef g = TestGuns.ak();
        assertEquals(0.15, Spread.inaccuracy(g, new Spread.Stance(true, true, true, true)));
        assertEquals(4.5, Spread.inaccuracy(g, new Spread.Stance(false, true, true, true)));
        assertEquals(2.5, Spread.inaccuracy(g, new Spread.Stance(false, false, true, true)));
        assertEquals(5, Spread.inaccuracy(g, new Spread.Stance(false, false, false, true)));
        assertEquals(4.5, Spread.inaccuracy(g, new Spread.Stance(false, false, false, false)));
    }

    @Test
    void boundsAndDistribution() {
        RandomGenerator r = new SplittableRandom(42);
        for (double inacc : new double[] {0.15, 2.5, 4.5, 5}) {
            double max = Spread.maxAngle(inacc);
            double sum = 0;
            double worst = 0;
            int n = 20000;
            for (int i = 0; i < n; i++) {
                double[] d = Spread.apply(0.3, -0.2, 0.9, inacc, r);
                double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                assertEquals(1, len, 1e-9);
                double a = FireValidator.angle(d[0], d[1], d[2], 0.3, -0.2, 0.9);
                assertTrue(a <= max + 1e-9, "угол " + a + " > " + max);
                worst = Math.max(worst, a);
                sum += a;
            }
            double mean = sum / n;
            // треугольное распределение: средний угол заметно меньше предела, но не ноль
            assertTrue(mean > inacc * 0.2 && mean < inacc * 1.0, "средний угол " + mean + " при " + inacc);
            assertTrue(worst > inacc * 0.8, "максимум " + worst + " при " + inacc);
        }
    }

    @Test
    void zeroSpreadKeepsDirection() {
        double[] d = Spread.apply(0, 0, 5, 0, new SplittableRandom(1));
        assertEquals(0, d[0]);
        assertEquals(0, d[1]);
        assertEquals(1, d[2]);
    }

    @Test
    void triangleRange() {
        RandomGenerator r = new SplittableRandom(7);
        double s = 0;
        for (int i = 0; i < 100000; i++) {
            double t = Spread.triangle(r);
            assertTrue(t > -1 && t < 1);
            s += t;
        }
        assertEquals(0, s / 100000, 0.01);
    }
}
