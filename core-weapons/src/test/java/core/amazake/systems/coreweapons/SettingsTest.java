package core.amazake.systems.coreweapons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        try (var in = new InputStreamReader(SettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            y.load(in);
        }
        final List<String> warns = new ArrayList<>();
        final Settings s = Settings.from(y, warns::add);
        assertTrue(warns.isEmpty(), warns.toString());
        final Settings.Weapon w = s.weapon("azerus_crimson_scythe");
        assertNotNull(w);
        assertEquals("azerus:crimson_scythe", w.testItem().itemModel());
        assertEquals(3, w.orbit().points());
        assertEquals(List.of("cw_swing_1", "cw_swing_2", "cw_swing_3"), w.swing().frames().names());
        assertEquals(6.0, w.tremor().radius());
        assertEquals(0.0, w.tremor().damage());
        assertEquals(3, w.tremor().sounds().size());
        assertEquals("entity.warden.step", w.tremor().sounds().get(0).key());
    }

    @Test
    void disabledBehaviourIsNull() throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("weapons:\n  x:\n    orbit:\n      enabled: false\n    crit-tremor:\n      radius: 99\n");
        final Settings.Weapon w = Settings.from(y, m -> { }).weapon("x");
        assertNull(w.orbit());
        assertNull(w.swing());
        assertEquals(16.0, w.tremor().radius());
    }

    @Test
    void soundSpec() {
        assertEquals(new Settings.SoundSpec("minecraft:block.stone.break", 0.5f, 2f),
            Settings.SoundSpec.parse("minecraft:block.stone.break:0.5:2"));
        assertEquals(new Settings.SoundSpec("entity.x", 1f, 1f), Settings.SoundSpec.parse("entity.x"));
        assertNull(Settings.SoundSpec.parse(" "));
    }
}
