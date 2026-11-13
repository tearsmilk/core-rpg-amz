package core.amazake.systems.corerpg.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ProgressionSettingsTest {

    @Test
    void shippedFileEqualsDefaults() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        final ProgressionSettings s = ProgressionSettings.from(TestYaml.resource("progression.yml"), w::add);
        assertEquals(java.util.List.of(), w.list);
        assertEquals(ProgressionSettings.DEFAULTS.maxLevel(), s.maxLevel());
        assertEquals(21, s.professionLevel());
        assertEquals(ProgressionSettings.DEFAULT_TABLE, s.expCurve().table());
        assertEquals(ProgressionSettings.DEFAULTS.expCurve(), s.expCurve());
        assertEquals(StatSettings.DEFAULTS, s.stats());
        assertEquals(5, s.pointsEveryLevels());
        assertEquals(4, s.pointsPerStep());
        assertTrue(s.blockExpUntilProfession());
        assertTrue(s.convertVanillaExp());
        assertEquals(1.0, s.vanillaExpMultiplier());
        assertEquals(15, s.blockedMessageCooldownSeconds());
        // сообщения в файле совпадают с встроенными (многострочные — списком)
        assertEquals(Messages.DEFAULTS, s.messages().values());
    }

    @Test
    void emptyConfigGivesDefaults() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        final ProgressionSettings s = ProgressionSettings.from(new YamlConfiguration(), w::add);
        assertTrue(w.list.isEmpty());
        assertEquals(ProgressionSettings.DEFAULTS, s);
    }

    @Test
    void badValuesWarnAndFallBack() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        final ProgressionSettings s = ProgressionSettings.from(TestYaml.of("""
            max-level: 1
            profession-level: abc
            block-exp-until-profession: maybe
            vanilla-exp-multiplier: -3
            points-every-levels: 0
            points-per-step: 2.5
            exp-blocked-message-cooldown-seconds: 99999
            level-up:
              pitch: 7
            stats:
              primary:
                attack-damage-per-point: "много"
              luck:
                crit-formula:
                  base: 1.5
                crit-chance-cap: 150
            messages:
              unknown-key: "x"
              level-up: "<red>LVL <level></red>"
            """), w::add);
        assertEquals(71, s.maxLevel());
        assertEquals(21, s.professionLevel());
        assertTrue(s.blockExpUntilProfession());
        assertEquals(1.0, s.vanillaExpMultiplier());
        assertEquals(5, s.pointsEveryLevels());
        assertEquals(4, s.pointsPerStep());
        assertEquals(15, s.blockedMessageCooldownSeconds());
        assertEquals(1.0f, s.levelUpPitch());
        assertEquals(0.5, s.stats().primaryDamagePerPoint());
        assertEquals(0.999, s.stats().critBase());
        assertEquals(57.0, s.stats().critChanceCap());
        assertEquals("<red>LVL <level></red>", s.messages().get("level-up"));
        assertEquals(Messages.DEFAULTS.get("class-chosen"), s.messages().get("class-chosen"));
        assertTrue(w.any("max-level"));
        assertTrue(w.any("profession-level"));
        assertTrue(w.any("block-exp-until-profession"));
        assertTrue(w.any("vanilla-exp-multiplier"));
        assertTrue(w.any("points-every-levels"));
        assertTrue(w.any("points-per-step"));
        assertTrue(w.any("exp-blocked-message-cooldown-seconds"));
        assertTrue(w.any("level-up.pitch"));
        assertTrue(w.any("stats.primary.attack-damage-per-point"));
        assertTrue(w.any("stats.luck.crit-formula.base"));
        assertTrue(w.any("stats.luck.crit-chance-cap"));
        assertTrue(w.any("messages.unknown-key"));
    }

    @Test
    void professionLevelClampedToMax() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        final ProgressionSettings s = ProgressionSettings.from(TestYaml.of("max-level: 30\nprofession-level: 40\n"), w::add);
        assertEquals(30, s.maxLevel());
        assertEquals(30, s.professionLevel());
        assertTrue(w.any("profession-level"));
        assertEquals(0, s.expToNext(30));
        assertTrue(s.expToNext(29) > 0);
    }

    @Test
    void expTableParsing() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        final ProgressionSettings s = ProgressionSettings.from(TestYaml.of("""
            max-level: 10
            exp-table:
              1: 0
              2: 50
              3: x
              4: -7
              abc: 5
              11: 99
              5: 12.5
            exp-formula:
              base: 10
              exponent: 2
            """), w::add);
        final ExpCurve c = s.expCurve();
        assertEquals(50, c.need(2));
        assertEquals(90, c.need(3)); // формула 10 × 3²
        assertEquals(160, c.need(4));
        assertEquals(250, c.need(5));
        assertEquals(1, c.table().size());
        assertTrue(w.any("exp-table.3"));
        assertTrue(w.any("exp-table.4"));
        assertTrue(w.any("exp-table.abc"));
        assertTrue(w.any("exp-table.11"));
        assertTrue(w.any("exp-table.5"));
        assertFalse(w.any("exp-table.1:"));
    }

    @Test
    void expTableNotASection() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        final ProgressionSettings s = ProgressionSettings.from(TestYaml.of("exp-table: 5\n"), w::add);
        assertEquals(ProgressionSettings.DEFAULT_TABLE, s.expCurve().table());
        assertTrue(w.any("exp-table"));
    }

    @Test
    void messagesFromList() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        final Messages m = Messages.from(TestYaml.of("""
            messages:
              info:
                - "a"
                - "b"
              level-up:
                nested: 1
            """).getConfigurationSection("messages"), w::add);
        assertEquals("a\nb", m.get("info"));
        assertEquals(Messages.DEFAULTS.get("level-up"), m.get("level-up"));
        assertTrue(w.any("messages.level-up"));
        assertEquals("нет-такого", m.get("нет-такого"));
    }
}
