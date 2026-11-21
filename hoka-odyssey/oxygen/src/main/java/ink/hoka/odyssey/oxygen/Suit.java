package ink.hoka.odyssey.oxygen;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.Equippable;
import net.minestom.server.tag.Tag;

/**
 * Скафандр: блок стекла с компонентом equippable (слот головы) — клиент даёт надеть его ПКМ и положить в слот
 * шлема, как ванильный шлем. Узнаётся по метке в custom_data: обычное стекло на голове скафандром не считается.
 */
final class Suit {
    static final Tag<Boolean> TAG = Tag.Boolean("hoka_oxygen_suit");
    static final Material MATERIAL = Material.GLASS;
    /** Слот головы; swappable — ПКМ из руки меняет местами с тем, что на голове (это делает сам Minestom). */
    static final Equippable EQUIPPABLE = new Equippable(EquipmentSlot.HELMET, Equippable.DEFAULT_EQUIP_SOUND,
        null, null, null, true, true, false, false, false, Equippable.DEFAULT_SHEARING_SOUND);

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Suit() {
    }

    static ItemStack create(Settings s) {
        List<Component> lore = s.suitLore().stream().map(Suit::text).toList();
        return ItemStack.builder(MATERIAL)
            .set(DataComponents.CUSTOM_NAME, text(s.suitName()))
            .set(DataComponents.LORE, lore)
            .set(DataComponents.EQUIPPABLE, EQUIPPABLE)
            .set(DataComponents.MAX_STACK_SIZE, 1)
            .build()
            .withTag(TAG, true);
    }

    /** Скафандр ли это (стекло с меткой модуля). */
    static boolean is(ItemStack item) {
        return item != null && item.material() == MATERIAL && Boolean.TRUE.equals(item.getTag(TAG));
    }

    /** Текст из config.yml с цветами через &, без курсива (Minecraft красит название и описание курсивом). */
    static Component text(String s) {
        return LEGACY.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
