package ink.hoka.odyssey.gravzones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PhysicsTest {
    private static final double WEAK = 0.1;
    private static final double STRONG = 0.3;

    @Test
    void validRange() {
        assertTrue(Physics.valid(0.1));
        assertTrue(Physics.valid(10));
        assertTrue(Physics.valid(13));
        assertFalse(Physics.valid(0.09));
        assertFalse(Physics.valid(13.01));
        assertFalse(Physics.valid(Double.NaN));
        assertFalse(Physics.isVacuum(10));
        assertTrue(Physics.isVacuum(11));
    }

    @Test
    void gravityModifierGivesVanillaTimesG() {
        for (double g : new double[]{0.1, 0.5, 1, 3, 10}) {
            double value = Physics.VANILLA_GRAVITY * (1 + Physics.gravityAmount(g));   // ADD_MULTIPLIED_TOTAL
            assertEquals(0.08 * g, value, 1e-12);
        }
        // вместе с модификатором gravity (−1) — ноль: вакуум главнее, если обе стоят
        assertEquals(0, Physics.VANILLA_GRAVITY * (1 + Physics.gravityAmount(0.1)) * (1 - 1), 1e-12);
    }

    @Test
    void vanillaJump() {
        double[] j = Physics.jump(Physics.VANILLA_JUMP, Physics.VANILLA_GRAVITY);
        assertEquals(1.252, j[0], 0.01);                                   // известная высота прыжка 1.25 блока
        assertEquals(12, j[1]);
        assertEquals(1, Physics.jumpFactor(1, WEAK, STRONG));
    }

    @Test
    void weakGravityJumpsHigherAndLongerButSmoothly() {
        double[] j0 = Physics.jumpIn(1, WEAK, STRONG);
        double prevH = j0[0];
        double prevT = j0[1];
        for (double g : new double[]{0.8, 0.5, 0.3, 0.1}) {
            double[] j = Physics.jumpIn(g, WEAK, STRONG);
            assertTrue(j[0] > prevH && j[1] > prevT, "g=" + g);
            prevH = j[0];
            prevT = j[1];
        }
        double[] moon = Physics.jumpIn(0.1, WEAK, STRONG);
        assertTrue(moon[0] > 3.5 && moon[0] < 6, "высота " + moon[0]);      // выше, но не «выстрел»
        assertTrue(moon[1] / 20 > 2.5, "время " + moon[1]);
        assertTrue(Physics.jumpFactor(0.1, WEAK, STRONG) < 1);               // сам толчок мягче обычного
    }

    @Test
    void strongGravityJumpsLowerAndShorter() {
        double[] j0 = Physics.jumpIn(1, WEAK, STRONG);
        double[] j10 = Physics.jumpIn(10, WEAK, STRONG);
        assertTrue(j10[0] < j0[0] && j10[0] > 0.5, "высота " + j10[0]);
        assertTrue(j10[1] < j0[1] / 2, "тиков " + j10[1]);
        assertTrue(Physics.jumpIn(3, WEAK, STRONG)[0] >= 1.0);               // на блок при g=3 ещё запрыгнуть
    }

    @Test
    void safeFallFactor() {
        assertEquals(1, Physics.safeFallFactor(1, WEAK, STRONG), 1e-9);
        assertEquals(1, Physics.safeFallFactor(10, WEAK, STRONG), 1e-9);    // не меньше 1
        double f = Physics.safeFallFactor(0.1, WEAK, STRONG);
        assertEquals(Physics.jumpIn(0.1, WEAK, STRONG)[0] / 1.252, f, 0.02);
    }

    @Test
    void launchSpeedGrowsFrom11To13() {
        assertEquals(12, Physics.launchSpeed(10.5, 12, 18, 24));
        assertEquals(12, Physics.launchSpeed(11, 12, 18, 24));
        assertEquals(15, Physics.launchSpeed(11.5, 12, 18, 24), 1e-9);
        assertEquals(18, Physics.launchSpeed(12, 12, 18, 24));
        assertEquals(24, Physics.launchSpeed(13, 12, 18, 24));
        assertEquals(24, Physics.launchSpeed(99, 12, 18, 24));
    }

    @Test
    void strengthParsing() {
        assertEquals(0.5, HoZoneCommand.parseStrength("0,5"));
        assertEquals(12, HoZoneCommand.parseStrength("12"));
        assertTrue(Double.isNaN(HoZoneCommand.parseStrength("0")));
        assertTrue(Double.isNaN(HoZoneCommand.parseStrength("14")));
        assertTrue(Double.isNaN(HoZoneCommand.parseStrength("abc")));
        assertEquals("0.1", Physics.fmt(0.1));
        assertEquals("12", Physics.fmt(12.0));
        assertEquals("0.25", Physics.fmt(0.25));
    }

    @Test
    void outlineBudgetAndRadius() {
        Outline.Box small = Outline.Box.blocks(0, 0, 0, 1, 1, 1, 1);          // куб 2×2×2: рёбра 12 × 2 = 24 блока
        List<Outline.Point> pts = Outline.points(List.of(small), 1, 1, 1, 40, 1000, 0.5);
        assertEquals(12 * 5, pts.size());                                     // шаг 0.5 → 5 точек на ребро
        for (Outline.Point p : pts) {
            assertTrue(p.x() >= 0 && p.x() <= 2 && p.y() >= 0 && p.y() <= 2);
        }
        Outline.Box big = Outline.Box.blocks(-20, 0, -20, 20, 100, 20, 2);  // рёбер рядом сотни блоков
        List<Outline.Point> many = Outline.points(List.of(small, big), 0, 10, 0, 40, 200, 0.5);
        assertTrue(many.size() <= 200 && many.size() > 150, "частиц " + many.size());
        assertTrue(many.stream().anyMatch(p -> p.color() == 1) && many.stream().anyMatch(p -> p.color() == 2));
        assertTrue(many.stream().allMatch(p -> Math.sqrt(p.x() * p.x() + (p.y() - 10) * (p.y() - 10) + p.z() * p.z()) <= 40.001));
        Outline.Box huge = Outline.Box.blocks(-500, 0, -500, 500, 100, 500, 3);
        assertTrue(Outline.points(List.of(huge), 0, 50, 0, 40, 200, 0.5).isEmpty());   // рёбра огромной зоны далеко
    }
}
