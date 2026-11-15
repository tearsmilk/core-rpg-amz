package core.amazake.systems.corenpc;

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
    void bundledConfigParsesWithoutWarnings() throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        try (var in = new InputStreamReader(SettingsTest.class.getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8)) {
            y.load(in);
        }
        final List<String> warns = new ArrayList<>();
        final Settings s = Settings.from(y, warns::add);
        assertTrue(warns.isEmpty(), warns.toString());
        assertEquals(Settings.Mode.HUD, s.dialog().mode());
        assertEquals(6, s.dialog().maxDistance());
        assertEquals(2, s.dialog().refreshTicks());
        assertEquals("", s.message("cooldown"));
        assertTrue(s.message("no-npc").contains("<id>"));
    }

    @Test
    void badValuesFallBack() throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("dialog:\n  mode: weird\n  chars-per-tick: 0\nnpc:\n  look-radius: 500\n");
        final List<String> warns = new ArrayList<>();
        final Settings s = Settings.from(y, warns::add);
        assertEquals(3, warns.size(), warns.toString());
        assertEquals(Settings.Mode.HUD, s.dialog().mode());
        assertEquals(1.0, s.dialog().charsPerTick());
        assertEquals(8, s.npc().lookRadius());
    }
}
