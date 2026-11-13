package core.amazake.systems.corerpg.sharpen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.corerpg.util.Gear;
import java.util.List;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

class SharpenRulesTest {

    private static final SharpenSettings RQ = SharpenSettings.DEFAULTS;
    private static final SharpenSettings SIMPLE = simple();

    private static SharpenSettings simple() {
        final MemoryConfiguration c = new MemoryConfiguration();
        c.set("mode", "simple");
        return SharpenSettings.from(c, w -> { });
    }

    private static SharpenRules.Plan ok(final SharpenRules.Check check) {
        assertTrue(check.ok(), check::error);
        return check.plan();
    }

    @Test
    void rqUsesGradeAndStoneColumn() {
        // weapon_1h C, +8 → +9: [14, 28, 56, 84, 100], уничтожение [13, 11, 7, 2, 0]
        SharpenRules.Plan p = ok(SharpenRules.plan(RQ, "weapon_1h", Gear.Grade.C, 8, Consumable.SHARD, null, -1));
        assertEquals(SharpenRules.Type.NORMAL, p.type());
        assertEquals(9, p.target());
        assertEquals(14.0, p.success());
        assertEquals(13.0, p.destroy());
        p = ok(SharpenRules.plan(RQ, "weapon_1h", Gear.Grade.C, 8, Consumable.RED, null, -1));
        assertEquals(84.0, p.success());
        assertEquals(2.0, p.destroy());
        p = ok(SharpenRules.plan(RQ, "weapon_1h", Gear.Grade.C, 8, Consumable.PURPLE, null, -1));
        assertEquals(100.0, p.success());
        // Крошка — колонка осколка
        p = ok(SharpenRules.plan(RQ, "weapon_1h", Gear.Grade.C, 8, Consumable.CRUMB, null, -1));
        assertEquals(14.0, p.success());
        assertEquals(13.0, p.destroy());
    }

    @Test
    void crumbOnlyForGradeC() {
        final SharpenRules.Check check = SharpenRules.plan(RQ, "armor", Gear.Grade.B, 3, Consumable.CRUMB, null, -1);
        assertFalse(check.ok());
        assertTrue(check.error().contains("грейда C"));
        assertFalse(SharpenRules.plan(RQ, "armor", Gear.Grade.A, 3, Consumable.CRUMB, null, -1).ok());
    }

    @Test
    void cubeNeverDestroysButCanDropLevel() {
        final SharpenRules.Plan p = ok(SharpenRules.plan(RQ, "armor", Gear.Grade.A, 9, Consumable.CUBE, null, -1));
        assertEquals(0.5, p.success());
        assertTrue(p.noDestroy());
        assertFalse(p.noDown());
        assertEquals(0.0, p.destroy());
        assertEquals(SharpenRules.Outcome.FAIL_DOWN, SharpenRules.roll(p, 99.0, 0.0));
        assertEquals(8, p.levelAfterFail());
    }

    @Test
    void runeKeepsLevelAndItem() {
        final SharpenRules.Plan p = ok(SharpenRules.plan(RQ, "armor", Gear.Grade.A, 9, Consumable.SHARD, Consumable.RUNE_SAVE, -1));
        assertTrue(p.noDown());
        assertTrue(p.noDestroy());
        assertEquals(Consumable.RUNE_SAVE, p.rune());
        assertEquals(SharpenRules.Outcome.FAIL_KEEP, SharpenRules.roll(p, 99.0, 0.0));
        assertEquals(9, p.levelAfterFail());
        // Камень в слоте руны — не руна
        final SharpenRules.Plan q = ok(SharpenRules.plan(RQ, "armor", Gear.Grade.A, 9, Consumable.SHARD, Consumable.BLACK, -1));
        assertFalse(q.noDown());
        assertEquals(null, q.rune());
    }

    @Test
    void failWithoutRuneRollsDestroyThenDrops() {
        final SharpenRules.Plan p = ok(SharpenRules.plan(RQ, "armor", Gear.Grade.A, 9, Consumable.SHARD, null, -1));
        assertEquals(52.0, p.destroy());
        assertEquals(SharpenRules.Outcome.SUCCESS, SharpenRules.roll(p, 0.1, 0.0));
        assertEquals(SharpenRules.Outcome.DESTROYED, SharpenRules.roll(p, 50.0, 51.9));
        assertEquals(SharpenRules.Outcome.FAIL_DOWN, SharpenRules.roll(p, 50.0, 52.0));
        // С +0 провал не опускает ниже 0
        final SharpenRules.Plan zero = new SharpenRules.Plan(SharpenRules.Type.NORMAL, 0, 1, 0.0, 0.0, false, false,
            Consumable.SHARD, null, -1);
        assertEquals(SharpenRules.Outcome.FAIL_KEEP, SharpenRules.roll(zero, 50.0, 50.0));
        assertEquals(0, zero.levelAfterFail());
    }

    @Test
    void maxLevelByGrade() {
        assertFalse(SharpenRules.plan(RQ, "armor", Gear.Grade.C, 10, Consumable.PURPLE, null, -1).ok());
        assertFalse(SharpenRules.plan(RQ, "armor", Gear.Grade.B, 11, Consumable.PURPLE, null, -1).ok());
        assertFalse(SharpenRules.needsSacrifice(RQ, Gear.Grade.C, 10));
        assertTrue(SharpenRules.needsSacrifice(RQ, Gear.Grade.B, 10));
        assertTrue(SharpenRules.needsSacrifice(RQ, Gear.Grade.A, 10));
        assertFalse(SharpenRules.needsSacrifice(RQ, Gear.Grade.A, 9));
        assertFalse(SharpenRules.needsSacrifice(SIMPLE, Gear.Grade.A, 10));
        // Без камня обычная попытка невозможна
        assertFalse(SharpenRules.plan(RQ, "armor", Gear.Grade.C, 1, null, null, -1).ok());
    }

    @Test
    void legendaryNeedsSacrificeNotStone() {
        final SharpenRules.Check none = SharpenRules.plan(RQ, "weapon_2h", Gear.Grade.A, 10, Consumable.PURPLE, null, -1);
        assertFalse(none.ok());
        assertTrue(none.error().contains("жертвы"));

        final SharpenRules.Plan p = ok(SharpenRules.plan(RQ, "weapon_2h", Gear.Grade.A, 10, null, null, 4));
        assertEquals(SharpenRules.Type.LEGENDARY, p.type());
        assertEquals(11, p.target());
        assertEquals(40.0, p.success());
        assertTrue(p.noDestroy());
        assertTrue(p.noDown());
        assertEquals(SharpenRules.Outcome.FAIL_KEEP, SharpenRules.roll(p, 40.0, 0.0));
        assertEquals(10, p.levelAfterFail());
        assertEquals(SharpenRules.Outcome.SUCCESS, SharpenRules.roll(p, 39.9, 0.0));

        final double[] expected = {10, 20, 30, 35, 40, 50, 60, 70, 80, 90, 100};
        for (int lvl = 0; lvl <= 10; lvl++) {
            assertEquals(expected[lvl], ok(SharpenRules.plan(RQ, "armor", Gear.Grade.B, 10, null, null, lvl)).success());
        }
    }

    @Test
    void simpleModeMultipliesPluginTables() {
        // weapon_1h +5: 50% × чёрный 2 = 100; +9: 14 × 2 = 28; × 8 = 112 → 100
        assertEquals(100.0, ok(SharpenRules.plan(SIMPLE, "weapon_1h", Gear.Grade.C, 4, Consumable.BLACK, null, -1)).success());
        assertEquals(28.0, ok(SharpenRules.plan(SIMPLE, "weapon_1h", Gear.Grade.C, 8, Consumable.BLACK, null, -1)).success());
        assertEquals(100.0, ok(SharpenRules.plan(SIMPLE, "weapon_1h", Gear.Grade.C, 8, Consumable.PURPLE, null, -1)).success());
        assertEquals(13.0, ok(SharpenRules.plan(SIMPLE, "weapon_1h", Gear.Grade.C, 8, Consumable.SHARD, null, -1)).destroy());
        // +11 в простом режиме — обычная попытка камнем по 11-й строке
        final SharpenRules.Plan p = ok(SharpenRules.plan(SIMPLE, "weapon_2h", Gear.Grade.A, 10, Consumable.WHITE, null, -1));
        assertEquals(SharpenRules.Type.NORMAL, p.type());
        assertEquals(4.0, p.success());
        assertEquals(50.0, p.destroy());
    }

    @Test
    void averageArmorBonus() {
        assertEquals(0.0, SharpenRules.averageBonus(List.of()));
        assertEquals(200.0, SharpenRules.averageBonus(List.of(200.0, 200.0, 200.0, 200.0)));
        assertEquals(105.0, SharpenRules.averageBonus(List.of(200.0, 10.0)));
        assertEquals(10.0, SharpenRules.averageBonus(List.of(10.0, 0.0)));
    }

    @Test
    void loreLineAndRecognition() {
        final String line = SharpenRules.loreLine(10, true, 100.0);
        assertTrue(line.contains("+10"));
        assertTrue(line.contains("Атака"));
        assertTrue(line.contains("+100%"));
        assertTrue(line.contains("×2"));
        assertTrue(SharpenRules.loreLine(8, false, 136.2).contains("×2.36"));
        assertTrue(SharpenRules.isSharpenLore("Заточка: +10 (Атака +100% ×2)"));
        assertTrue(SharpenRules.isSharpenLore("  заточка +3"));
        assertTrue(SharpenRules.isSharpenLore("Защита +22%"));
        assertTrue(SharpenRules.isSharpenLore("×1.5"));
        assertFalse(SharpenRules.isSharpenLore(""));
        assertFalse(SharpenRules.isSharpenLore("Печать Ярости"));
        assertFalse(SharpenRules.isSharpenLore("+1.6 Урон"));
        assertFalse(SharpenRules.isSharpenLore("Заточенный клинок героя"));
    }

    @Test
    void percentFormatting() {
        assertEquals("\\<1", SharpenRules.percent(0.5));
        assertEquals("0", SharpenRules.percent(0));
        assertEquals("14", SharpenRules.percent(14));
        assertEquals("33.3", SharpenRules.percent(33.333));
        assertEquals("100", SharpenRules.percent(100));
    }

    @Test
    void consumableColumnsAndIds() {
        assertEquals(0, Consumable.SHARD.column());
        assertEquals(0, Consumable.CRUMB.column());
        assertEquals(0, Consumable.CUBE.column());
        assertEquals(1, Consumable.BLACK.column());
        assertEquals(4, Consumable.PURPLE.column());
        assertTrue(Consumable.RUNE_COLD.isRune());
        assertEquals(Consumable.RUNE_SAVE, Consumable.byId("RUNE_SAVE"));
        assertEquals("руна_сохранения", Consumable.normalize(" Руна  Сохранения "));
        assertEquals("черный_камень", Consumable.normalize("Чёрный-камень"));
    }
}
