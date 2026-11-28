package ink.hoka.odyssey.oxygen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.Equippable;
import net.minestom.server.tag.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SuitTest {
    @BeforeAll
    static void init() {
        MinecraftServer.init();
    }

    @Test
    void suitIsEquippableGlassWithTag() {
        ItemStack suit = Suit.create(LungsTest.VANILLA);
        assertEquals(Material.GLASS, suit.material());
        assertTrue(Suit.is(suit));
        Equippable eq = suit.get(DataComponents.EQUIPPABLE);
        assertNotNull(eq);
        assertEquals(EquipmentSlot.HELMET, eq.slot());
        assertTrue(eq.swappable());                                // ПКМ из руки меняет местами со шлемом
        assertEquals(1, suit.get(DataComponents.MAX_STACK_SIZE));
        assertEquals("Скафандр", PlainTextComponentSerializer.plainText().serialize(suit.get(DataComponents.CUSTOM_NAME)));
    }

    @Test
    void plainGlassIsNotSuit() {
        assertFalse(Suit.is(ItemStack.of(Material.GLASS)));
        assertFalse(Suit.is(ItemStack.AIR));
        assertFalse(Suit.is(null));
        // стекло с тем же названием, но без метки, — не скафандр
        assertFalse(Suit.is(Suit.create(LungsTest.VANILLA).withTag(Suit.TAG, null)));
        // метка на другом предмете — тоже нет
        assertFalse(Suit.is(ItemStack.of(Material.DIAMOND_HELMET).withTag(Suit.TAG, true)));
        // чужая метка — нет
        assertFalse(Suit.is(ItemStack.of(Material.GLASS).withTag(Tag.Boolean("other"), true)));
    }

    @Test
    void stillSuitWhenRenamed() {
        ItemStack renamed = Suit.create(LungsTest.VANILLA).with(DataComponents.CUSTOM_NAME, Suit.text("Шлем"));
        assertTrue(Suit.is(renamed));
    }
}
