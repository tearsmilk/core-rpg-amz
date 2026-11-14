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

/**
 * Предметы-порталы: «Портал» (RESIN_CLUMP) и «Изумрудный портал» (EMERALD), у каждого своя метка в PDC.
 * Узнаются по метке и материалу, а не по имени.
 */
public final class PortalItem {

    /** Метка «Портала» из смолы (постоянное пространство имён: предметы из прошлых запусков тоже узнаются). */
    public static final NamespacedKey KEY = new NamespacedKey("coreportals", "portal_item");
    /** Метка «Изумрудного портала». */
    public static final NamespacedKey KEY_EMERALD = new NamespacedKey("coreportals", "emerald_portal_item");

    private PortalItem() {
    }

    /** «Портал» из смолы (как раньше). */
    public static ItemStack create(final Settings s, final int amount) {
        return create(s, PortalType.RESIN, amount);
    }

    public static ItemStack create(final Settings s, final PortalType type, final int amount) {
        final boolean emerald = type == PortalType.EMERALD;
        final ItemStack stack = new ItemStack(emerald ? Material.EMERALD : Material.RESIN_CLUMP,
            Math.max(1, Math.min(amount, 64)));
        final ItemMeta meta = stack.getItemMeta();
        meta.displayName(Text.parse(emerald ? s.emerald().itemName() : s.itemName()));
        final List<Component> lore = new ArrayList<>();
        for (final String line : emerald ? s.emerald().itemLore() : s.itemLore()) {
            lore.add(Text.parse(line));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(emerald ? KEY_EMERALD : KEY, PersistentDataType.BYTE, (byte) 1);
        if (emerald) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
        final String model = emerald ? s.emerald().itemModel() : s.itemModel();
        if (!model.isEmpty()) {
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(model));
        }
        return stack;
    }

    /** Вид портала, который открывает предмет, или {@code null} — это не предмет-портал. */
    public static PortalType type(final ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        final Material m = stack.getType();
        if (m != Material.RESIN_CLUMP && m != Material.EMERALD) {
            return null;
        }
        final var pdc = stack.getItemMeta().getPersistentDataContainer();
        if (m == Material.RESIN_CLUMP && pdc.has(KEY, PersistentDataType.BYTE)) {
            return PortalType.RESIN;
        }
        if (m == Material.EMERALD && pdc.has(KEY_EMERALD, PersistentDataType.BYTE)) {
            return PortalType.EMERALD;
        }
        return null;
    }

    /** Любой предмет-портал. */
    public static boolean is(final ItemStack stack) {
        return type(stack) != null;
    }
}
