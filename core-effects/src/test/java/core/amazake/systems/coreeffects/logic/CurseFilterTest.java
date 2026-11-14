package core.amazake.systems.coreeffects.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CurseFilterTest {

    private static final List<String> NBT = List.of("MMOITEMS_ITEM_ID", "nexo:id");

    private static ItemFacts plain(final String material) {
        return new ItemFacts(material, 0, false, false, false, false, false, false, false, "");
    }

    private static boolean ok(final ItemFacts f) {
        return CurseFilter.ordinary(f, Set.of("NETHER_STAR"), NBT, false, true);
    }

    @Test
    void ordinaryLootIsTaken() {
        for (final String m : List.of("DIRT", "COBBLESTONE", "ROTTEN_FLESH", "BONE", "IRON_INGOT", "DIAMOND", "BREAD",
            "ARROW", "TORCH", "OAK_LOG", "STRING", "GOLDEN_APPLE", "ENDER_PEARL", "POTION")) {
            assertTrue(ok(plain(m)), m);
        }
    }

    @Test
    void equipmentNeverTaken() {
        for (final String m : List.of("DIAMOND_SWORD", "NETHERITE_AXE", "IRON_PICKAXE", "WOODEN_SHOVEL", "STONE_HOE",
            "DIAMOND_HELMET", "IRON_CHESTPLATE", "GOLDEN_LEGGINGS", "LEATHER_BOOTS", "IRON_SPEAR", "BOW", "CROSSBOW",
            "TRIDENT", "MACE", "SHIELD", "ELYTRA", "SHEARS", "FISHING_ROD", "FLINT_AND_STEEL", "TOTEM_OF_UNDYING",
            "TURTLE_HELMET", "DIAMOND_HORSE_ARMOR", "WOLF_ARMOR", "SHULKER_BOX", "RED_SHULKER_BOX", "BUNDLE",
            "RED_BUNDLE", "COMPASS", "SPYGLASS")) {
            assertFalse(ok(plain(m)), m);
        }
    }

    @Test
    void componentsProtect() {
        // своя прочность (кастомное оружие на любом материале)
        assertFalse(ok(new ItemFacts("STICK", 250, false, false, false, false, false, false, false, "")));
        // надевается (тыква, кастомная броня)
        assertFalse(ok(new ItemFacts("CARVED_PUMPKIN", 0, true, false, false, false, false, false, false, "")));
        // модификаторы атрибутов
        assertFalse(ok(new ItemFacts("PAPER", 0, false, true, false, false, false, false, false, "")));
        // death_protection — аналог тотема
        assertFalse(ok(new ItemFacts("APPLE", 0, false, false, true, false, false, false, false, "")));
        // в тегах инструментов/оружия
        assertFalse(ok(new ItemFacts("SOMETHING", 0, false, false, false, true, false, false, false, "")));
        // контейнер
        assertFalse(ok(new ItemFacts("SOMETHING", 0, false, false, false, false, true, false, false, "")));
        // своя модель
        assertFalse(ok(new ItemFacts("PAPER", 0, false, false, false, false, false, false, true, "")));
    }

    @Test
    void pluginItemsAndConfigProtect() {
        assertFalse(ok(new ItemFacts("PAPER", 0, false, false, false, false, false, false, false,
            "[minecraft:custom_data={MMOITEMS_ITEM_ID:\"RING\"}]")));
        assertFalse(ok(new ItemFacts("PAPER", 0, false, false, false, false, false, false, false,
            "{PublicBukkitValues:{\"nexo:id\":\"ruby\"}}")));
        assertFalse(ok(plain("NETHER_STAR")));
        assertFalse(ok(plain("AIR")));
        assertFalse(ok(plain("")));
        assertFalse(ok(null));
        final ItemFacts named = new ItemFacts("DIRT", 0, false, false, false, false, false, true, false, "");
        assertTrue(CurseFilter.ordinary(named, Set.of(), NBT, false, true));
        assertFalse(CurseFilter.ordinary(named, Set.of(), NBT, true, true));
    }

    @Test
    void takeAmount() {
        assertEquals(2, CurseFilter.take(64, 2, false));
        assertEquals(3, CurseFilter.take(3, 5, false));
        assertEquals(1, CurseFilter.take(5, 0, false));
        assertEquals(64, CurseFilter.take(64, 2, true));
        assertEquals(0, CurseFilter.take(0, 2, false));
    }
}
