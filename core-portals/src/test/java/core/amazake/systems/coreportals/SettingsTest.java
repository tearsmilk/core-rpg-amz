package core.amazake.systems.coreportals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsTest {

    private static Settings parse(final String yaml, final List<String> warnings) throws InvalidConfigurationException {
        final YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return Settings.from(c, warnings::add);
    }

    @Test
    void bundledConfigMatchesDefaults() throws Exception {
        final YamlConfiguration c = new YamlConfiguration();
        try (var in = SettingsTest.class.getResourceAsStream("/config.yml")) {
            c.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        final List<String> warnings = new ArrayList<>();
        final Settings s = Settings.from(c, warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(Settings.DEFAULTS, s);
    }

    @Test
    void emptyConfigGivesDefaults() throws Exception {
        final List<String> warnings = new ArrayList<>();
        assertEquals(Settings.DEFAULTS, parse("", warnings));
        assertEquals(List.of(), warnings);
        assertEquals(TeleportAction.RunAs.PLAYER, Settings.DEFAULTS.teleport().runAs());
        assertEquals(List.of("warp dungeon1"), Settings.DEFAULTS.teleport().commands());
        assertEquals(600, Settings.DEFAULTS.lifetimeTicks());
        assertTrue(Settings.DEFAULTS.consume());
    }

    @Test
    void parsesValues() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final Settings s = parse("""
            item:
              consume: false
              cooldown-seconds: 1.5
              model: "coreportals:portal_item"
            portal:
              lifetime-seconds: 12
              distance: 2
              entry: owner
              immunity-seconds: 5
            teleport:
              run-as: console
              commands: ["/spawn {player}", "say {player} ушёл"]
              grant-permissions: []
            effects:
              particles: false
            """, warnings);
        assertEquals(List.of(), warnings);
        assertFalse(s.consume());
        assertEquals(30, s.cooldownTicks());
        assertEquals("coreportals:portal_item", s.itemModel());
        assertEquals(240, s.lifetimeTicks());
        assertEquals(2, s.distance());
        assertTrue(s.ownerOnly());
        assertEquals(100, s.immunityTicks());
        assertEquals(TeleportAction.RunAs.CONSOLE, s.teleport().runAs());
        assertEquals(List.of("spawn Steve", "say Steve ушёл"), s.teleport().resolve("Steve", "u"));
        assertFalse(s.particles());
        assertTrue(s.sounds());
    }

    @Test
    void badValuesWarnAndFallBack() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final Settings s = parse("""
            item:
              model: "Not A Key"
              cooldown-seconds: -4
            portal:
              lifetime-seconds: 2
              distance: 40
              entry: friends
              view-range: 0
            teleport:
              run-as: robot
              commands: ["warp dungeon1"]
            """, warnings);
        assertEquals("", s.itemModel());
        assertEquals(Settings.DEFAULTS.cooldownTicks(), s.cooldownTicks());
        assertEquals(Settings.DEFAULTS.lifetimeTicks(), s.lifetimeTicks());
        assertEquals(Settings.DEFAULTS.distance(), s.distance());
        assertFalse(s.ownerOnly());
        assertEquals(Settings.DEFAULTS.viewRange(), s.viewRange());
        assertEquals(TeleportAction.RunAs.PLAYER, s.teleport().runAs());
        assertEquals(7, warnings.size(), warnings.toString());
    }

    @Test
    void warnsAboutConsoleWarp() throws Exception {
        final List<String> warnings = new ArrayList<>();
        parse("teleport:\n  run-as: console\n  commands: [\"warp dungeon1\"]\n", warnings);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("от имени игрока"));
    }

    @Test
    void resolveStripsSlashesAndBlanks() {
        final TeleportAction a = new TeleportAction(TeleportAction.RunAs.PLAYER,
            List.of("//warp dungeon1", "  ", "tell {player} id {uuid}"), List.of());
        assertEquals(List.of("warp dungeon1", "tell Alex id 42"), a.resolve("Alex", "42"));
    }
}
