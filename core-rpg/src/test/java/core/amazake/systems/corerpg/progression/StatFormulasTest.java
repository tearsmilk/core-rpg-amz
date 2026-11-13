package core.amazake.systems.corerpg.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StatFormulasTest {

    private static final StatSettings S = StatSettings.DEFAULTS;
    private static final double EPS = 1e-9;

    @Test
    void primary() {
        final StatFormulas.Bonuses b = StatFormulas.primary(S, 23);
        assertEquals(23 * 0.5 + 2 * 3, b.attackDamage(), EPS);
        assertEquals(4, b.accuracy(), EPS); // 23 / 5 вниз
        assertEquals(0, StatFormulas.primary(S, -4).attackDamage(), EPS);
    }

    @Test
    void stamina() {
        assertEquals(20, StatFormulas.stamina(S, 10).maxHealth(), EPS);
    }

    @Test
    void agility() {
        final StatFormulas.Bonuses b = StatFormulas.agility(S, 25);
        assertEquals(25 * 0.01 + 2 * 0.1, b.attackSpeed(), EPS);
        assertEquals(4, b.evasion(), EPS);
    }

    @Test
    void luckCritFormula() {
        // рейтинг 20 удачи = 20×0.33 + 2 = 8.6
        assertEquals(8.6, StatFormulas.critRating(S, 20), EPS);
        final double expected = 100 * 0.6 * (1 - Math.pow(0.999, 8.6 * (60.1007 - 10 * 0.6491)));
        final StatFormulas.Bonuses b = StatFormulas.luck(S, 20, 10);
        assertEquals(expected, b.critChance(), EPS);
        assertEquals(4, b.accuracy(), EPS);
        assertEquals(10, b.critDamage(), EPS);
    }

    @Test
    void critChanceCapAndHighLevel() {
        assertEquals(57.0, StatFormulas.critChance(S, 100_000, 1), EPS);
        // на уровне > 92 множитель отрицательный — шанс не уходит в минус
        assertEquals(0.0, StatFormulas.critChance(S, 50, 200), EPS);
        assertEquals(0.0, StatFormulas.critChance(S, 0, 10), EPS);
        assertTrue(StatFormulas.critChance(S, 10, 71) < StatFormulas.critChance(S, 10, 1));
    }

    @Test
    void totalAddsCritBaseToEveryone() {
        final StatFormulas.Bonuses zero = StatFormulas.total(S, 1, 0, 0, 0, 0);
        assertEquals(50, zero.critDamage(), EPS);
        assertEquals(0, zero.attackDamage(), EPS);
        final StatFormulas.Bonuses some = StatFormulas.total(S, 1, 10, 10, 10, 10);
        assertEquals(10 * 0.5 + 3, some.attackDamage(), EPS);
        assertEquals(20, some.maxHealth(), EPS);
        assertEquals(2 + 2, some.accuracy(), EPS); // 10/5 + 2 за 10 удачи
        assertEquals(55, some.critDamage(), EPS);
    }

    @Test
    void ofMatchesParts() {
        assertEquals(StatFormulas.primary(S, 7), StatFormulas.of(S, StatType.PRIMARY, 7, 1));
        assertEquals(StatFormulas.luck(S, 7, 30), StatFormulas.of(S, StatType.LUCK, 7, 30));
    }

    @Test
    void statTypeNames() {
        assertEquals(StatType.PRIMARY, StatType.parse("Сила"));
        assertEquals(StatType.PRIMARY, StatType.parse("точность"));
        assertEquals(StatType.PRIMARY, StatType.parse("ИНТЕЛЛЕКТ"));
        assertEquals(StatType.PRIMARY, StatType.parse("primary"));
        assertEquals(StatType.AGILITY, StatType.parse("ловкость"));
        assertEquals(StatType.STAMINA, StatType.parse("stamina"));
        assertEquals(StatType.LUCK, StatType.parse("удача"));
        assertEquals(null, StatType.parse("мана"));
        assertEquals(null, StatType.parse(null));
    }
}
