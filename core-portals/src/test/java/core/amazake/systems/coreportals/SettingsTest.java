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

    @Test
    void emeraldInheritsSharedValues() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final Settings s = parse("""
            portal:
              lifetime-seconds: 12
              distance: 2
            teleport:
              commands: ["warp dungeon7"]
            """, warnings);
        assertEquals(List.of(), warnings);
        assertEquals(240, s.lifetimeFor(PortalType.EMERALD));
        assertEquals(2, s.distanceFor(PortalType.EMERALD));
        assertEquals(List.of("warp dungeon7"), s.teleportFor(PortalType.EMERALD).commands());
        assertEquals(s.teleport(), s.teleportFor(PortalType.EMERALD));
        assertEquals(Settings.Emerald.DEFAULTS.itemName(), s.emerald().itemName());
        assertTrue(s.emerald().itemName().contains("Изумрудный портал"));
    }

    @Test
    void emeraldOwnSection() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final Settings s = parse("""
            emerald:
              item:
                name: "<green>Изумруд"
                lore: ["a"]
                model: "coreportals:emerald_item"
              portal:
                lifetime-seconds: 20
                distance: 4
              teleport:
                commands: ["warp dungeon2"]
            """, warnings);
        assertEquals(List.of(), warnings);
        assertEquals(400, s.lifetimeFor(PortalType.EMERALD));
        assertEquals(600, s.lifetimeFor(PortalType.RESIN));
        assertEquals(4, s.distanceFor(PortalType.EMERALD));
        assertEquals(3, s.distanceFor(PortalType.RESIN));
        assertEquals("coreportals:emerald_item", s.emerald().itemModel());
        assertEquals(List.of("a"), s.emerald().itemLore());
        // Своё действие: команды свои, остальное (от игрока, временное право) — из общего.
        final TeleportAction a = s.teleportFor(PortalType.EMERALD);
        assertEquals(List.of("warp dungeon2"), a.commands());
        assertEquals(TeleportAction.RunAs.PLAYER, a.runAs());
        assertEquals(List.of("amazakecore.essentials.warp"), a.grantPermissions());
        assertEquals(List.of("warp dungeon1"), s.teleportFor(PortalType.RESIN).commands());
    }

    @Test
    void emeraldBadValuesWarn() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final Settings s = parse("""
            emerald:
              item:
                model: "Bad Key"
              portal:
                lifetime-seconds: 1
                distance: 9
              teleport:
                run-as: console
                commands: ["warp x"]
            """, warnings);
        assertEquals("", s.emerald().itemModel());
        assertEquals(600, s.emerald().lifetimeTicks());
        assertEquals(3, s.emerald().distance());
        assertEquals(4, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("emerald.teleport")), warnings.toString());
    }

    @Test
    void portalTypeParse() {
        assertEquals(PortalType.RESIN, PortalType.parse("resin").orElseThrow());
        assertEquals(PortalType.EMERALD, PortalType.parse(" Emerald ").orElseThrow());
        assertTrue(PortalType.parse("lava").isEmpty());
        assertTrue(PortalType.parse(null).isEmpty());
    }

    @Test
    void portalTypeParseLapis() {
        assertEquals(PortalType.LAPIS, PortalType.parse("lapis").orElseThrow());
        assertEquals(PortalType.LAPIS, PortalType.parse("Лазурит").orElseThrow());
        assertEquals(PortalType.LAPIS, PortalType.parse("rift").orElseThrow());
    }

    @Test
    void lapisInheritsSharedValues() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final Settings s = parse("""
            portal:
              lifetime-seconds: 40
              distance: 4
            teleport:
              commands: ["warp dungeon2"]
            """, warnings);
        assertEquals(List.of(), warnings);
        assertEquals(800, s.lifetimeFor(PortalType.LAPIS));
        assertEquals(4, s.distanceFor(PortalType.LAPIS));
        assertEquals(List.of("warp dungeon2"), s.teleportFor(PortalType.LAPIS).commands());
        assertEquals(5.0, s.lapis().size().width());
        assertEquals(7.0, s.lapis().size().height());
        assertEquals(3.6, s.lapis().size().centerUp());
        assertEquals(10.0, s.lapis().radius());
        assertEquals(0.05, s.lapis().pullStrength());
    }

    @Test
    void lapisOwnSection() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final Settings s = parse("""
            lapis:
              item:
                name: "Разлом"
                model: "coreportals:lapis_item"
              portal:
                lifetime-seconds: 20
                distance: 5
                width: 6
                height: 8
              screen:
                radius: 0
                cracks: false
              pull:
                strength: 0
              teleport:
                commands: ["warp dungeon3"]
            """, warnings);
        assertEquals(List.of(), warnings);
        assertEquals("Разлом", s.lapis().itemName());
        assertEquals("coreportals:lapis_item", s.lapis().itemModel());
        assertEquals(400, s.lifetimeFor(PortalType.LAPIS));
        assertEquals(600, s.lifetimeFor(PortalType.RESIN));
        assertEquals(5, s.distanceFor(PortalType.LAPIS));
        assertEquals(6.0, s.lapis().size().width());
        assertEquals(8.0, s.lapis().size().height());
        assertEquals(4.1, s.lapis().size().centerUp(), 1e-9);   // нет center-up: height / 2 + 0,1
        assertEquals(0.0, s.lapis().radius());
        assertFalse(s.lapis().screenCracks());
        assertTrue(s.lapis().screenGlow());
        assertEquals(0.0, s.lapis().pullStrength());
        assertEquals(List.of("warp dungeon3"), s.teleportFor(PortalType.LAPIS).commands());
        assertEquals(List.of("warp dungeon1"), s.teleportFor(PortalType.EMERALD).commands());
    }

    @Test
    void lapisBadValuesWarn() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final Settings s = parse("""
            lapis:
              portal:
                width: 40
                height: 7
                center-up: 1
                distance: 12
              screen:
                radius: -1
              pull:
                strength: 2
                max-speed: 0
            """, warnings);
        assertEquals(5.0, s.lapis().size().width());
        assertEquals(3.6, s.lapis().size().centerUp(), 1e-9);   // уходит в пол больше 0,5 — height / 2 + 0,1
        assertEquals(3, s.lapis().distance());
        assertEquals(10.0, s.lapis().radius());
        assertEquals(0.05, s.lapis().pullStrength());
        assertEquals(0.25, s.lapis().pullMaxSpeed());
        assertEquals(6, warnings.size(), warnings.toString());
    }
}
