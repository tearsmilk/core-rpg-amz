package core.amazake.systems.coreeffects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.coreeffects.effect.EffectType;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        assertEquals(List.of(), warns);
        assertEquals(120, s.of(EffectType.BLEEDING).defaultSeconds());
        assertEquals(Set.of("bandage"), s.of(EffectType.BLEEDING).curedBy());
        assertEquals(Set.of("milk", "healing-potion"), s.warmth().quenchedBy());
        assertEquals(2400, s.warmth().deadlineTicks());
        assertEquals(80, s.bleeding().damageIntervalTicks());
        assertTrue(s.panic().phantoms().types().contains("PLAYER"));
        assertTrue(s.hud().format().get(EffectType.BLEEDING).contains(""));
        assertTrue(s.curse().protectMaterials().contains("NETHER_STAR"));
    }

    @Test
    void badValuesFallBack() {
        final YamlConfiguration y = new YamlConfiguration();
        y.set("bleeding.damage", -3);
        y.set("bleeding.default-seconds", "abc");
        y.set("panic.twist-degrees", "10-999");
        y.set("hud.placeholderapi-identifier", "Плохо!");
        y.set("warmth.warn-at-percent", List.of(50, 150, "x"));
        final List<String> warns = new ArrayList<>();
        final Settings s = Settings.from(y, warns::add);
        assertEquals(1.0, s.bleeding().damage());
        assertEquals(120, s.of(EffectType.BLEEDING).defaultSeconds());
        assertEquals(60, s.panic().twistDegrees().min());
        assertEquals("coreeffects", s.hud().papiIdentifier());
        assertEquals(List.of(50), s.warmth().warnAtPercent());
        assertEquals(6, warns.size());
    }

    @Test
    void defaultsHaveAllMessages() {
        for (final String k : List.of("usage", "given", "cleared", "faint", "curse-took", "warmth-warn-3", "death-bleeding")) {
            assertTrue(!Settings.DEFAULTS.messages().get(k).isEmpty(), k);
        }
    }
}
