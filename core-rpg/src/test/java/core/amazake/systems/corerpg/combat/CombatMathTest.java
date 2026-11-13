package core.amazake.systems.corerpg.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CombatMathTest {

    private static final double EPS = 1e-9;

    @Test
    void sharpenMultiplier() {
        assertEquals(1.0, CombatMath.sharpenMultiplier(0), EPS);
        assertEquals(2.0, CombatMath.sharpenMultiplier(100), EPS);
        assertEquals(2.3, CombatMath.sharpenMultiplier(130), EPS);
        assertEquals(1.0, CombatMath.sharpenMultiplier(-50), EPS);
        assertEquals(1.0, CombatMath.sharpenMultiplier(Double.NaN), EPS);
    }

    @Test
    void rageStepsAndCap() {
        // полное здоровье — без бонуса
        assertEquals(0.0, CombatMath.rageBonusPercent(20, 20, 2, 5, 40), EPS);
        // потеряно 25% — 5 шагов по 2%
        assertEquals(10.0, CombatMath.rageBonusPercent(15, 20, 2, 5, 40), EPS);
        // потеряно 24% — 4 шага
        assertEquals(8.0, CombatMath.rageBonusPercent(15.2, 20, 2, 5, 40), EPS);
        // почти мёртв — потолок 40
        assertEquals(38.0, CombatMath.rageBonusPercent(0.5, 20, 2, 5, 40), EPS);
        assertEquals(40.0, CombatMath.rageBonusPercent(0.0, 20, 2, 5, 40), EPS);
        assertEquals(40.0, CombatMath.rageBonusPercent(0.5, 20, 3, 5, 40), EPS);
        // неверный максимум — 0
        assertEquals(0.0, CombatMath.rageBonusPercent(5, 0, 2, 5, 40), EPS);
        // здоровье выше максимума — 0
        assertEquals(0.0, CombatMath.rageBonusPercent(30, 20, 2, 5, 40), EPS);
    }

    @Test
    void headshot() {
        assertTrue(CombatMath.isHeadshot(1.5, 1.62, 0.3));
        assertFalse(CombatMath.isHeadshot(1.2, 1.62, 0.3));
    }

    @Test
    void backstabAngle() {
        // атакующий прямо перед глазами
        assertEquals(0.0, CombatMath.horizontalAngle(0, 1, 0, 5), 1e-6);
        // ровно сзади
        assertEquals(180.0, CombatMath.horizontalAngle(0, 1, 0, -5), 1e-6);
        // сбоку
        assertEquals(90.0, CombatMath.horizontalAngle(0, 1, 3, 0), 1e-6);
        assertTrue(CombatMath.isBackstab(0, 1, 0, -1, 120));
        assertTrue(CombatMath.isBackstab(0, 1, 1, -1, 120)); // 135°
        assertFalse(CombatMath.isBackstab(0, 1, 1, 0, 120)); // 90°
        // нулевой вектор (стоят в одной точке) — не сзади
        assertFalse(CombatMath.isBackstab(0, 1, 0, 0, 120));
    }

    @Test
    void dualWield() {
        assertEquals(4.2, CombatMath.dualWieldBonus(7, 60), EPS);
        assertEquals(0.0, CombatMath.dualWieldBonus(-1, 60), EPS);
    }

    @Test
    void elementalBonusReducedByResistance() {
        assertEquals(0.10, CombatMath.elementalBonus(10, 0), EPS);
        assertEquals(0.06, CombatMath.elementalBonus(10, 0.04), EPS);
        assertEquals(0.0, CombatMath.elementalBonus(3, 0.07), EPS);
        assertEquals(0.0, CombatMath.elementalBonus(0, 0), EPS);
    }

    @Test
    void critChanceCapAndMultiplier() {
        assertEquals(0.30, CombatMath.critChance(30, 57), EPS);
        assertEquals(0.57, CombatMath.critChance(90, 57), EPS);
        assertEquals(0.0, CombatMath.critChance(-5, 57), EPS);
        assertEquals(1.5, CombatMath.critMultiplier(0, 50), EPS);
        assertEquals(1.75, CombatMath.critMultiplier(75, 50), EPS);
        // Контракт: ×(1 + crit_damage/100); запертый crit_damage = 0 при минимуме по умолчанию 0 — ×1.0.
        assertEquals(1.0, CombatMath.critMultiplier(0, CombatSettings.DEFAULTS.crit().minDamagePercent()), EPS);
        assertEquals(1.5, CombatMath.critMultiplier(50, CombatSettings.DEFAULTS.crit().minDamagePercent()), EPS);
    }

    @Test
    void attackChargeScalesDualWield() {
        assertEquals(1.0, CombatMath.attackCharge(8, 8), EPS);
        assertEquals(0.2, CombatMath.attackCharge(1.6, 8), EPS);
        assertEquals(1.0, CombatMath.attackCharge(12, 8), EPS);   // ванильный крит прыжком
        assertEquals(1.0, CombatMath.attackCharge(5, 0), EPS);    // нет атрибута
        assertEquals(0.0, CombatMath.attackCharge(Double.NaN, 8), EPS);
    }

    @Test
    void evasionChance() {
        assertEquals(0.10, CombatMath.evasionChance(14, 4, 0.85), EPS);
        assertEquals(0.0, CombatMath.evasionChance(4, 14, 0.85), EPS);
        assertEquals(0.85, CombatMath.evasionChance(500, 0, 0.85), EPS);
        assertEquals(0.0, CombatMath.evasionChance(Double.NaN, 0, 0.85), EPS);
    }

    @Test
    void armorDivisor() {
        assertEquals(1.0, CombatMath.armorDivisor(0), EPS);
        assertEquals(3.0, CombatMath.armorDivisor(200), EPS);
        assertEquals(1.0, CombatMath.armorDivisor(-10), EPS);
    }

    @Test
    void resistanceCap() {
        assertEquals(0.9, CombatMath.resistanceMultiplier(0.1, 0.4), EPS);
        assertEquals(0.6, CombatMath.resistanceMultiplier(0.9, 0.4), EPS);
        assertEquals(1.0, CombatMath.resistanceMultiplier(-0.3, 0.4), EPS);
        assertEquals(0.5, CombatMath.resistanceMultiplier(0.5, 7), EPS); // потолок обрезается до 1
    }

    @Test
    void lifestealCap() {
        assertEquals(1.0, CombatMath.lifestealHeal(10, 10, 20), EPS);
        assertEquals(2.0, CombatMath.lifestealHeal(10, 50, 20), EPS);
        assertEquals(0.0, CombatMath.lifestealHeal(-3, 50, 20), EPS);
    }

    @Test
    void regenAndHeal() {
        assertEquals(10.5, CombatMath.regenHealth(10, 20, 0.5, 20), EPS);
        assertEquals(11.0, CombatMath.regenHealth(10, 20, 0.5, 40), EPS);
        assertEquals(20.0, CombatMath.regenHealth(19.9, 20, 5, 20), EPS);
        assertEquals(20.0, CombatMath.regenHealth(20, 20, 5, 20), EPS);
        assertEquals(25.0, CombatMath.regenHealth(25, 20, 5, 20), EPS); // выше максимума не трогаем
        assertEquals(10.0, CombatMath.regenHealth(10, 20, 0, 20), EPS);
        assertEquals(20.0, CombatMath.healed(18, 20, 5), EPS);
        assertEquals(18.0, CombatMath.healed(18, 20, -5), EPS);
    }
}
