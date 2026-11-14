package core.amazake.systems.coremobs.drop;

import core.amazake.systems.coremobs.util.IntRange;
import org.bukkit.inventory.ItemStack;

/**
 * Предмет лута моба.
 *
 * @param item образец (клонируйте перед выдачей)
 * @param data образец в base64 ({@link ItemStack#serializeAsBytes()}): все компоненты и NBT, версия данных внутри
 * @param preview что это за предмет — для людей, в файле и в /mobs drops list
 * @param chance шанс выпадения 0..100
 * @param amount сколько штук выпадает
 */
public record DropEntry(ItemStack item, String data, String preview, double chance, IntRange amount) {
}
