package core.amazake.systems.corerpg.seals;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SealSettingsTest {

    private static YamlConfiguration yaml(final String text) throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    @Test
    void emptyConfigGivesDefaultsWithoutWarnings() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final SealSettings s = SealSettings.from(yaml(""), warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertArrayEquals(new int[] {60, 27, 10, 3}, s.weights());
        assertEquals(100.0, s.arkoniteRerollChance());
        assertTrue(s.classFilter());
        assertEquals("Мастер зачарования", s.npcName());
        assertEquals(Consumable.values().length, s.looks().size());
        assertEquals(Material.AMETHYST_SHARD, s.look(Consumable.HEART).material());
        assertTrue(s.look(Consumable.STONE_C).name().contains("Изумрудный камень (C)"));
    }

    @Test
    void bundledFileParsesCleanly() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final YamlConfiguration y = yaml(Files.readString(Path.of("src/main/resources/seals.yml")));
        final SealSettings s = SealSettings.from(y, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertArrayEquals(new int[] {60, 27, 10, 3}, s.weights());
        assertEquals(100.0, s.arkoniteRerollChance());
        assertTrue(s.classFilter());
        assertEquals("Мастер зачарования", s.npcName());
        assertEquals("<light_purple>Сургуч Черепа Ахи</light_purple>", s.look(Consumable.WAX).name());
    }

    @Test
    void validValuesAreRead() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final SealSettings s = SealSettings.from(yaml("""
            settings:
              roll-weights: {green: 1, blue: 2, purple: 3, orange: 4}
              arkonite-reroll-chance: 37.5
              class-filter: false
              npc-name: "  Кузнец  "
              menu-title: "<red>Меню"
              consumables:
                heart: {material: heart_of_the_sea, name: "<aqua>Сердце"}
            """), warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertArrayEquals(new int[] {1, 2, 3, 4}, s.weights());
        assertEquals(37.5, s.arkoniteRerollChance());
        assertFalse(s.classFilter());
        assertEquals("Кузнец", s.npcName());
        assertEquals("<red>Меню", s.menuTitle());
        assertEquals(Material.HEART_OF_THE_SEA, s.look(Consumable.HEART).material());
        assertEquals("<aqua>Сердце", s.look(Consumable.HEART).name());
        assertEquals(Material.AMETHYST_SHARD, s.look(Consumable.WAX).material());
    }

    @Test
    void badValuesWarnAndFallBack() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final SealSettings s = SealSettings.from(yaml("""
            settings:
              roll-weights: {green: -5, blue: "много", purple: 2.5, orange: 4}
              arkonite-reroll-chance: 150
              class-filter: "да"
              consumables:
                stone_a: {material: NOT_A_MATERIAL, name: ""}
                stone_b: {material: AIR}
                stone_c: {material: LEGACY_STONE}
            """), warnings::add);
        assertArrayEquals(new int[] {60, 27, 10, 4}, s.weights());
        assertEquals(100.0, s.arkoniteRerollChance());
        assertTrue(s.classFilter());
        assertEquals(Material.AMETHYST_SHARD, s.look(Consumable.STONE_A).material());
        assertEquals(Material.AMETHYST_SHARD, s.look(Consumable.STONE_B).material());
        assertEquals(Material.AMETHYST_SHARD, s.look(Consumable.STONE_C).material());
        assertTrue(s.look(Consumable.STONE_A).name().contains("Морионовый"));
        assertEquals(9, warnings.size(), warnings.toString());
    }

    @Test
    void allZeroWeightsFallBackToDefaults() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final SealSettings s = SealSettings.from(yaml("""
            settings:
              roll-weights: {green: 0, blue: 0, purple: 0, orange: 0}
            """), warnings::add);
        assertArrayEquals(new int[] {60, 27, 10, 3}, s.weights());
        assertEquals(1, warnings.size());
    }

    @Test
    void nonItemMaterialsAreReplaced() throws Exception {
        final SealSettings s = SealSettings.from(yaml("""
            settings:
              consumables:
                wax: {material: EMERALD}
                heart: {material: WATER}
            """), w -> { });
        final List<String> warnings = new ArrayList<>();
        final SealSettings fixed = s.withItemMaterials(m -> m != Material.WATER, warnings::add);
        assertEquals(Material.EMERALD, fixed.look(Consumable.WAX).material());
        assertEquals(Material.AMETHYST_SHARD, fixed.look(Consumable.HEART).material());
        assertEquals(1, warnings.size());
        assertSame(fixed, fixed.withItemMaterials(m -> true, warnings::add));
    }
}
