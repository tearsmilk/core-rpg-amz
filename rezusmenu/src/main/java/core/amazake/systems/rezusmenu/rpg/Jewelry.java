package core.amazake.systems.rezusmenu.rpg;

import core.amazake.systems.rezusmenu.Settings;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Слоты бижутерии: амулет, два кольца, тотем, артефакт. Предметы лежат в PersistentDataContainer игрока (ключ на
 * слот, {@link ItemStack#serializeAsBytes()}): не выпадают при смерти, не теряются при выходе, выключении системы
 * и падении сервера (PDC сохраняется вместе с игроком). Читается при открытии меню, пишется сразу при каждом
 * изменении. Только поток игрока. Характеристики бижутерии пока никуда не применяются.
 */
final class Jewelry {

    static final int AMULET = 100;
    static final int RING_1 = 101;
    static final int RING_2 = 102;
    static final int TOTEM = 103;
    static final int ARTIFACT = 104;

    private static final Pattern MMO_TYPE = Pattern.compile("MMOITEMS_ITEM_TYPE\\\\?\"?\\s*:\\s*\\\\?\"?([A-Za-z0-9_]+)");

    private final Player player;
    private final Map<Integer, ItemStack> items = new HashMap<>();
    /** Записи, которые не прочитались: слот закрыт, запись не трогаем. */
    private final Map<Integer, Boolean> broken = new HashMap<>();

    Jewelry(final Player player) {
        this.player = player;
        final PersistentDataContainer pdc = player.getPersistentDataContainer();
        for (int i = AMULET; i <= ARTIFACT; i++) {
            final byte[] raw = pdc.get(key(i), PersistentDataType.BYTE_ARRAY);
            if (raw != null && raw.length > 0) {
                try {
                    this.items.put(i, ItemStack.deserializeBytes(raw));
                } catch (final RuntimeException e) {
                    // Битую запись не трогаем: вдруг её прочтёт следующая версия. Слот покажется пустым и закрытым.
                    this.items.put(i, null);
                    this.broken.put(i, Boolean.TRUE);
                }
            }
        }
    }

    static boolean isJewel(final int index) {
        return index >= AMULET && index <= ARTIFACT;
    }

    /** Ключ PDC слота: {@code rezusmenu:jewel_amulet} и т. д. */
    static NamespacedKey key(final int index) {
        return new NamespacedKey("rezusmenu", "jewel_" + switch (index) {
            case AMULET -> "amulet";
            case RING_1 -> "ring_1";
            case RING_2 -> "ring_2";
            case TOTEM -> "totem";
            case ARTIFACT -> "artifact";
            default -> throw new IllegalArgumentException("не слот бижутерии: " + index);
        });
    }

    /** Правило слота в config.yml (rpg.jewelry.&lt;slot&gt;). */
    static String rule(final int index) {
        return switch (index) {
            case AMULET -> "amulet";
            case RING_1, RING_2 -> "ring";
            case TOTEM -> "totem";
            default -> "artifact";
        };
    }

    static String slotName(final int index) {
        return switch (index) {
            case AMULET -> "Амулет";
            case RING_1, RING_2 -> "Кольцо";
            case TOTEM -> "Тотем";
            default -> "Артефакт";
        };
    }

    ItemStack get(final int index) {
        final ItemStack it = this.items.get(index);
        return it == null ? null : it.clone();
    }

    boolean locked(final int index) {
        return this.broken.containsKey(index);
    }

    /** Положить (null — очистить) и сразу записать в PDC. */
    void set(final int index, final ItemStack item) {
        final ItemStack value = item == null || item.isEmpty() ? null : item.clone();
        this.items.put(index, value);
        final PersistentDataContainer pdc = this.player.getPersistentDataContainer();
        if (value == null) {
            pdc.remove(key(index));
        } else {
            pdc.set(key(index), PersistentDataType.BYTE_ARRAY, value.serializeAsBytes());
        }
    }

    /** Можно ли положить предмет в слот по правилам config.yml. */
    static boolean allowed(final Settings settings, final int index, final ItemStack item) {
        final Settings.JewelRule r = settings.jewelry().get(rule(index));
        if (r == null || r.any()) {
            return true;
        }
        if (r.materials().contains(item.getType())) {
            return true;
        }
        final String type = mmoType(item);
        return type != null && r.types().contains(type.toUpperCase(Locale.ROOT));
    }

    private record TypeApi(Plugin plugin, Method method) {
    }

    private static volatile TypeApi typeApi;

    /** Тип MMOItems (MMOITEMS_ITEM_TYPE) или null. Сначала через API MMOItems, иначе из компонентов предмета. */
    static String mmoType(final ItemStack item) {
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("MMOItems");
        if (plugin != null && plugin.isEnabled()) {
            try {
                TypeApi a = typeApi;
                if (a == null || a.plugin() != plugin) {
                    final Class<?> c = Class.forName("net.Indyuce.mmoitems.MMOItems", true, plugin.getClass().getClassLoader());
                    a = new TypeApi(plugin, c.getMethod("getTypeName", ItemStack.class));
                    typeApi = a;
                }
                final Object t = a.method().invoke(null, item);
                if (t != null && !t.toString().isBlank()) {
                    return t.toString();
                }
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                // ниже — разбор компонентов
            }
        }
        if (!item.hasItemMeta()) {
            return null;
        }
        try {
            final Matcher m = MMO_TYPE.matcher(item.getItemMeta().getAsString());
            return m.find() ? m.group(1) : null;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    static void reset() {
        typeApi = null;
    }
}
