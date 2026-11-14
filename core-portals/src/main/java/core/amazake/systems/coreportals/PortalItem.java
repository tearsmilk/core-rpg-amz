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
 * Предметы-порталы: «Портал» (RESIN_CLUMP), «Изумрудный портал» (EMERALD) и «Лазуритовый портал» (LAPIS_LAZULI),
 * у каждого своя метка в PDC.
 * Узнаются по метке и материалу, а не по имени.
 */
public final class PortalItem {

    /** Метка «Портала» из смолы (постоянное пространство имён: предметы из прошлых запусков тоже узнаются). */
    public static final NamespacedKey KEY = new NamespacedKey("coreportals", "portal_item");
    /** Метка «Изумрудного портала». */
    public static final NamespacedKey KEY_EMERALD = new NamespacedKey("coreportals", "emerald_portal_item");
    /** Метка «Лазуритового портала». */
    public static final NamespacedKey KEY_LAPIS = new NamespacedKey("coreportals", "lapis_portal_item");

    private PortalItem() {
    }

    /** «Портал» из смолы (как раньше). */
    public static ItemStack create(final Settings s, final int amount) {
        return create(s, PortalType.RESIN, amount);
    }

    public static ItemStack create(final Settings s, final PortalType type, final int amount) {
        final Material material;
        final String name;
        final List<String> loreLines;
        final String model;
        final NamespacedKey key;
        switch (type) {
            case EMERALD -> {
                material = Material.EMERALD;
                name = s.emerald().itemName();
                loreLines = s.emerald().itemLore();
                model = s.emerald().itemModel();
                key = KEY_EMERALD;
            }
            case LAPIS -> {
                material = Material.LAPIS_LAZULI;
                name = s.lapis().itemName();
                loreLines = s.lapis().itemLore();
                model = s.lapis().itemModel();
                key = KEY_LAPIS;
            }
            default -> {
                material = Material.RESIN_CLUMP;
                name = s.itemName();
                loreLines = s.itemLore();
                model = s.itemModel();
                key = KEY;
            }
        }
        final ItemStack stack = new ItemStack(material, Math.max(1, Math.min(amount, 64)));
        final ItemMeta meta = stack.getItemMeta();
        meta.displayName(Text.parse(name));
        final List<Component> lore = new ArrayList<>();
        for (final String line : loreLines) {
            lore.add(Text.parse(line));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        if (type != PortalType.RESIN) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
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
        if (m != Material.RESIN_CLUMP && m != Material.EMERALD && m != Material.LAPIS_LAZULI) {
            return null;
        }
        final var pdc = stack.getItemMeta().getPersistentDataContainer();
        if (m == Material.RESIN_CLUMP && pdc.has(KEY, PersistentDataType.BYTE)) {
            return PortalType.RESIN;
        }
        if (m == Material.EMERALD && pdc.has(KEY_EMERALD, PersistentDataType.BYTE)) {
            return PortalType.EMERALD;
        }
        if (m == Material.LAPIS_LAZULI && pdc.has(KEY_LAPIS, PersistentDataType.BYTE)) {
            return PortalType.LAPIS;
        }
        return null;
    }

    /** Любой предмет-портал. */
    public static boolean is(final ItemStack stack) {
        return type(stack) != null;
    }
}
