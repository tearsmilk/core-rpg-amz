package core.amazake.systems.rezusmenu.rpg;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Хранилище во вкладке «Снаряжение»: {@code base} рядов по 9 слотов бесплатно, ещё до {@code maxExtra} рядов
 * покупаются (по одному). Каждый слот лежит в PersistentDataContainer игрока под своим ключом
 * ({@code rezusmenu:stash_<n>}, {@link ItemStack#serializeAsBytes()}) и пишется сразу при изменении: PDC
 * сохраняется вместе с игроком, поэтому предметы не теряются и не двоятся при выходе, выключении системы и падении
 * сервера. Если рядов стало меньше (сняли донат), предметы в скрытых рядах остаются и вернутся с рядами. Только поток
 * игрока.
 */
final class Stash {

    /** Первый индекс слота хранилища в общей нумерации меню (инвентарь 0..40, бижутерия 100..104). */
    static final int BASE = 200;
    static final int COLS = 9;
    static final int VISIBLE_ROWS = 3;
    /** Предел нумерации: больше рядов не бывает никогда (base до 6 + extra до 30). */
    static final int MAX_SLOTS = 36 * COLS;

    private final Player player;
    private final Map<Integer, ItemStack> items = new HashMap<>();
    private final Map<Integer, Boolean> broken = new HashMap<>();
    private final int rows;

    Stash(final Player player, final int baseRows, final int extraRows) {
        this.player = player;
        this.rows = rows(baseRows, extraRows);
        final PersistentDataContainer pdc = player.getPersistentDataContainer();
        for (int i = 0; i < this.rows * COLS; i++) {
            final byte[] raw = pdc.get(key(i), PersistentDataType.BYTE_ARRAY);
            if (raw != null && raw.length > 0) {
                try {
                    this.items.put(i, ItemStack.deserializeBytes(raw));
                } catch (final RuntimeException e) {
                    this.broken.put(i, Boolean.TRUE);
                }
            }
        }
    }

    /** Сколько рядов открыто: base + купленные, не больше предела нумерации. */
    static int rows(final int baseRows, final int extraRows) {
        return Math.max(1, Math.min(MAX_SLOTS / COLS, baseRows + Math.max(0, extraRows)));
    }

    /** Самый нижний ряд, с которого можно начать показ трёх рядов. */
    static int maxOffset(final int rows) {
        return Math.max(0, rows - VISIBLE_ROWS);
    }

    /** Сдвиг прокрутки в пределах [0, maxOffset]. */
    static int clampOffset(final int offset, final int rows) {
        return Math.max(0, Math.min(maxOffset(rows), offset));
    }

    static boolean isStash(final int index) {
        return index >= BASE && index < BASE + MAX_SLOTS;
    }

    static NamespacedKey key(final int slot) {
        return new NamespacedKey("rezusmenu", "stash_" + slot);
    }

    int rows() {
        return this.rows;
    }

    /** Слот открыт (ряд куплен или бесплатный). */
    boolean open(final int index) {
        return isStash(index) && index - BASE < this.rows * COLS;
    }

    boolean locked(final int index) {
        return this.broken.containsKey(index - BASE);
    }

    ItemStack get(final int index) {
        final ItemStack it = this.items.get(index - BASE);
        return it == null ? null : it.clone();
    }

    /** Положить (null — очистить) и сразу записать в PDC. */
    void set(final int index, final ItemStack item) {
        final int slot = index - BASE;
        if (!this.open(index) || this.broken.containsKey(slot)) {
            throw new IllegalStateException("слот хранилища закрыт: " + slot);
        }
        final ItemStack value = item == null || item.isEmpty() ? null : item.clone();
        final PersistentDataContainer pdc = this.player.getPersistentDataContainer();
        if (value == null) {
            this.items.remove(slot);
            pdc.remove(key(slot));
        } else {
            this.items.put(slot, value);
            pdc.set(key(slot), PersistentDataType.BYTE_ARRAY, value.serializeAsBytes());
        }
    }
}
