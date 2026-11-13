package core.amazake.systems.corerpg.sharpen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.corerpg.util.Gear;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SharpenSettingsTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = SharpenSettingsTest.class.getResourceAsStream("/sharpen.yml")) {
            assertNotNull(in, "sharpen.yml в ресурсах");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static YamlConfiguration yaml(final String text) throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    @Test
    void defaultsMatchContract() {
        final SharpenSettings s = SharpenSettings.DEFAULTS;
        assertEquals(SharpenSettings.Mode.ROYAL_QUEST, s.mode());
        assertEquals(10, s.maxLevel(Gear.Grade.C));
        assertEquals(11, s.maxLevel(Gear.Grade.B));
        assertEquals(11, s.maxLevel(Gear.Grade.A));
        assertEquals(200.0, s.bonus(false, 10));
        assertEquals(260.0, s.bonus(false, 11));
        assertEquals(100.0, s.bonus(true, 10));
        assertEquals(130.0, s.bonus(true, 11));
        assertEquals(35.1, s.bonus(true, 5));
        assertEquals(0.0, s.bonus(true, 0));
        assertEquals(10.0, s.legendaryChance(0));
        assertEquals(35.0, s.legendaryChance(3));
        assertEquals(100.0, s.legendaryChance(10));
        assertEquals(100.0, s.legendaryChance(15), "жертва выше +10 — как +10");
        assertEquals(1, s.legendaryStat());
        assertEquals(2.0, s.multiplier(Consumable.BLACK));
        assertEquals(8.0, s.multiplier(Consumable.PURPLE));
        assertEquals(1.0, s.multiplier(Consumable.CUBE));
        assertEquals(List.of("Королевский алхимик"), s.npcNames());
        assertEquals(Material.AMETHYST_SHARD, s.def(Consumable.SHARD).material());
        assertEquals(Material.BLUE_ICE, s.def(Consumable.RUNE_COLD).material());
        assertEquals(9, s.consumables().size());
    }

    @Test
    void bundledFileParsesWithoutWarningsAndMatchesDefaults() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final SharpenSettings s = SharpenSettings.from(bundled(), warnings::add);
        assertTrue(warnings.isEmpty(), () -> "предупреждения: " + warnings);
        final SharpenSettings d = SharpenSettings.DEFAULTS;
        assertEquals(d.mode(), s.mode());
        assertEquals(d.maxLevel(), s.maxLevel());
        assertEquals(d.armorBonus(), s.armorBonus());
        assertEquals(d.weaponBonus(), s.weaponBonus());
        assertEquals(d.legendaryChances(), s.legendaryChances());
        assertEquals(d.simpleSuccess(), s.simpleSuccess());
        assertEquals(d.simpleDestroy(), s.simpleDestroy());
        assertEquals(d.multipliers(), s.multipliers());
        assertEquals(d.consumables(), s.consumables());
        for (final String cat : SharpenSettings.CATEGORIES) {
            for (final Gear.Grade g : Gear.Grade.values()) {
                for (int lvl = 1; lvl <= 10; lvl++) {
                    for (int col = 0; col < 5; col++) {
                        assertEquals(d.rqSuccess().get(cat, g, lvl, col), s.rqSuccess().get(cat, g, lvl, col));
                        assertEquals(d.rqDestroy().get(cat, g, lvl, col), s.rqDestroy().get(cat, g, lvl, col));
                    }
                }
            }
        }
    }

    @Test
    void rqTableSpotChecks() {
        final SharpenSettings s = SharpenSettings.DEFAULTS;
        // armor A +10: осколок 0.5 («<1%»), пурпурный 4
        assertEquals(0.5, s.rqSuccess().get("armor", Gear.Grade.A, 10, 0));
        assertEquals(4.0, s.rqSuccess().get("armor", Gear.Grade.A, 10, 4));
        assertEquals(52.0, s.rqDestroy().get("armor", Gear.Grade.A, 10, 0));
        // weapon_1h C +9: [14, 28, 56, 84, 100]
        assertEquals(84.0, s.rqSuccess().get("weapon_1h", Gear.Grade.C, 9, 3));
        // weapon_2h B +2 осколок 67
        assertEquals(67.0, s.rqSuccess().get("weapon_2h", Gear.Grade.B, 2, 0));
        // вне таблицы — 0
        assertEquals(0.0, s.rqSuccess().get("armor", Gear.Grade.C, 11, 0));
        assertEquals(0.0, s.rqSuccess().get("unknown", Gear.Grade.C, 1, 0));
    }

    @Test
    void badValuesWarnAndFallBack() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final SharpenSettings s = SharpenSettings.from(yaml("""
            mode: hardcore
            max-level:
              C: 15
              B: abc
              A: 9
            bonuses:
              armor:
                1: -5
                2: "x"
                zz: 3
                12: 5
                3: 40
            legendary:
              chances:
                0: 150
                5: 55
              stat-bonus: -1
            royal-quest:
              success:
                armor:
                  C:
                    1: [1, 2, 3]
                    2: [50, 60, 70, 80, 200]
                    11: [1, 1, 1, 1, 1]
                weapon_3h: {}
            simple:
              stone-multipliers:
                black: 0
                white: 3
                rune_save: 2
            consumables:
              shard:
                material: NOT_A_MATERIAL
              gold:
                name: x
            sounds:
              success: "Bad Sound!"
              fail:
                sound: block.anvil.use
                volume: 50
            """), warnings::add);

        assertEquals(SharpenSettings.Mode.ROYAL_QUEST, s.mode());
        assertEquals(10, s.maxLevel(Gear.Grade.C));
        assertEquals(11, s.maxLevel(Gear.Grade.B));
        assertEquals(9, s.maxLevel(Gear.Grade.A));
        assertEquals(10.0, s.bonus(false, 1));
        assertEquals(22.0, s.bonus(false, 2));
        assertEquals(40.0, s.bonus(false, 3));
        assertEquals(10.0, s.legendaryChance(0));
        assertEquals(55.0, s.legendaryChance(5));
        assertEquals(1, s.legendaryStat());
        // строка 1 неверной длины — по умолчанию; строка 2: 200 вне диапазона → по умолчанию только эта ячейка
        assertEquals(100.0, s.rqSuccess().get("armor", Gear.Grade.C, 1, 0));
        assertEquals(50.0, s.rqSuccess().get("armor", Gear.Grade.C, 2, 0));
        assertEquals(80.0, s.rqSuccess().get("armor", Gear.Grade.C, 2, 3));
        assertEquals(100.0, s.rqSuccess().get("armor", Gear.Grade.C, 2, 4));
        assertEquals(2.0, s.multiplier(Consumable.BLACK));
        assertEquals(3.0, s.multiplier(Consumable.WHITE));
        assertEquals(Material.AMETHYST_SHARD, s.def(Consumable.SHARD).material());
        assertEquals("block.enchantment_table.use", s.sounds().success().key());
        assertEquals("block.anvil.use", s.sounds().fail().key());
        assertEquals(1.0f, s.sounds().fail().volume());

        final String all = String.join("\n", warnings);
        for (final String expected : List.of("mode", "max-level.C", "max-level.B", "bonuses.armor.1", "bonuses.armor.2",
            "bonuses.armor.zz", "bonuses.armor.12", "legendary.chances.0", "legendary.stat-bonus",
            "royal-quest.success.armor.C.1", "royal-quest.success.armor.C.2[4]", "royal-quest.success.armor.C.11",
            "royal-quest.success.weapon_3h", "simple.stone-multipliers.black", "simple.stone-multipliers.rune_save",
            "consumables.shard.material", "consumables.gold", "sounds.success", "sounds.fail.volume")) {
            assertTrue(all.contains(expected), () -> "нет предупреждения про " + expected + ":\n" + all);
        }
    }

    @Test
    void simpleModeAndListForms() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final SharpenSettings s = SharpenSettings.from(yaml("""
            mode: simple
            bonuses:
              weapon: [1, 2, 3]
            simple:
              success-chances:
                armor: [50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50]
            npc:
              enabled: true
              names: []
            sounds:
              destroy: none
            """), warnings::add);
        assertEquals(SharpenSettings.Mode.SIMPLE, s.mode());
        assertEquals(1.0, s.bonus(true, 1));
        assertEquals(3.0, s.bonus(true, 3));
        assertEquals(26.0, s.bonus(true, 4), "остальное — по умолчанию");
        assertEquals(50.0, s.simpleSuccess().get("armor").get(10));
        assertEquals(11, s.simpleSuccess().get("armor").size());
        assertTrue(s.npcNames().isEmpty());
        assertFalse(s.sounds().destroy().present());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("bonuses.weapon")), warnings::toString);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("npc.names")), warnings::toString);
    }

    @Test
    void modeAliases() {
        assertEquals(SharpenSettings.Mode.ROYAL_QUEST, SharpenSettings.Mode.byKey("Royal_Quest"));
        assertEquals(SharpenSettings.Mode.ROYAL_QUEST, SharpenSettings.Mode.byKey("rq"));
        assertEquals(SharpenSettings.Mode.SIMPLE, SharpenSettings.Mode.byKey(" SIMPLE "));
        assertNull(SharpenSettings.Mode.byKey("x"));
    }

    @Test
    void resolvesRussianNames() {
        final SharpenSettings s = SharpenSettings.DEFAULTS;
        assertEquals(Consumable.SHARD, s.resolveConsumable("осколок"));
        assertEquals(Consumable.SHARD, s.resolveConsumable("SHARD"));
        assertEquals(Consumable.CRUMB, s.resolveConsumable("Крошка"));
        assertEquals(Consumable.CUBE, s.resolveConsumable("кубик"));
        assertEquals(Consumable.BLACK, s.resolveConsumable("чёрный"));
        assertEquals(Consumable.BLACK, s.resolveConsumable("черный"));
        assertEquals(Consumable.WHITE, s.resolveConsumable("белый"));
        assertEquals(Consumable.RED, s.resolveConsumable("красный"));
        assertEquals(Consumable.PURPLE, s.resolveConsumable("пурпурный"));
        assertEquals(Consumable.RUNE_SAVE, s.resolveConsumable("руна сохранения"));
        assertEquals(Consumable.RUNE_SAVE, s.resolveConsumable("Руна_Сохранения"));
        assertEquals(Consumable.RUNE_COLD, s.resolveConsumable("руна холода"));
        assertEquals(Consumable.PURPLE, s.resolveConsumable("Пурпурный философский камень"));
        assertNull(s.resolveConsumable("алмаз"));
        assertNull(s.resolveConsumable(""));
    }

    @Test
    void strictStatBonusNpcEnabledAndGradeKeys() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final SharpenSettings s = SharpenSettings.from(yaml("""
            max-level:
              C: 10
              b: 5
            legendary:
              stat-bonus: abc
            npc:
              enabled: "нет"
            royal-quest:
              success:
                armor:
                  b:
                    1: [1, 2, 3, 4, 5]
            """), warnings::add);
        assertEquals(1, s.legendaryStat());
        assertTrue(s.npcEnabled());
        assertEquals(11, s.maxLevel(Gear.Grade.B));
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("legendary.stat-bonus")), warnings::toString);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("npc.enabled")), warnings::toString);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("max-level.b")), warnings::toString);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("royal-quest.success.armor.b")), warnings::toString);

        final List<String> w2 = new ArrayList<>();
        final SharpenSettings frac = SharpenSettings.from(yaml("legendary:\n  stat-bonus: 2.7\n"), w2::add);
        assertEquals(1, frac.legendaryStat());
        assertFalse(w2.isEmpty());
        final List<String> w3 = new ArrayList<>();
        assertEquals(3, SharpenSettings.from(yaml("legendary:\n  stat-bonus: 3\nnpc:\n  enabled: false\n"), w3::add).legendaryStat());
        assertTrue(w3.isEmpty(), w3::toString);
    }
}
