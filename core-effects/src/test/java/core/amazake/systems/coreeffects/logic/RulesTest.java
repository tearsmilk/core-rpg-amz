package core.amazake.systems.coreeffects.logic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RulesTest {

    @Test
    void bleedingDamageEveryInterval() {
        long u = 0;
        long f = 0;
        int hits = 0;
        for (long t = 1; t <= 200; t++) {
            final BleedingRules.Result r = BleedingRules.tick(u, f, t, 80, 0, 600, 160);
            u = r.untreated();
            f = r.faint();
            if (r.step() == BleedingRules.Step.DAMAGE) {
                hits++;
            }
        }
        assertEquals(2, hits);   // 80 и 160
        assertEquals(200, u);
        assertEquals(0, f);
    }

    @Test
    void faintThenWakeThenRefaint() {
        // обморок через 100 тиков, длится 20, повтор через 30
        long u = 0;
        long f = 0;
        final java.util.List<String> events = new java.util.ArrayList<>();
        for (long t = 1; t <= 200; t++) {
            final BleedingRules.Result r = BleedingRules.tick(u, f, t, 0, 100, 30, 20);
            u = r.untreated();
            f = r.faint();
            if (r.step() == BleedingRules.Step.FAINT || r.step() == BleedingRules.Step.WAKE) {
                events.add(r.step() + "@" + t);
            }
        }
        // 100: обморок; 120: очнулся (u = 70); 150: снова обморок; 170: очнулся
        assertEquals(List.of("FAINT@100", "WAKE@120", "FAINT@150", "WAKE@170", "FAINT@200"), events);
    }

    @Test
    void faintDisabled() {
        final BleedingRules.Result r = BleedingRules.tick(1_000_000, 0, 5, 0, 0, 30, 20);
        assertEquals(BleedingRules.Step.NONE, r.step());
        assertEquals(0, r.faint());
    }

    @Test
    void allowedDamage() {
        assertEquals(1.0, BleedingRules.allowedDamage(20, 1, 2));
        assertEquals(0.5, BleedingRules.allowedDamage(2.5, 1, 2), 1e-9);
        assertEquals(0.0, BleedingRules.allowedDamage(2, 1, 2));
        assertEquals(1.5, BleedingRules.allowedDamage(1.5, 3, 0));   // может убить
        assertEquals(0.0, BleedingRules.allowedDamage(0, 3, 0));
    }

    @Test
    void bloodLevel() {
        assertEquals(1, BleedingRules.bloodLevel(10, 100));
        assertEquals(2, BleedingRules.bloodLevel(50, 100));
        assertEquals(3, BleedingRules.bloodLevel(90, 100));
        assertEquals(1, BleedingRules.bloodLevel(90, 0));
    }

    @Test
    void warmthStagesAndDeath() {
        final List<Integer> warn = List.of(50, 75, 90);
        assertEquals(0, WarmthRules.stage(0, 2400, warn));
        assertEquals(0, WarmthRules.stage(1199, 2400, warn));
        assertEquals(1, WarmthRules.stage(1200, 2400, warn));
        assertEquals(2, WarmthRules.stage(1800, 2400, warn));
        assertEquals(3, WarmthRules.stage(2160, 2400, warn));
        assertFalse(WarmthRules.lethal(2399, 2400));
        assertTrue(WarmthRules.lethal(2400, 2400));
        assertFalse(WarmthRules.lethal(1_000_000, 0));
        assertEquals(0, WarmthRules.stage(1_000_000, 0, warn));
        assertEquals(0.5, WarmthRules.fraction(1200, 2400));
        assertTrue(WarmthRules.healNow(80, 80));
        assertFalse(WarmthRules.healNow(81, 80));
        assertFalse(WarmthRules.healNow(0, 80));
    }

    @Test
    void disorientDirections() {
        // yaw 0 — юг (+Z): W — вперёд (+Z), A — влево (+X, восток)
        assertArrayEquals(new double[] {0, 1}, Disorient.direction(1, 0, 0f, 0), 1e-9);
        assertArrayEquals(new double[] {1, 0}, Disorient.direction(0, 1, 0f, 0), 1e-9);
        assertArrayEquals(new double[] {0, -1}, Disorient.direction(-1, 0, 0f, 0), 1e-9);
        // yaw 90 — запад (−X)
        assertArrayEquals(new double[] {-1, 0}, Disorient.direction(1, 0, 90f, 0), 1e-9);
        // скрутка 180 — назад
        assertArrayEquals(new double[] {0, -1}, Disorient.direction(1, 0, 0f, 180), 1e-9);
        // по диагонали — единичный вектор
        final double[] d = Disorient.direction(1, 1, 0f, 0);
        assertEquals(1.0, Math.hypot(d[0], d[1]), 1e-9);
        assertNull(Disorient.direction(0, 0, 0f, 90));
        assertArrayEquals(new double[] {0.1, 0.1}, Disorient.blend(0.2, 0, new double[] {0, 1}, 0.2, 0.5), 1e-9);
    }

    @Test
    void durations() {
        assertEquals(90L, Durations.parseSeconds("90"));
        assertEquals(90L, Durations.parseSeconds("1m30s"));
        assertEquals(120L, Durations.parseSeconds("2m"));
        assertEquals(3600L, Durations.parseSeconds("1h"));
        assertEquals(150L, Durations.parseSeconds("2м30с"));
        assertEquals(Durations.INFINITE, Durations.parseSeconds("inf"));
        assertNull(Durations.parseSeconds("0"));
        assertNull(Durations.parseSeconds("-5"));
        assertNull(Durations.parseSeconds("abc"));
        assertNull(Durations.parseSeconds("5x"));
        assertNull(Durations.parseSeconds(""));
        assertNull(Durations.parseSeconds("99999999999"));
        assertEquals("1:30", Durations.format(1800));
        assertEquals("0:01", Durations.format(1));
        assertEquals("1:00:05", Durations.format(20 * 3605));
        assertEquals("∞", Durations.format(-1));
        assertEquals(2, Durations.seconds(21));
    }

    @Test
    void ranges() {
        assertEquals(new Range(2, 5), Range.parse("2-5", 0, 10));
        assertEquals(new Range(3, 3), Range.parse(3, 0, 10));
        assertEquals(new Range(2, 5), Range.parse("5..2", 0, 10));
        assertNull(Range.parse("2-50", 0, 10));
        assertNull(Range.parse("x", 0, 10));
        final java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < 100; i++) {
            final int v = new Range(2, 4).pick(r);
            assertTrue(v >= 2 && v <= 4);
        }
    }
}
