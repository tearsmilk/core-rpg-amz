package core.amazake.systems.corevault.coin;

import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Предмет монеты: метка {@code corevault:coin} (long — стоимость одной монеты в минорных единицах). */
public final class Coins {

    public static final NamespacedKey KEY = new NamespacedKey("corevault", "coin");

    private Coins() {
    }

    public static Material material(final Settings.Coins c) {
        final Material m = Material.matchMaterial(c.material().trim().toUpperCase(Locale.ROOT));
        return m == null || !m.isItem() || m.isAir() ? Material.SUNFLOWER : m;
    }

    /** Стопка монет стоимостью {@code value} каждая. */
    public static ItemStack create(final Settings.Coins c, final long value, final int amount) {
        final ItemStack it = new ItemStack(material(c), Math.max(1, amount));
        final ItemMeta meta = it.getItemMeta();
        meta.itemName(Text.parse(c.name()));
        if (!c.lore().isEmpty()) {
            final List<Component> lore = new ArrayList<>();
            for (final String l : c.lore()) {
                lore.add(Text.parse(l).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(lore);
        }
        if (!c.itemModel().isBlank()) {
            final NamespacedKey model = NamespacedKey.fromString(c.itemModel().trim().toLowerCase(Locale.ROOT));
            if (model != null) {
                meta.setItemModel(model);
            }
        }
        if (c.glint()) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.LONG, value);
        it.setItemMeta(meta);
        return it;
    }

    /** Стоимость одной монеты или 0, если это не монета core-vault. */
    public static long value(final ItemStack it) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) {
            return 0;
        }
        final PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        final Long v = pdc.get(KEY, PersistentDataType.LONG);
        return v == null || v <= 0 ? 0 : v;
    }

    /** Без копирования меты: быстрая проверка через PDC-вид предмета. */
    public static boolean isCoin(final ItemStack it) {
        return it != null && !it.isEmpty() && it.getPersistentDataContainer().has(KEY, PersistentDataType.LONG);
    }
}
