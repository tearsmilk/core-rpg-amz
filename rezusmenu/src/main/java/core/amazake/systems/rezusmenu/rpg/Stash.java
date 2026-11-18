package core.amazake.systems.rezusmenu.rpg;

import core.amazake.systems.rezusmenu.Settings;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Хранилище во вкладке «Снаряжение»: {@code rows} рядов по 9 слотов у всех (stash.rows; покупки рядов больше нет).
 * Ряды ниже, в которых что-то лежит (остались от купленных раньше рядов или от большего stash.rows), показываются
 * всегда — предметы не прячутся.
 *
 * <p>Каждый слот — в PersistentDataContainer игрока: предмет ({@code rezusmenu:stash_<n>},
 * {@link ItemStack#serializeAsBytes()}, количество в нём — не больше ванильного предела) и, если штук больше
 * ванильного предела, счётчик {@code rezusmenu:stash_<n>_count} (до 99, см. {@link StackMath}). Так в мире и в
 * сериализации никогда нет «переполненных» ItemStack, а старые записи (без счётчика) читаются как есть. Пишется
 * сразу при изменении и сохраняется вместе с игроком: предметы не теряются и не двоятся при выходе, выключении
 * системы и падении сервера. Оба ключа — с префиксом {@code rezusmenu:stash_}, профили переносят их вместе. Только
 * поток игрока.</p>
 */
final class Stash {

    /** Первый индекс слота хранилища в общей нумерации меню (инвентарь 0..40, бижутерия 100..104). */
    static final int BASE = 200;
    static final int COLS = 9;
    /** Предел нумерации: больше рядов не бывает никогда. */
    static final int MAX_SLOTS = Settings.STASH_MAX_ROWS * COLS;

    private final Player player;
    private final Map<Integer, ItemStack> items = new HashMap<>();
    private final Map<Integer, Integer> counts = new HashMap<>();
    private final Map<Integer, Boolean> broken = new HashMap<>();
    private final int rows;

    Stash(final Player player, final int configRows) {
        this.player = player;
        final PersistentDataContainer pdc = player.getPersistentDataContainer();
        int lastUsed = -1;
        for (int i = 0; i < MAX_SLOTS; i++) {
            final byte[] raw = pdc.get(key(i), PersistentDataType.BYTE_ARRAY);
            if (raw != null && raw.length > 0) {
                lastUsed = i;
                try {
                    final ItemStack it = ItemStack.deserializeBytes(raw);
                    this.items.put(i, it);
                    this.counts.put(i, StackMath.decodeCount(pdc.get(countKey(i), PersistentDataType.INTEGER),
                        it.getAmount()));
                } catch (final RuntimeException e) {
                    this.broken.put(i, Boolean.TRUE);
                }
            }
        }
        this.rows = rows(configRows, lastUsed);
    }

    /** Сколько рядов показать: stash.rows, но не меньше, чем нужно, чтобы был виден последний занятый слот. */
    static int rows(final int configRows, final int lastUsedSlot) {
        final int used = lastUsedSlot < 0 ? 0 : lastUsedSlot / COLS + 1;
        return Math.max(1, Math.min(MAX_SLOTS / COLS, Math.max(configRows, used)));
    }

    // --- сумка вкладки «Снаряжение»: 3 ряда кармана, под ними ряды хранилища; видно 3 ряда, панель — отдельно ---

    /** Рядов кармана (ванильный инвентарь 9..35) в сумке. */
    static final int POCKET_ROWS = 3;
    /** Сколько рядов сумки видно сразу. */
    static final int VISIBLE_ROWS = 3;

    /** Всего рядов в сумке: карман + хранилище. */
    static int bagRows(final int stashRows) {
        return POCKET_ROWS + stashRows;
    }

    static int maxOffset(final int bagRows) {
        return Math.max(0, bagRows - VISIBLE_ROWS);
    }

    static int clampOffset(final int offset, final int bagRows) {
        return Math.max(0, Math.min(maxOffset(bagRows), offset));
    }

    /** Индекс слота меню для ряда сумки {@code bagRow} и колонки: карман 9..35, дальше хранилище с {@link #BASE}. */
    static int bagSlot(final int bagRow, final int col) {
        return bagRow < POCKET_ROWS ? 9 + bagRow * COLS + col : BASE + (bagRow - POCKET_ROWS) * COLS + col;
    }

    static boolean isStash(final int index) {
        return index >= BASE && index < BASE + MAX_SLOTS;
    }

    static NamespacedKey key(final int slot) {
        return new NamespacedKey("rezusmenu", "stash_" + slot);
    }

    static NamespacedKey countKey(final int slot) {
        return new NamespacedKey("rezusmenu", "stash_" + slot + "_count");
    }

    int rows() {
        return this.rows;
    }

    /** Слот есть у игрока. */
    boolean open(final int index) {
        return isStash(index) && index - BASE < this.rows * COLS;
    }

    boolean locked(final int index) {
        return this.broken.containsKey(index - BASE);
    }

    /** Предмет слота (копия) с настоящим количеством — может быть больше ванильного предела (до 99). */
    ItemStack get(final int index) {
        final int slot = index - BASE;
        final ItemStack it = this.items.get(slot);
        if (it == null) {
            return null;
        }
        final ItemStack copy = it.clone();
        copy.setAmount(this.counts.getOrDefault(slot, it.getAmount()));
        return copy;
    }

    /** Положить (null — очистить) и сразу записать в PDC. Количество — до {@link StackMath#stashLimit}. */
    void set(final int index, final ItemStack item) {
        final int slot = index - BASE;
        if (!this.open(index) || this.broken.containsKey(slot)) {
            throw new IllegalStateException("слот хранилища закрыт: " + slot);
        }
        final PersistentDataContainer pdc = this.player.getPersistentDataContainer();
        if (item == null || item.isEmpty()) {
            this.items.remove(slot);
            this.counts.remove(slot);
            pdc.remove(key(slot));
            pdc.remove(countKey(slot));
            return;
        }
        final int max = item.getMaxStackSize();
        final int count = item.getAmount();
        if (count > StackMath.stashLimit(max)) {
            throw new IllegalStateException("в слоте хранилища не больше " + StackMath.stashLimit(max) + ": " + count);
        }
        final ItemStack stored = item.clone();
        stored.setAmount(StackMath.storedAmount(count, max));
        this.items.put(slot, stored);
        this.counts.put(slot, count);
        pdc.set(key(slot), PersistentDataType.BYTE_ARRAY, stored.serializeAsBytes());
        if (count > stored.getAmount()) {
            pdc.set(countKey(slot), PersistentDataType.INTEGER, count);
        } else {
            pdc.remove(countKey(slot));
        }
    }
}
