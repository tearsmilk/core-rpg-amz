package core.amazake.systems.coreworld.hunger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.coreworld.module.WorldFilter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HungerSettingsTest {

    private final List<String> warnings = new ArrayList<>();

    private HungerSettings parse(final String yaml) throws Exception {
        return HungerSettings.parse(yaml, this.warnings::add);
    }

    @Test
    void bundledConfigGivesDefaults() throws Exception {
        final String yaml;
        try (InputStream in = HungerSettingsTest.class.getResourceAsStream("/config.yml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(HungerSettings.DEFAULTS, this.parse(yaml));
        assertTrue(this.warnings.isEmpty(), this.warnings.toString());
    }

    @Test
    void emptyConfigGivesDefaults() throws Exception {
        final HungerSettings s = this.parse("");
        assertEquals(HungerSettings.DEFAULTS, s);
        assertTrue(s.hungerDisabled());
        assertTrue(s.keepSaturation());
        assertTrue(s.worlds().allWorlds());
        assertTrue(this.warnings.isEmpty());
    }

    @Test
    void allValues() throws Exception {
        final HungerSettings s = this.parse("""
            hunger:
              disabled: true
              worlds: [world, Spawn]
              keep-saturation: false
            """);
        assertTrue(s.hungerDisabled());
        assertFalse(s.keepSaturation());
        assertEquals(Set.of("world", "spawn"), s.worlds().worlds());
        assertTrue(this.warnings.isEmpty());
    }

    @Test
    void disabledFalseTurnsModuleOff() throws Exception {
        final HungerSettings s = this.parse("hunger:\n  disabled: false\n");
        assertFalse(s.hungerDisabled());
        assertFalse(s.affects("world"));
    }

    @Test
    void affectsRespectsWorldList() throws Exception {
        final HungerSettings all = this.parse("hunger:\n  worlds: []\n");
        assertTrue(all.affects("world"));
        assertTrue(all.affects("world_the_end"));

        final HungerSettings some = this.parse("hunger:\n  worlds:\n    - world\n    - world_nether\n");
        assertTrue(some.affects("world"));
        assertTrue(some.affects("WORLD_NETHER"));
        assertFalse(some.affects("world_the_end"));

        final HungerSettings off = new HungerSettings(false, WorldFilter.ALL, true);
        assertFalse(off.affects("world"));
    }

    @Test
    void singleWorldStringIsAcceptedWithWarning() throws Exception {
        final HungerSettings s = this.parse("hunger:\n  worlds: world\n");
        assertEquals(Set.of("world"), s.worlds().worlds());
        assertEquals(1, this.warnings.size());
    }

    @Test
    void blankWorldEntriesAreSkipped() throws Exception {
        final HungerSettings s = this.parse("hunger:\n  worlds: ['', world]\n");
        assertEquals(Set.of("world"), s.worlds().worlds());
        assertEquals(1, this.warnings.size());
    }

    @Test
    void badValuesFallBackToDefaults() throws Exception {
        final HungerSettings s = this.parse("""
            hunger:
              disabled: maybe
              worlds: {a: 1}
              keep-saturation: 5
            """);
        assertEquals(HungerSettings.DEFAULTS, s);
        assertEquals(3, this.warnings.size(), this.warnings.toString());
    }

    @Test
    void hungerNotASectionFallsBack() throws Exception {
        assertEquals(HungerSettings.DEFAULTS, this.parse("hunger: false\n"));
        assertEquals(1, this.warnings.size());
    }
}
