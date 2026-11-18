package core.amazake.systems.coremechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsTest {

    @Test
    void defaultsMatchOriginal() {
        final Settings s = Settings.DEFAULTS;
        assertEquals(90 * 20, s.deathLifeTicks());
        assertEquals(300 * 20, s.commandLifeTicks());
        assertEquals(12, s.maxDolls());
        assertEquals(6.0, s.grabReach());
        assertEquals(17.0, s.physicsGravity());
        assertEquals("", s.mineskinKey());
        assertEquals(2, s.libsRepositories().size());
    }

    @Test
    void bundledConfigEqualsDefaults() throws Exception {
        try (InputStreamReader r = new InputStreamReader(SettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            final List<String> warns = new ArrayList<>();
            assertEquals(Settings.DEFAULTS, Settings.from(YamlConfiguration.loadConfiguration(r), warns::add));
            assertTrue(warns.isEmpty(), warns.toString());
        }
    }

    @Test
    void clampsBadValues() throws Exception {
        final YamlConfiguration c = new YamlConfiguration();
        c.loadFromString("ragdoll:\n  max-dolls: 1000\n  grab-reach: -3\nphysics:\n  substeps: 0\n");
        final List<String> warns = new ArrayList<>();
        final Settings s = Settings.from(c, warns::add);
        assertEquals(64, s.maxDolls());
        assertEquals(1.0, s.grabReach());
        assertEquals(1, s.physicsSubsteps());
        assertEquals(3, warns.size());
    }
}
