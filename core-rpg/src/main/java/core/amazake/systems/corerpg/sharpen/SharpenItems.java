package core.amazake.systems.corerpg.sharpen;

import core.amazake.systems.corerpg.util.Gear;
import core.amazake.systems.corerpg.util.Keys;
import core.amazake.systems.corerpg.util.Text;
import io.papermc.paper.persistence.PersistentDataContainerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Работа с предметами: уровень заточки в PDC (с переносом со старого ключа {@code zatochka:sharpen_level}), лор,
 * расходники. Методы чтения безопасны из любого потока для предмета, которым владеет вызывающий; запись — в потоке
 * владельца предмета (игрока).
 */
final class SharpenItems {

    private final Keys keys;
    private final Supplier<SharpenSettings> settings;

    SharpenItems(final Keys keys, final Supplier<SharpenSettings> settings) {
        this.keys = keys;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ уровень

    /** Уровень: новый ключ, иначе старый ключ плагина Zatochka; 0, если нет. */
    int level(final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return 0;
        }
        final PersistentDataContainerView pdc = item.getPersistentDataContainer();
        Integer v = pdc.has(this.keys.sharpenLevel, PersistentDataType.INTEGER)
            ? pdc.get(this.keys.sharpenLevel, PersistentDataType.INTEGER) : null;
        if (v == null && pdc.has(Keys.LEGACY_ZATOCHKA_LEVEL, PersistentDataType.INTEGER)) {
            v = pdc.get(Keys.LEGACY_ZATOCHKA_LEVEL, PersistentDataType.INTEGER);
        }
        return v == null ? 0 : Math.max(0, Math.min(SharpenSettings.HARD_MAX, v));
    }

    /** Есть ли на предмете старый ключ плагина. */
    boolean hasLegacy(final ItemStack item) {
        return item != null && !item.getType().isAir() && item.hasItemMeta()
            && item.getPersistentDataContainer().has(Keys.LEGACY_ZATOCHKA_LEVEL);
    }

    /** Перенести уровень со старого ключа на новый (первое касание). true — предмет изменён. Поток владельца. */
    boolean migrate(final ItemStack item) {
        if (!this.hasLegacy(item)) {
            return false;
        }
        if (Gear.kind(item) == null) {
            // Не точится в CoreRpg: просто убрать чужой ключ, не трогая лор.
            item.editMeta(meta -> meta.getPersistentDataContainer().remove(Keys.LEGACY_ZATOCHKA_LEVEL));
            return true;
        }
        this.setLevel(item, this.level(item));
        return true;
    }

    /** Записать уровень (0 — снять заточку), обновить лор. Поток владельца предмета. */
    void setLevel(final ItemStack item, final int level) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        final Gear.Kind kind = Gear.kind(item);
        final int lvl = Math.max(0, Math.min(SharpenSettings.HARD_MAX, level));
        item.editMeta(meta -> {
            final PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.remove(Keys.LEGACY_ZATOCHKA_LEVEL);
            if (lvl <= 0) {
                pdc.remove(this.keys.sharpenLevel);
            } else {
                pdc.set(this.keys.sharpenLevel, PersistentDataType.INTEGER, lvl);
            }
            removeLegacyModifiers(meta);
            this.writeLore(meta, kind, lvl);
        });
    }

    private void writeLore(final ItemMeta meta, final Gear.Kind kind, final int level) {
        final List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.removeIf(c -> SharpenRules.isSharpenLore(Text.plain(c)));
        while (!lore.isEmpty() && Text.plain(lore.get(0)).isBlank()) {
            lore.remove(0);
        }
        if (level > 0 && kind != null) {
            final double bonus = this.settings.get().bonus(kind.attacks(), level);
            lore.add(0, Text.item(SharpenRules.loreLine(level, kind.attacks(), bonus)));
        }
        meta.lore(lore.isEmpty() ? null : lore);
    }

    /** Модификаторы атрибутов старых версий плагина (zatochka_*): плагин их тоже снимал. */
    private static void removeLegacyModifiers(final ItemMeta meta) {
        if (!meta.hasAttributeModifiers() || meta.getAttributeModifiers() == null) {
            return;
        }
        final List<Map.Entry<Attribute, AttributeModifier>> copy = new ArrayList<>(meta.getAttributeModifiers().entries());
        for (final Map.Entry<Attribute, AttributeModifier> entry : copy) {
            final AttributeModifier mod = entry.getValue();
            if ("zatochka".equals(mod.getKey().getNamespace()) || mod.getKey().getKey().startsWith("zatochka_")) {
                meta.removeAttributeModifier(entry.getKey(), mod);
            }
        }
    }

    /** Перенести все старые предметы в инвентаре игрока. Поток игрока. Возвращает число изменённых. */
    int migrateInventory(final Player player) {
        int changed = 0;
        final ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            final ItemStack item = contents[i];
            if (this.hasLegacy(item) && this.migrate(item)) {
                player.getInventory().setItem(i, item);
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ расходники

    /** Расходник по PDC (новый ключ или старый {@code zatochka:consumable_id}) или null. */
    Consumable consumable(final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        final PersistentDataContainerView pdc = item.getPersistentDataContainer();
        String id = pdc.has(this.keys.sharpenConsumable, PersistentDataType.STRING)
            ? pdc.get(this.keys.sharpenConsumable, PersistentDataType.STRING) : null;
        if (id == null && pdc.has(Keys.LEGACY_ZATOCHKA_CONSUMABLE, PersistentDataType.STRING)) {
            id = pdc.get(Keys.LEGACY_ZATOCHKA_CONSUMABLE, PersistentDataType.STRING);
        }
        return Consumable.byId(id);
    }

    /** Новый расходник, 1..64 штук. */
    ItemStack create(final Consumable type, final int amount) {
        final SharpenSettings.ConsumableDef def = this.settings.get().def(type);
        // Материал из настроек, который не является предметом (блок без предмета), — материал по умолчанию.
        final Material material = def.material().isItem() && !def.material().isAir() ? def.material() : Material.valueOf(type.defaultMaterial());
        final ItemStack stack = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        stack.editMeta(meta -> {
            meta.displayName(Text.item(def.display()));
            meta.lore(def.lore().isEmpty() ? null : Text.lore(def.lore()));
            meta.getPersistentDataContainer().set(this.keys.sharpenConsumable, PersistentDataType.STRING, type.id());
        });
        return stack;
    }

    /** Имя расходника для сообщений. */
    String name(final Consumable type) {
        return type == null ? "?" : this.settings.get().def(type).name();
    }
}
