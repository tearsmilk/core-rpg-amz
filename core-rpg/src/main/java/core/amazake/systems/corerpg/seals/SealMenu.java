package core.amazake.systems.corerpg.seals;

import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Меню мастера зачарования (27 слотов, как в MagicSeals): слоты камня, кристалла, сургуча и Сердца Аркона, кнопка
 * «Наложить печать», подсказки. Принадлежит одному игроку; трогается только в его потоке.
 */
final class SealMenu implements InventoryHolder {

    static final int SIZE = 27;
    static final int SLOT_STONE = 10;
    static final int SLOT_CRYSTAL = 12;
    static final int SLOT_WAX = 14;
    static final int SLOT_HEART = 16;
    static final int SLOT_ACTION = 22;
    static final Set<Integer> INPUT_SLOTS = Set.of(SLOT_STONE, SLOT_CRYSTAL, SLOT_WAX, SLOT_HEART);
    /** Порядок слотов при раскладке shift-кликом и возврате. */
    static final int[] INPUT_ORDER = {SLOT_STONE, SLOT_CRYSTAL, SLOT_WAX, SLOT_HEART};

    private final UUID viewer;
    private final Inventory inventory;
    private final AtomicBoolean finished = new AtomicBoolean();
    /** Декор по слотам (копии): в {@link #finish()} всё, что на них не похоже, — предметы игрока. */
    private final ItemStack[] decor = new ItemStack[SIZE];

    SealMenu(final UUID viewer, final String title) {
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.item(title));
        final ItemStack glass = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) {
            if (!INPUT_SLOTS.contains(i) && i != SLOT_ACTION) {
                this.inventory.setItem(i, glass);
            }
        }
        // Подсказки над слотами (декор, забрать нельзя). Только стёкла: если меню на миг осталось без обработчика
        // кликов (выключение системы), забрать из него нечего ценного.
        this.inventory.setItem(1, icon(Material.LIME_STAINED_GLASS_PANE, "<green>Камень C / B / A",
            "<gray>Для первого наложения.",
            "<gray>C — обычные, B — алмаз и золото, A — незерит."));
        this.inventory.setItem(3, icon(Material.RED_STAINED_GLASS_PANE, "<red>Кристалл крови",
            "<gray>Для смены печати.",
            "<gray>С арконитовым сургучом — только цвет."));
        this.inventory.setItem(5, icon(Material.ORANGE_STAINED_GLASS_PANE, "<gold>Сургуч",
            "<gray>Обычный — оранжевый цвет.",
            "<gray>Особый — оранжевый + привязка.",
            "<gray>Арконитовый — только цвет (с кристаллом)."));
        this.inventory.setItem(7, icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "<aqua>Сердце Аркона",
            "<gray>Цвет не ниже синего.",
            "<gray>Кладётся вместе с камнем или кристаллом."));
        this.inventory.setItem(SLOT_ACTION, icon(Material.YELLOW_STAINED_GLASS_PANE, "<yellow>Наложить печать",
            "<gray>Предмет — в основной руке.",
            "<gray>Расходники — в слоты выше.",
            "<gray>Нажмите, чтобы применить."));
        for (int i = 0; i < SIZE; i++) {
            final ItemStack item = this.inventory.getItem(i);
            this.decor[i] = item == null ? null : item.clone();
        }
    }

    private static ItemStack icon(final Material material, final String title, final String... lore) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.item(title));
        if (lore.length > 0) {
            meta.lore(Text.lore(Arrays.asList(lore)));
        }
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }

    UUID viewer() {
        return this.viewer;
    }

    /** Какой расходник можно положить в слот. */
    static boolean allowed(final int slot, final Consumable consumable) {
        if (consumable == null) {
            return false;
        }
        return switch (slot) {
            case SLOT_STONE -> consumable.isStone();
            case SLOT_CRYSTAL -> consumable == Consumable.BLOOD_CRYSTAL;
            case SLOT_WAX -> consumable.isWax();
            case SLOT_HEART -> consumable == Consumable.HEART;
            default -> false;
        };
    }

    /** Слот для расходника (shift-клик). -1 — некуда. */
    static int slotFor(final Consumable consumable) {
        for (final int slot : INPUT_ORDER) {
            if (allowed(slot, consumable)) {
                return slot;
            }
        }
        return -1;
    }

    /** Снять один предмет из слота. false — слот пуст. */
    boolean takeOne(final int slot) {
        final ItemStack stack = this.inventory.getItem(slot);
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (stack.getAmount() <= 1) {
            this.inventory.setItem(slot, null);
        } else {
            stack.setAmount(stack.getAmount() - 1);
            this.inventory.setItem(slot, stack);
        }
        return true;
    }

    /**
     * Меню закрыто: один раз забирает всё из входных слотов (их надо вернуть игроку). Повторный вызов — пустой список,
     * так что закрытие и выключение системы не выдадут предметы дважды. Чужие предметы на месте декора (подложены,
     * пока меню было без обработчика кликов) тоже возвращаются.
     */
    List<ItemStack> finish() {
        if (!this.finished.compareAndSet(false, true)) {
            return List.of();
        }
        final List<ItemStack> out = new ArrayList<>();
        for (final int slot : INPUT_ORDER) {
            final ItemStack stack = this.inventory.getItem(slot);
            if (stack != null && !stack.getType().isAir()) {
                out.add(stack);
            }
            this.inventory.setItem(slot, null);
        }
        for (int slot = 0; slot < SIZE; slot++) {
            if (INPUT_SLOTS.contains(slot)) {
                continue;
            }
            final ItemStack stack = this.inventory.getItem(slot);
            if (stack != null && !stack.getType().isAir() && !isDecor(slot, stack)) {
                out.add(stack);
                this.inventory.setItem(slot, this.decor[slot] == null ? null : this.decor[slot].clone());
            }
        }
        return out;
    }

    /** Предмет в слоте — наш декор (та же иконка), а не вещь игрока. */
    boolean isDecor(final int slot, final ItemStack stack) {
        final ItemStack d = slot >= 0 && slot < SIZE ? this.decor[slot] : null;
        return d != null && d.isSimilar(stack);
    }

    boolean finished() {
        return this.finished.get();
    }
}
