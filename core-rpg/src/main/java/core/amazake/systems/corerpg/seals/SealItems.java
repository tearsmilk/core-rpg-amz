package core.amazake.systems.corerpg.seals;

import core.amazake.systems.corerpg.util.Keys;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Данные печати на предметах (PDC) и расходники. Читает и ключи CoreRpg, и старые ключи MagicSeals; переносит старые
 * данные на новые ключи при записи ({@link #migrate}). Методы чтения безопасны в любом потоке для предмета, которым
 * владеет вызывающий; запись — только в потоке владельца предмета.
 */
final class SealItems {

    /** Данные печати на предмете. color — null, если в PDC мусор. */
    record SealData(String id, SealColor color, String boundTo) {
    }

    private final Keys keys;

    SealItems(final Keys keys) {
        this.keys = keys;
    }

    // --- чтение ---

    private static String read(final PersistentDataContainer pdc, final NamespacedKey key, final NamespacedKey legacy) {
        final String value = pdc.get(key, PersistentDataType.STRING);
        return value != null ? value : pdc.get(legacy, PersistentDataType.STRING);
    }

    /** Печать на предмете или null. */
    SealData read(final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        final PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        final String id = read(pdc, this.keys.sealId, Keys.LEGACY_SEAL_ID);
        final String bound = read(pdc, this.keys.sealBound, Keys.LEGACY_SEAL_BOUND);
        if (id == null && bound == null) {
            return null;
        }
        return new SealData(id, SealColor.byId(read(pdc, this.keys.sealColor, Keys.LEGACY_SEAL_COLOR)), bound);
    }

    /** UUID владельца персонального предмета (строкой) или null. */
    String boundTo(final ItemStack item) {
        final SealData data = this.read(item);
        return data == null ? null : data.boundTo();
    }

    boolean isBound(final ItemStack item) {
        return this.boundTo(item) != null;
    }

    boolean isBoundToOther(final ItemStack item, final UUID player) {
        final String owner = this.boundTo(item);
        return owner != null && !owner.equals(player.toString());
    }

    /** Расходник печатей (новый ключ или старый magicseals:consumable) или null. */
    Consumable consumable(final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        final PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return Consumable.byId(read(pdc, this.keys.sealConsumable, Keys.LEGACY_SEAL_CONSUMABLE));
    }

    private static boolean hasLegacy(final PersistentDataContainer pdc) {
        return pdc.has(Keys.LEGACY_SEAL_ID) || pdc.has(Keys.LEGACY_SEAL_COLOR) || pdc.has(Keys.LEGACY_SEAL_BOUND)
            || pdc.has(Keys.LEGACY_SEAL_CONSUMABLE);
    }

    // --- запись ---

    /**
     * Переносит предмет MagicSeals на ключи CoreRpg (и лор по текущему каталогу). Старый расходник пересоздаётся
     * новым, чтобы складывался с выданными. Возвращает новый предмет, если что-то поменялось, иначе null.
     */
    ItemStack migrate(final ItemStack item, final SealCatalog catalog, final SealSettings settings) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        final PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (!hasLegacy(pdc)) {
            return null;
        }
        final Consumable consumable = this.consumable(item);
        if (consumable != null) {
            return this.createConsumable(consumable, item.getAmount(), settings);
        }
        final SealData data = this.read(item);
        final ItemStack copy = item.clone();
        final ItemMeta meta = copy.getItemMeta();
        final PersistentDataContainer out = meta.getPersistentDataContainer();
        out.remove(Keys.LEGACY_SEAL_ID);
        out.remove(Keys.LEGACY_SEAL_COLOR);
        out.remove(Keys.LEGACY_SEAL_BOUND);
        out.remove(Keys.LEGACY_SEAL_CONSUMABLE);
        if (data != null) {
            if (data.id() != null) {
                out.set(this.keys.sealId, PersistentDataType.STRING, data.id().toLowerCase(java.util.Locale.ROOT));
            }
            if (data.color() != null) {
                out.set(this.keys.sealColor, PersistentDataType.STRING, data.color().id());
            }
            if (data.boundTo() != null) {
                out.set(this.keys.sealBound, PersistentDataType.STRING, data.boundTo());
            }
        }
        copy.setItemMeta(meta);
        if (data != null && data.id() != null && data.color() != null) {
            final SealDefinition def = catalog.get(data.id());
            if (def != null) {
                this.write(copy, def, data.color(), null);
            }
        }
        return copy;
    }

    /**
     * Записывает печать и цвет на предмет (на месте) и перестраивает лор. bindTo — сделать персональным (null — не
     * менять привязку).
     */
    void write(final ItemStack item, final SealDefinition seal, final SealColor color, final UUID bindTo) {
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        final String legacyBound = pdc.get(Keys.LEGACY_SEAL_BOUND, PersistentDataType.STRING);
        pdc.remove(Keys.LEGACY_SEAL_ID);
        pdc.remove(Keys.LEGACY_SEAL_COLOR);
        pdc.remove(Keys.LEGACY_SEAL_BOUND);
        pdc.set(this.keys.sealId, PersistentDataType.STRING, seal.id());
        pdc.set(this.keys.sealColor, PersistentDataType.STRING, color.id());
        if (bindTo != null) {
            pdc.set(this.keys.sealBound, PersistentDataType.STRING, bindTo.toString());
        } else if (legacyBound != null && !pdc.has(this.keys.sealBound)) {
            pdc.set(this.keys.sealBound, PersistentDataType.STRING, legacyBound);
        }
        final boolean bound = pdc.has(this.keys.sealBound);
        meta.lore(rebuildLore(meta.lore(), seal, color, bound));
        item.setItemMeta(meta);
    }

    /** Убирает прежние строки печати и добавляет новые в конец. */
    static List<Component> rebuildLore(final List<Component> old, final SealDefinition seal, final SealColor color,
                                       final boolean bound) {
        final List<Component> lore = new ArrayList<>();
        if (old != null) {
            boolean inBlock = false;
            for (final Component line : old) {
                final String plain = Text.plain(line).trim();
                final boolean header = plain.startsWith("Печать:") || plain.startsWith("Цвет печати:");
                if (header || plain.equals("Персональный предмет") || (inBlock && plain.startsWith("•"))) {
                    inBlock = header || plain.startsWith("•");
                    continue;
                }
                inBlock = false;
                lore.add(line);
            }
        }
        lore.add(Text.item("<gray>Печать: <white><name></white>", Placeholder.unparsed("name", seal.name())));
        lore.add(Text.item("<gray>Цвет печати: <" + color.tag() + ">" + color.russian() + "</" + color.tag() + ">"));
        lore.addAll(bonusLines(seal, color));
        if (bound) {
            lore.add(Text.item("<red>Персональный предмет"));
        }
        return lore;
    }

    /** Строки бонусов печати для цвета: «• Урон: +1.6». */
    static List<Component> bonusLines(final SealDefinition seal, final SealColor color) {
        final List<Component> lines = new ArrayList<>();
        for (final Map.Entry<String, SealDefinition.Values> e : seal.bonuses().entrySet()) {
            final double v = e.getValue().get(color);
            if (Double.isNaN(v)) {
                continue;
            }
            final String key = e.getKey();
            final String tail = BonusFormat.inactive(key) ? " <dark_gray>(пока не действует)</dark_gray>" : "";
            lines.add(Text.item("<dark_gray>• <gray><label>: <white><value></white>" + tail,
                Placeholder.unparsed("label", BonusFormat.label(key)),
                Placeholder.unparsed("value", BonusFormat.value(key, v))));
        }
        return lines;
    }

    /** Новый расходник. */
    ItemStack createConsumable(final Consumable consumable, final int amount, final SealSettings settings) {
        final SealSettings.Look look = settings.look(consumable);
        final ItemStack stack = new ItemStack(look.material(), Math.max(1, Math.min(amount, 64)));
        final ItemMeta meta = stack.getItemMeta();
        meta.displayName(Text.item(look.name()));
        meta.lore(List.of(
            Text.item("<gray>Материал системы магических печатей"),
            Text.item("<dark_gray><hint>", Placeholder.unparsed("hint", consumable.hint()))));
        meta.getPersistentDataContainer().set(this.keys.sealConsumable, PersistentDataType.STRING, consumable.id());
        stack.setItemMeta(meta);
        return stack;
    }
}
