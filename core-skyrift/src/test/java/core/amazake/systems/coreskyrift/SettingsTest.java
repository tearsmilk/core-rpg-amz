package core.amazake.systems.coreskyrift;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Color;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsTest {

    @Test
    void bundledConfigEqualsDefaults() throws Exception {
        final YamlConfiguration c;
        try (var in = new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            c = YamlConfiguration.loadConfiguration(in);
        }
        final List<String> warnings = new ArrayList<>();
        final Settings s = Settings.from(c, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(Settings.DEFAULTS, s);
    }

    @Test
    void emptyConfigGivesDefaults() {
        final List<String> warnings = new ArrayList<>();
        assertEquals(Settings.DEFAULTS, Settings.from(new YamlConfiguration(), warnings::add));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void badValuesWarnAndFallBack() {
        final YamlConfiguration c = new YamlConfiguration();
        c.set("event.radius", 5000);
        c.set("rift.yaw", "sideways");
        c.set("mobs.per-wave", "5-2");
        c.set("mobs.spawn-mode", "everywhere");
        c.set("mobs.command", "summon zombie");
        c.set("mobs.min-distance", 30);
        c.set("mobs.max-distance", 10);
        c.set("timeline.opening", "long");
        c.set("mobs.list.Bad Id", 3);
        c.set("mobs.list.forest_zombie", 2);
        final List<String> warnings = new ArrayList<>();
        final Settings s = Settings.from(c, warnings::add);
        final Settings d = Settings.DEFAULTS;
        assertEquals(d.event().radius(), s.event().radius());
        assertEquals(Settings.YawMode.RANDOM, s.rift().yawMode());
        assertEquals(d.mobs().perWaveMin(), s.mobs().perWaveMin());
        assertEquals(d.mobs().spawnMode(), s.mobs().spawnMode());
        assertEquals(d.mobs().command(), s.mobs().command());
        assertEquals(d.mobs().minDistance(), s.mobs().minDistance());
        assertEquals(d.times().opening(), s.times().opening());
        assertEquals(java.util.Map.of("forest_zombie", 2), s.mobs().list());
        assertTrue(warnings.size() >= 8, warnings.toString());
    }

    @Test
    void parsedValues() {
        final YamlConfiguration c = new YamlConfiguration();
        c.set("rift.yaw", 450);
        c.set("mobs.per-wave", 3);
        c.set("mobs.spawn-mode", "under-rift");
        c.set("mobs.command", "/mobs spawn {id} {amount} {world} {x} {y} {z}");
        c.set("mobs.list", List.of("a_mob", "b"));
        final Settings s = Settings.from(c, w -> { });
        assertEquals(Settings.YawMode.FIXED, s.rift().yawMode());
        assertEquals(90, s.rift().yawDegrees(), 1e-9);
        assertEquals(3, s.mobs().perWaveMin());
        assertEquals(3, s.mobs().perWaveMax());
        assertEquals(Settings.SpawnMode.UNDER_RIFT, s.mobs().spawnMode());
        assertEquals("mobs spawn {id} {amount} {world} {x} {y} {z}", s.mobs().command());
        assertEquals(List.of("a_mob", "b"), List.copyOf(s.mobs().list().keySet()));
        assertEquals(6, s.mobs().aliveCap(1));
        assertEquals(20, s.mobs().aliveCap(10));
    }

    @Test
    void ranges() {
        assertArrayEquals(new int[] {2, 4}, Settings.parseRange("2-4"));
        assertArrayEquals(new int[] {3, 3}, Settings.parseRange(" 3 "));
        assertNull(Settings.parseRange("x"));
        assertFalse(Settings.validId("Bad Id"));
        assertTrue(Settings.validId("venom_spider"));
    }

    @Test
    void markersStayInRange() {
        final Color c = Markers.paramRaw(Markers.RIFT, 400);
        assertEquals(255, c.getRed());
        assertEquals(97, c.getGreen());
        assertEquals(250, c.getBlue());
        assertEquals(0, Markers.paramRaw(Markers.FOG, -5).getBlue());
        assertEquals(125, Markers.paramByte(0.5));
        assertEquals(0, Markers.paramByte(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Markers.paramRaw(95, 0));
        assertThrows(IllegalArgumentException.class, () -> Markers.paramRaw(112, 0));
    }
}
