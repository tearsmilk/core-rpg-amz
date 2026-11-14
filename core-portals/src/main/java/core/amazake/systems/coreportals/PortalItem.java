package core.amazake.systems.coreportals;

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

/** Предмет «Портал (смола)»: RESIN_CLUMP с меткой в PDC. Узнаётся по метке, а не по имени. */
public final class PortalItem {

    /** Метка предмета (постоянное пространство имён: предметы из прошлых запусков тоже узнаются). */
    public static final NamespacedKey KEY = new NamespacedKey("coreportals", "portal_item");

    private PortalItem() {
    }

    public static ItemStack create(final Settings s, final int amount) {
        final ItemStack stack = new ItemStack(Material.RESIN_CLUMP, Math.max(1, Math.min(amount, 64)));
        final ItemMeta meta = stack.getItemMeta();
        meta.displayName(Text.parse(s.itemName()));
        final List<Component> lore = new ArrayList<>();
        for (final String line : s.itemLore()) {
            lore.add(Text.parse(line));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        if (!s.itemModel().isEmpty()) {
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(s.itemModel()));
        }
        return stack;
    }

    public static boolean is(final ItemStack stack) {
        if (stack == null || stack.getType() != Material.RESIN_CLUMP || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }
}
