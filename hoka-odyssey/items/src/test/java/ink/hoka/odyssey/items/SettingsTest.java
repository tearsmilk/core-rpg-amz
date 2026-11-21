package ink.hoka.odyssey.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.api.ModuleConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {
    @TempDir
    Path dir;

    @Test
    void defaultConfigIsVanilla() throws IOException {
        Path f = dir.resolve("config.yml");
        try (InputStream in = SettingsTest.class.getResourceAsStream("/config.yml")) {
            Files.copy(in, f);
        }
        Settings s = Settings.from(new ModuleConfig(f));
        assertEquals(2000, s.pickupDelayMs());
        assertEquals(300, s.despawnSeconds());
        assertTrue(s.merge());
        assertEquals(1.0f, s.mergeRange());
        assertEquals(0.3, s.throwSpeed(), 1e-12);
        assertTrue(s.spread());
        assertEquals(java.util.Set.of("galaxy1"), s.noDespawnMaps());
    }

    @Test
    void emptyAndNegativeValues() throws IOException {
        Path f = dir.resolve("c.yml");
        Files.writeString(f, "despawn-seconds: -5\npickup-delay-seconds: 0.25\nthrow-speed: -1\n");
        Settings s = Settings.from(new ModuleConfig(f));
        assertEquals(0, s.despawnSeconds());
        assertEquals(250, s.pickupDelayMs());
        assertEquals(0, s.throwSpeed());
        Settings d = Settings.from(new ModuleConfig(dir.resolve("missing.yml")));
        assertEquals(300, d.despawnSeconds());
        assertTrue(d.noDespawnMaps().isEmpty());
    }
}
