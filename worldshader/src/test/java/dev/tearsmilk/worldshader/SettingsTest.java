package dev.tearsmilk.worldshader;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    private static Path root() {
        return Path.of(System.getProperty("worldshader.root", "."));
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(new StringReader(text));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return y;
    }

    @Test
    void defaultsFromShippedConfig() throws IOException {
        Settings s = Settings.from(yaml(Files.readString(root().resolve("src/main/resources/config.yml"), StandardCharsets.UTF_8)));
        assertTrue(s.packInstall());
        assertEquals("", s.packDir());
        assertTrue(s.reloadNexo());
        assertEquals(10, s.radius());
        assertEquals(20, s.strength());
        assertEquals(60, s.seconds());
        assertEquals(86400, s.maxSeconds());
        assertEquals(4.0f, s.viewRange());
        assertEquals(64, s.targetDistance());
    }

    @Test
    void emptyConfigGivesSameDefaults() {
        Settings s = Settings.from(new MemoryConfiguration());
        assertEquals(10, s.radius());
        assertEquals(20, s.strength());
        assertEquals(60, s.seconds());
        assertTrue(s.packInstall());
    }

    @Test
    void valuesAreClamped() {
        Settings s = Settings.from(yaml("pack:\n  install: false\n  dir: /tmp/x\n  reload-nexo: false\n"
                + "zone:\n  radius: 500\n  strength: 0\n  seconds: 999999\n  max-seconds: 120\n  view-range: 0.1\n  target-distance: 9999\n"));
        assertFalse(s.packInstall());
        assertEquals("/tmp/x", s.packDir());
        assertFalse(s.reloadNexo());
        assertEquals(ZoneCode.MAX_RADIUS, s.radius());
        assertEquals(1, s.strength());
        assertEquals(120, s.maxSeconds());
        assertEquals(120, s.seconds());
        assertEquals(1.0f, s.viewRange());
        assertEquals(256, s.targetDistance());
        Settings neg = Settings.from(yaml("zone:\n  radius: -3\n  strength: 99\n  seconds: -5\n  view-range: 99\n"));
        assertEquals(1, neg.radius());
        assertEquals(ZoneCode.MAX_STRENGTH, neg.strength());
        assertEquals(0, neg.seconds());
        assertEquals(16.0f, neg.viewRange());
    }

    @Test
    void zoneRecordReadsBack() {
        Zone z = new Zone(java.util.UUID.randomUUID(), "world_nether", 1.5, -56.5, 30.25, 3, 12, 25, 1000L, 61000L);
        YamlConfiguration y = new YamlConfiguration();
        Zones.write(y.createSection("z"), z);
        YamlConfiguration back = yaml(y.saveToString());
        assertEquals(z, Zones.read(z.id().toString(), back.getConfigurationSection("z")));
        assertEquals(z.rgb(), ZoneCode.rgb(3, 12, 25));
        assertEquals(61, z.secondsLeft(0L));
        assertTrue(z.expired(61000L));
        assertFalse(z.expired(60999L));
        Zone forever = new Zone(z.id(), "world", 0, 0, 0, 0, 10, 20, 0L, 0L);
        assertEquals(-1, forever.secondsLeft(123456L));
        assertFalse(forever.expired(Long.MAX_VALUE));
        assertEquals(7, z.withSlot(7).slot());
        assertEquals("w", z.at("w", 1, 2, 3).world());
        assertTrue(Zones.read("not-a-uuid", back.getConfigurationSection("z")) == null);
    }
}
