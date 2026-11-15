package core.amazake.systems.corevault.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LevelTest {

    private static final LevelCurve FLAT = new LevelCurve(Expr.parse("100"), Map.of(), 1, 10);

    @Test
    void exprParses() {
        assertEquals(100, Expr.parse("100 * level^1.5").eval(1), 1e-9);
        assertEquals(800, Expr.parse("100 * level^1.5").eval(4), 1e-9);
        assertEquals(-4, Expr.parse("-2^2").eval(0), 1e-9);
        assertEquals(512, Expr.parse("2^3^2").eval(0), 1e-9);
        assertEquals(14, Expr.parse("2 + 3 * 4").eval(0), 1e-9);
        assertEquals(20, Expr.parse("(2 + 3) * 4").eval(0), 1e-9);
        assertEquals(3, Expr.parse("floor(lvl / 3)").eval(10), 1e-9);
        assertEquals(50, Expr.parse("max(50, level * 2)").eval(10), 1e-9);
        assertEquals(1, Expr.parse("10 % 3").eval(0), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> Expr.parse("100 * "));
        assertThrows(IllegalArgumentException.class, () -> Expr.parse("100 * foo"));
        assertThrows(IllegalArgumentException.class, () -> Expr.parse("(1 + 2"));
        assertThrows(IllegalArgumentException.class, () -> Expr.parse("1 2"));
        assertThrows(IllegalArgumentException.class, () -> Expr.parse("max(1)"));
    }

    @Test
    void curveUsesTableThenFormula() {
        final LevelCurve c = new LevelCurve(Expr.parse("100 * level^1.5"), Map.of(2, 50L), 1, 5);
        assertEquals(100, c.need(1));
        assertEquals(50, c.need(2));
        assertEquals(520, c.need(3));
        assertEquals(0, c.need(5));
        assertEquals(150, c.totalBefore(3));
        final LevelCurve neg = new LevelCurve(Expr.parse("level - 10"), Map.of(), 1, 5);
        assertEquals(1, neg.need(1));
    }

    @Test
    void addExpCrossesLevels() {
        LevelMath.State s = new LevelMath.State(1, 0);
        s = LevelMath.addExp(FLAT, s, 250);
        assertEquals(new LevelMath.State(3, 50), s);
        s = LevelMath.addExp(FLAT, s, 50);
        assertEquals(new LevelMath.State(4, 0), s);
        s = LevelMath.addExp(FLAT, s, 10_000);
        assertEquals(new LevelMath.State(10, 0), s);
    }

    @Test
    void removeExpGoesDown() {
        LevelMath.State s = new LevelMath.State(3, 50);
        s = LevelMath.addExp(FLAT, s, -60);
        assertEquals(new LevelMath.State(2, 90), s);
        s = LevelMath.addExp(FLAT, s, -190);
        assertEquals(new LevelMath.State(1, 0), s);
        s = LevelMath.addExp(FLAT, new LevelMath.State(2, 0), -1_000);
        assertEquals(new LevelMath.State(1, 0), s);
    }

    @Test
    void totalRoundTrip() {
        final LevelCurve c = new LevelCurve(Expr.parse("100 * level^1.5"), Map.of(), 1, 50);
        for (long t : new long[] {0, 1, 99, 100, 382, 383, 5000, 123456}) {
            final LevelMath.State s = LevelMath.fromTotal(c, t);
            assertEquals(t, LevelMath.total(c, s), "total " + t);
        }
    }

    @Test
    void levelsShiftKeepsExp() {
        final LevelMath.State s = LevelMath.addLevels(FLAT, new LevelMath.State(2, 40), 3);
        assertEquals(new LevelMath.State(5, 40), s);
        assertEquals(new LevelMath.State(1, 40), LevelMath.addLevels(FLAT, s, -10));
        assertEquals(new LevelMath.State(10, 0), LevelMath.addLevels(FLAT, s, 100));
        assertEquals(new LevelMath.State(7, 0), LevelMath.setLevel(FLAT, 7, 0));
        assertEquals(new LevelMath.State(3, 99), LevelMath.setLevel(FLAT, 3, 500));
    }

    @Test
    void normalizeAfterCurveChange() {
        assertEquals(new LevelMath.State(4, 20), LevelMath.normalize(FLAT, 2, 220));
        assertEquals(new LevelMath.State(10, 0), LevelMath.normalize(FLAT, 50, 3));
        assertEquals(new LevelMath.State(1, 0), LevelMath.normalize(FLAT, 0, -5));
        assertEquals(0.5, LevelMath.progress(FLAT, new LevelMath.State(3, 50)), 1e-9);
    }

    @Test
    void rewardCommandFill() {
        assertEquals("give Steve diamond 5", LevelService.fill("/give %player% diamond %level%", "Steve",
            java.util.UUID.randomUUID(), 5));
    }
}
