package core.amazake.systems.corerpg.seals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Подписи бонусов, разбор цветов, расходников и аргументов команды. */
class SealsTextTest {

    @Test
    void bonusValues() {
        assertEquals("+1.6", BonusFormat.value("attack_damage", 1.6));
        assertEquals("+4%", BonusFormat.value("resistance", 0.04));
        assertEquals("+7%", BonusFormat.value("resistance:fire", 0.07));
        assertEquals("+2.2%", BonusFormat.value("crit_chance", 2.2));
        assertEquals("+3%", BonusFormat.value("damage_vs:monster", 3));
        assertEquals("+0.004", BonusFormat.value("movement_speed", 0.004));
        assertEquals("+2 HP", BonusFormat.value("max_health", 2));
        assertEquals("+0.56 бл.", BonusFormat.value("range", 0.56));
        assertEquals("-2", BonusFormat.value("armor", -2));
        assertEquals("0", BonusFormat.number(0.0001));
    }

    @Test
    void bonusLabels() {
        assertEquals("Урон", BonusFormat.label("attack_damage"));
        assertEquals("Сопротивление (огонь)", BonusFormat.label("resistance:fire"));
        assertEquals("Урон против (монстры)", BonusFormat.label("damage_vs:monster"));
        assertEquals("• Мана / энергия: +5 (пока не действует)", BonusFormat.plainLine("resource", 5));
        assertEquals("• Урон: +1", BonusFormat.plainLine("attack_damage", 1));
        assertTrue(SealCatalog.isKnownBonus("resistance:chaos"));
        assertTrue(!SealCatalog.isKnownBonus("resistance:"));
        assertTrue(!SealCatalog.isKnownBonus("mana"));
    }

    @Test
    void colorParsing() {
        assertEquals(SealColor.GREEN, SealColor.parse("green"));
        assertEquals(SealColor.GREEN, SealColor.parse("Зелёная"));
        assertEquals(SealColor.GREEN, SealColor.parse("зеленая"));
        assertEquals(SealColor.BLUE, SealColor.parse("синяя"));
        assertEquals(SealColor.PURPLE, SealColor.parse("ФИОЛЕТОВАЯ"));
        assertEquals(SealColor.ORANGE, SealColor.parse("orange"));
        assertNull(SealColor.parse("red"));
        assertEquals(SealColor.PURPLE, SealColor.byId("purple"));
        assertNull(SealColor.byId(null));
    }

    @Test
    void consumableParsing() {
        assertEquals(List.of(Consumable.STONE_C), Consumable.resolve("изумруд"));
        assertEquals(List.of(Consumable.STONE_A), Consumable.resolve("stone_a"));
        assertEquals(List.of(Consumable.WAX), Consumable.resolve("сургуч"));
        assertEquals(List.of(Consumable.ACCOUNT_WAX), Consumable.resolve("особый"));
        assertEquals(List.of(Consumable.ARKONITE_WAX), Consumable.resolve("арконит"));
        assertEquals(List.of(Consumable.HEART), Consumable.resolve("сердце"));
        assertEquals(List.of(Consumable.BLOOD_CRYSTAL), Consumable.resolve("кристалл"));
        assertEquals(List.of(Consumable.STONE_B), Consumable.resolve("сапфир"));
        assertEquals(List.of(Consumable.STONE_A), Consumable.resolve("морион"));
        assertEquals(8, Consumable.resolve("all").size());
        assertEquals(List.of(Consumable.STONE_C, Consumable.STONE_B, Consumable.STONE_A), Consumable.resolve("stones"));
        assertEquals(List.of(Consumable.WAX, Consumable.ACCOUNT_WAX, Consumable.ARKONITE_WAX), Consumable.resolve("wax"));
        assertEquals(3, Consumable.resolve("камни").size());
        assertTrue(Consumable.resolve("камень").isEmpty());
        assertEquals(Consumable.HEART, Consumable.byId("HEART"));
        assertTrue(Consumable.completions().containsAll(List.of("all", "stones", "wax", "heart", "сердце")));
    }

    @Test
    void menuSlotRules() {
        assertTrue(SealMenu.allowed(SealMenu.SLOT_STONE, Consumable.STONE_B));
        assertTrue(!SealMenu.allowed(SealMenu.SLOT_STONE, Consumable.HEART));
        assertTrue(SealMenu.allowed(SealMenu.SLOT_WAX, Consumable.ARKONITE_WAX));
        assertTrue(!SealMenu.allowed(SealMenu.SLOT_CRYSTAL, null));
        assertEquals(SealMenu.SLOT_HEART, SealMenu.slotFor(Consumable.HEART));
        assertEquals(SealMenu.SLOT_WAX, SealMenu.slotFor(Consumable.ACCOUNT_WAX));
        assertEquals(SealMenu.SLOT_CRYSTAL, SealMenu.slotFor(Consumable.BLOOD_CRYSTAL));
    }

    @Test
    void listArguments() {
        final Set<String> classes = Set.of("mage", "archer");
        final SealsCommand.ListQuery q = SealsCommand.parseList(List.of("броня", "mage", "3"), classes);
        assertEquals("ARMOR", q.type());
        assertEquals("mage", q.classKey());
        assertEquals(3, q.page());
        assertNull(q.error());
        assertEquals("SHIELD", SealsCommand.parseList(List.of("Щит"), classes).type());
        assertEquals(1, SealsCommand.parseList(List.of("0"), classes).page());
        assertEquals("ракета", SealsCommand.parseList(List.of("ракета"), classes).error());
        assertEquals(1, SealsCommand.pages(0));
        assertEquals(1, SealsCommand.pages(10));
        assertEquals(15, SealsCommand.pages(144));
        assertEquals(64, SealsCommand.parseAmount("64"));
        assertNull(SealsCommand.parseAmount("65"));
        assertNull(SealsCommand.parseAmount("x"));
        assertEquals(List.of("menu"), SealsCommand.partial(List.of("menu", "list"), "ME"));
    }
}
