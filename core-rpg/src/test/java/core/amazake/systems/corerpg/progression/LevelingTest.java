package core.amazake.systems.corerpg.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LevelingTest {

    private static final ExpCurve CURVE = ProgressionSettings.DEFAULT_CURVE;

    @Test
    void levelsUpSeveralTimesInOneGrant() {
        // 2..15 стоят по 10: 35 опыта с 1 уровня — три уровня и 5 в запасе.
        final Leveling.Result r = Leveling.apply(CURVE, 71, 1, 0, 35, 0, 5, 4);
        assertEquals(4, r.level());
        assertEquals(5, r.exp());
        assertEquals(3, r.levelsGained());
        assertEquals(0, r.pointsGained());
    }

    @Test
    void notEnoughExpKeepsLevel() {
        final Leveling.Result r = Leveling.apply(CURVE, 71, 1, 3, 4, 0, 5, 4);
        assertEquals(1, r.level());
        assertEquals(7, r.exp());
        assertEquals(0, r.levelsGained());
    }

    @Test
    void pointsEveryFiveLevels() {
        final Leveling.Result r = Leveling.apply(CURVE, 71, 4, 0, 10 * 6, 0, 5, 4); // 4 → 10
        assertEquals(10, r.level());
        assertEquals(8, r.pointsGained()); // 5 и 10
        assertEquals(56, Leveling.pointsForLevel(71, 5, 4));
        assertEquals(0, Leveling.pointsForLevel(4, 5, 4));
        assertEquals(4, Leveling.pointsAt(5, 5, 4));
        assertEquals(0, Leveling.pointsAt(6, 5, 4));
        assertEquals(0, Leveling.pointsAt(1, 1, 4));
    }

    @Test
    void stopsAtProfessionGate() {
        final Leveling.Result r = Leveling.apply(CURVE, 71, 19, 0, 1_000_000, 21, 5, 4);
        assertEquals(21, r.level());
        assertTrue(r.stoppedAtGate());
        assertEquals(0, r.exp()); // 20 стоит 40, 21 стоит 80; остаток сверх уровня профессии сгорает
        assertEquals(2, r.levelsGained());
        assertEquals(4, r.pointsGained()); // за 20
    }

    @Test
    void gateAlreadyReached() {
        final Leveling.Result r = Leveling.apply(CURVE, 71, 21, 0, 500, 21, 5, 4);
        assertEquals(21, r.level());
        assertTrue(r.stoppedAtGate());
        assertEquals(0, r.levelsGained());
    }

    @Test
    void notGatedBelowGate() {
        final Leveling.Result r = Leveling.apply(CURVE, 71, 1, 0, 5, 21, 5, 4);
        assertFalse(r.stoppedAtGate());
    }

    @Test
    void maxLevelDropsExp() {
        final Leveling.Result r = Leveling.apply(CURVE, 71, 70, 0, Long.MAX_VALUE, 0, 5, 4);
        assertEquals(71, r.level());
        assertEquals(0, r.exp());
        assertEquals(1, r.levelsGained());
        final Leveling.Result again = Leveling.apply(CURVE, 71, 71, 0, 100, 0, 5, 4);
        assertEquals(71, again.level());
        assertEquals(0, again.exp());
    }

    @Test
    void fullRunFromOneToMax() {
        final long total = CURVE.total(71);
        final Leveling.Result r = Leveling.apply(CURVE, 71, 1, 0, total, 0, 5, 4);
        assertEquals(71, r.level());
        assertEquals(70, r.levelsGained());
        assertEquals(56, r.pointsGained());
        final Leveling.Result shortBy1 = Leveling.apply(CURVE, 71, 1, 0, total - 1, 0, 5, 4);
        assertEquals(70, shortBy1.level());
    }

    @Test
    void negativeInputsAreSafe() {
        final Leveling.Result r = Leveling.apply(CURVE, 71, -5, -10, -20, 0, 5, 4);
        assertEquals(1, r.level());
        assertEquals(0, r.exp());
        assertEquals(Long.MAX_VALUE, Leveling.saturatedAdd(Long.MAX_VALUE - 1, 10));
    }

    @Test
    void progress() {
        assertEquals(50.0, Leveling.progress(5, 10));
        assertEquals(100.0, Leveling.progress(50, 10));
        assertEquals(100.0, Leveling.progress(0, 0));
        assertEquals(0.0, Leveling.progress(-3, 10));
    }

    @Test
    void curveTableAndFormula() {
        assertEquals(0, CURVE.need(1));
        assertEquals(10, CURVE.need(2));
        assertEquals(80, CURVE.need(21));
        assertEquals(10_025_770, CURVE.need(71));
        // уровня 72 в таблице нет — формула 100 × 72^1.5
        assertEquals((long) (100 * Math.pow(72, 1.5)), CURVE.need(72));
        final ExpCurve formulaOnly = new ExpCurve(Map.of(), 100, 1.5);
        assertEquals(282, formulaOnly.need(2));
        final ExpCurve huge = new ExpCurve(Map.of(), 1e12, 10);
        assertEquals(Long.MAX_VALUE, huge.need(1000));
    }

    @Test
    void defaultTableMatchesJsonSamples() {
        assertEquals(70, ProgressionSettings.DEFAULT_TABLE.size());
        assertEquals(20L, ProgressionSettings.DEFAULT_TABLE.get(16));
        assertEquals(950L, ProgressionSettings.DEFAULT_TABLE.get(31));
        assertEquals(100_040L, ProgressionSettings.DEFAULT_TABLE.get(51));
        assertEquals(1_349_040L, ProgressionSettings.DEFAULT_TABLE.get(65));
        assertEquals(4_212_510L, ProgressionSettings.DEFAULT_TABLE.get(70));
    }

    @Test
    void expBonusAndCooldown() {
        assertEquals(150, ExpMath.withBonus(100, 50));
        assertEquals(100, ExpMath.withBonus(100, 0));
        assertEquals(0, ExpMath.withBonus(100, -200));
        assertEquals(0, ExpMath.withBonus(-5, 50));
        assertEquals(100, ExpMath.withBonus(100, Double.NaN));
        assertEquals(Long.MAX_VALUE, ExpMath.withBonus(Long.MAX_VALUE, 100));
        assertTrue(ExpMath.cooldownPassed(null, 1000, 15));
        assertFalse(ExpMath.cooldownPassed(1000L, 15_999, 15));
        assertTrue(ExpMath.cooldownPassed(1000L, 16_000, 15));
        assertTrue(ExpMath.cooldownPassed(1000L, 1000, 0));
    }
}
