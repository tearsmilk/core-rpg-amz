package core.amazake.systems.coreeffects.item;

import core.amazake.systems.coreeffects.Settings;
import core.amazake.systems.coreeffects.Text;
import core.amazake.systems.coreeffects.logic.ItemFacts;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/** Бинт, «чем лечится» у предмета, сведения о предмете для проклятья. */
public final class Items {

    private Items() {
    }

    // -------------------------------------------------------------------------------------------------- бинт

    public static ItemStack bandage(final Settings s, final int amount) {
        Material m = Material.matchMaterial(s.bandage().material());
        if (m == null || !m.isItem() || m.isAir()) {
            m = Material.PAPER;
        }
        final ItemStack item = new ItemStack(m, Math.max(1, Math.min(m.getMaxStackSize(), amount)));
        item.editMeta(meta -> {
            meta.itemName(Text.item(s.bandage().name()));
            final List<Component> lore = new ArrayList<>();
            for (final String l : s.bandage().lore()) {
                lore.add(Text.item(l));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(Keys.BANDAGE, PersistentDataType.BYTE, (byte) 1);
            final String model = s.bandage().model();
            if (model != null && !model.isBlank()) {
                final NamespacedKey key = NamespacedKey.fromString(model.trim());
                if (key != null) {
                    meta.setItemModel(key);
                }
            }
        });
        return item;
    }

    public static boolean isBandage(final ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(Keys.BANDAGE, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------------------------- чем лечится

    /**
     * Метки предмета для {@code cured-by} / {@code quenched-by}: имя материала (milk-bucket, golden-apple, ...) и
     * синонимы: milk, healing-potion, honey, golden-apple, bandage.
     */
    public static Set<String> tokens(final ItemStack item) {
        final Set<String> out = new LinkedHashSet<>();
        if (item == null || item.isEmpty()) {
            return out;
        }
        final Material m = item.getType();
        out.add(m.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        switch (m) {
            case MILK_BUCKET -> out.add("milk");
            case HONEY_BOTTLE -> out.add("honey");
            case GOLDEN_APPLE, ENCHANTED_GOLDEN_APPLE -> out.add("golden-apple");
            default -> { }
        }
        if (isHealingPotion(item)) {
            out.add("healing-potion");
        }
        if (isBandage(item)) {
            out.add("bandage");
        }
        return out;
    }

    public static boolean isHealingPotion(final ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof PotionMeta pm)) {
            return false;
        }
        final PotionType base = pm.getBasePotionType();
        if (base == PotionType.HEALING || base == PotionType.STRONG_HEALING) {
            return true;
        }
        for (final PotionEffect e : pm.getCustomEffects()) {
            if (e.getType().equals(PotionEffectType.INSTANT_HEALTH)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------- проклятье

    /** Сведения о предмете для {@code CurseFilter}. Поток владельца инвентаря. */
    public static ItemFacts facts(final ItemStack item) {
        final Material m = item.getType();
        final Integer maxDamage = item.getData(DataComponentTypes.MAX_DAMAGE);
        final ItemAttributeModifiers attrs = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        final boolean tool = Tag.ITEMS_SWORDS.isTagged(m) || Tag.ITEMS_AXES.isTagged(m) || Tag.ITEMS_PICKAXES.isTagged(m)
            || Tag.ITEMS_SHOVELS.isTagged(m) || Tag.ITEMS_HOES.isTagged(m) || Tag.ITEMS_SPEARS.isTagged(m)
            || Tag.ITEMS_HEAD_ARMOR.isTagged(m) || Tag.ITEMS_CHEST_ARMOR.isTagged(m) || Tag.ITEMS_LEG_ARMOR.isTagged(m)
            || Tag.ITEMS_FOOT_ARMOR.isTagged(m) || Tag.ITEMS_ENCHANTABLE_WEAPON.isTagged(m)
            || Tag.ITEMS_ENCHANTABLE_DURABILITY.isTagged(m)
            || item.hasData(DataComponentTypes.TOOL) || item.hasData(DataComponentTypes.WEAPON)
            || item.hasData(DataComponentTypes.BLOCKS_ATTACKS);
        final boolean container = Tag.ITEMS_SHULKER_BOXES.isTagged(m) || Tag.ITEMS_BUNDLES.isTagged(m)
            || item.hasData(DataComponentTypes.BUNDLE_CONTENTS);
        final Key model = item.getData(DataComponentTypes.ITEM_MODEL);
        final boolean customModel = item.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)
            || (model != null && !model.equals(m.getKey()));
        String nbt = "";
        if (item.hasItemMeta()) {
            final ItemMeta meta = item.getItemMeta();
            try {
                nbt = meta.getAsComponentString();
            } catch (final RuntimeException e) {
                nbt = "";
            }
        }
        return new ItemFacts(m.name(), maxDamage == null ? m.getMaxDurability() : maxDamage,
            item.hasData(DataComponentTypes.EQUIPPABLE), attrs != null && !attrs.modifiers().isEmpty(),
            item.hasData(DataComponentTypes.DEATH_PROTECTION), tool, container,
            item.hasData(DataComponentTypes.CUSTOM_NAME), customModel, nbt);
    }
}
