package core.amazake.systems.coreskyrift;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** «Разлом небес»: NETHERITE_INGOT с меткой {@code coreskyrift:rift_item} в PDC (узнаётся по метке, не по имени). */
public final class RiftItem {

    /** Метка предмета (постоянное пространство имён: предметы прошлых запусков тоже узнаются). */
    public static final NamespacedKey KEY = new NamespacedKey("coreskyrift", "rift_item");

    private RiftItem() {
    }

    public static ItemStack create(final Settings s, final int amount) {
        final ItemStack stack = new ItemStack(Material.NETHERITE_INGOT, Math.max(1, Math.min(amount, 64)));
        final ItemMeta meta = stack.getItemMeta();
        meta.displayName(Text.parse(s.item().name()));
        final List<Component> lore = new ArrayList<>();
        for (final String line : s.item().lore()) {
            lore.add(Text.parse(line));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta);
        if (!s.item().model().isEmpty()) {
            try {
                stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(s.item().model()));
            } catch (final RuntimeException ignored) {
                // неверный ключ модели — без своей иконки
            }
        }
        return stack;
    }

    public static boolean is(final ItemStack stack) {
        return stack != null && stack.getType() == Material.NETHERITE_INGOT && stack.hasItemMeta()
            && stack.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }
}
