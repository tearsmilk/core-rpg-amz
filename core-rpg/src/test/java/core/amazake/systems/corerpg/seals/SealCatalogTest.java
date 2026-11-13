package core.amazake.systems.corerpg.seals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SealCatalogTest {

    private static SealCatalog bundled;
    private static List<String> bundledWarnings;

    @BeforeAll
    static void loadBundled() throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(Files.readString(Path.of("src/main/resources/seals.yml")));
        bundledWarnings = new ArrayList<>();
        bundled = SealCatalog.from(y, bundledWarnings::add);
    }

    private static SealCatalog parse(final String text, final List<String> warnings) throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return SealCatalog.from(y, warnings::add);
    }

    @Test
    void bundledCatalogHas144Seals() {
        assertEquals(144, bundled.size());
        assertTrue(bundledWarnings.isEmpty(), bundledWarnings.toString());
        final long plugin = bundled.all().stream().filter(s -> "plugin".equals(s.origin())).count();
        assertEquals(57, plugin);
        assertTrue(bundled.all().stream().anyMatch(s -> !s.enabled()));
    }

    @Test
    void bundledValuesAndTildes() {
        final SealDefinition aggression = bundled.get("aggression");
        assertNotNull(aggression);
        assertEquals("Агрессии", aggression.name());
        assertEquals(1.0, aggression.value("attack_damage", SealColor.GREEN));
        assertEquals(3.4, aggression.value("attack_damage", SealColor.ORANGE));
        assertTrue(Double.isNaN(aggression.value("armor", SealColor.GREEN)));
        // Где-то в каталоге у зелёного цвета бонуса нет (~).
        assertTrue(bundled.all().stream().anyMatch(s -> s.bonuses().values().stream()
            .anyMatch(v -> !v.has(SealColor.GREEN) && v.has(SealColor.ORANGE))));
    }

    @Test
    void accessoryIsDroppedSilently() {
        final long onlyAccessory = bundled.all().stream().filter(s -> s.types().isEmpty()).count();
        assertTrue(onlyAccessory > 0);
        for (final SealDefinition seal : bundled.all()) {
            assertTrue(SealCatalog.TYPES.containsAll(seal.types()), seal.id());
        }
        assertFalse(bundled.classKeys().contains("any"));
        assertTrue(bundled.classKeys().containsAll(Set.of("swordsman", "archer", "mage", "thief", "warlock")));
    }

    @Test
    void badEntriesWarnAndAreSkipped() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final SealCatalog c = parse("""
            seals:
              Good:
                name: "Хорошая"
                types: [weapon, BELT]
                classes: [Mage]
                bonuses:
                  attack_damage: [1, 2, 3, 4]
                  unknown_key: [1, 2, 3, 4]
                  armor: [1, 2, 3]
                  max_health: [1, "x", 3, 4]
                  "resistance:fire": [0.01, "0,02", ~, 0.04]
              broken: 5
            """, warnings);
        assertEquals(1, c.size());
        final SealDefinition good = c.get("good");
        assertEquals(Set.of("WEAPON"), good.types());
        assertEquals(Set.of("mage"), good.classes());
        assertEquals(Set.of("attack_damage", "resistance:fire"), good.bonuses().keySet());
        assertEquals(0.02, good.value("resistance:fire", SealColor.BLUE));
        assertTrue(Double.isNaN(good.value("resistance:fire", SealColor.PURPLE)));
        assertEquals(5, warnings.size(), warnings.toString());
    }

    @Test
    void missingSectionGivesEmptyCatalog() throws Exception {
        final List<String> warnings = new ArrayList<>();
        assertEquals(0, parse("settings: {}", warnings).size());
        assertEquals(1, warnings.size());
    }

    private static SealDefinition seal(final String id, final Set<String> types, final Set<String> classes, final boolean enabled) {
        return new SealDefinition(id, id, "plugin", types, classes, enabled,
            Map.of("attack_damage", new SealDefinition.Values(1, 2, 3, 4)));
    }

    @Test
    void poolFiltersByTypeEnabledAndClass() {
        final SealCatalog c = SealCatalog.of(List.of(
            seal("any_weapon", Set.of("WEAPON"), Set.of("any"), true),
            seal("mage_weapon", Set.of("WEAPON"), Set.of("mage"), true),
            seal("wizard_weapon", Set.of("WEAPON"), Set.of("wizard"), true),
            seal("off_weapon", Set.of("WEAPON"), Set.of("any"), false),
            seal("armor", Set.of("ARMOR"), Set.of(), true)));
        assertEquals(List.of("any_weapon", "mage_weapon", "wizard_weapon"),
            c.pool("WEAPON", null, null, false).stream().map(SealDefinition::id).toList());
        assertEquals(List.of("any_weapon", "mage_weapon"),
            c.pool("WEAPON", "mage", null, true).stream().map(SealDefinition::id).toList());
        assertEquals(List.of("any_weapon", "mage_weapon", "wizard_weapon"),
            c.pool("WEAPON", "mage", "wizard", true).stream().map(SealDefinition::id).toList());
        assertEquals(List.of("any_weapon"),
            c.pool("WEAPON", null, null, true).stream().map(SealDefinition::id).toList());
        assertEquals(List.of("armor"), c.pool("ARMOR", "archer", null, true).stream().map(SealDefinition::id).toList());
        assertTrue(c.pool("SHIELD", null, null, true).isEmpty());
        assertNull(c.get("nope"));
    }

    @Test
    void poolFallsBackToAllOfTypeWhenClassMatchesNothing() {
        final SealCatalog c = SealCatalog.of(List.of(
            seal("mage_only", Set.of("SHIELD"), Set.of("mage"), true),
            seal("archer_only", Set.of("SHIELD"), Set.of("archer"), true)));
        assertEquals(2, c.pool("SHIELD", "thief", null, true).size());
    }

    @Test
    void bundledPoolsAreNotEmpty() {
        for (final String type : SealCatalog.TYPES) {
            for (final String cls : List.of("swordsman", "archer", "mage", "thief")) {
                assertFalse(bundled.pool(type, cls, null, true).isEmpty(), type + "/" + cls);
            }
        }
    }

    @Test
    void scalarListsAndBadEnabledWarn() throws Exception {
        final List<String> w = new ArrayList<>();
        final SealCatalog c = parse("""
            seals:
              one:
                name: Один
                types: WEAPON
                classes: swordsman
                enabled: nope
                bonuses:
                  armor: [1, 2, 3, 4]
            """, w);
        final SealDefinition one = c.get("one");
        assertNotNull(one);
        assertEquals(Set.of("WEAPON"), one.types());
        assertEquals(Set.of("swordsman"), one.classes());
        assertFalse(one.forAnyClass());
        assertTrue(w.stream().anyMatch(x -> x.contains(".types")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains(".classes")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains(".enabled")), w.toString());
    }

    @Test
    void sealWithoutTypesWarnsUnlessOnlyAccessory() throws Exception {
        final List<String> w = new ArrayList<>();
        parse("""
            seals:
              none:
                name: Без
                bonuses:
                  armor: [1, 2, 3, 4]
              acc:
                name: Акс
                types: [ACCESSORY]
                bonuses:
                  armor: [1, 2, 3, 4]
            """, w);
        assertTrue(w.stream().anyMatch(x -> x.startsWith("seals.none.types")), w.toString());
        assertFalse(w.stream().anyMatch(x -> x.startsWith("seals.acc")), w.toString());
    }
}
