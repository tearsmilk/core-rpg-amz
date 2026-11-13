package core.amazake.systems.corerpg.progression.menu;

import core.amazake.systems.corerpg.progression.ProgressionModule;
import core.amazake.systems.corerpg.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Меню модуля. Все слоты — только кнопки: клики и перетаскивание в меню отменяются, предметы игрока в слоты меню не
 * попадают. Меню живёт в потоке своего игрока (открытие, клики, перерисовка).
 */
public abstract class ProgressionMenu implements InventoryHolder {

    protected final ProgressionModule module;
    private final Inventory inventory;

    @SuppressWarnings("this-escape") // держатель инвентаря — само меню; до открытия инвентарь никто не читает
    protected ProgressionMenu(final ProgressionModule module, final int size, final String titleKey) {
        this.module = module;
        final Component title = Text.mm(module.settings().messages().get(titleKey));
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return this.inventory;
    }

    /** Заполнить меню по текущему профилю. Поток игрока. */
    public abstract void render(Player player);

    /** Клик по слоту верхнего инвентаря. Поток игрока. */
    public abstract void click(Player player, int slot, ClickType click);

    /** Нарисовать и открыть. Поток игрока. */
    public final void open(final Player player) {
        this.render(player);
        player.openInventory(this.inventory);
    }

    /** Рамка: серый фон, чёрные верхняя и нижняя строки. */
    protected void frame(final int size) {
        final var gray = MenuItems.filler(org.bukkit.Material.GRAY_STAINED_GLASS_PANE);
        final var black = MenuItems.filler(org.bukkit.Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) {
            this.inventory.setItem(i, gray);
        }
        for (int i = 0; i < 9; i++) {
            this.inventory.setItem(i, black);
            this.inventory.setItem(size - 9 + i, black);
        }
    }

    /**
     * Слоты для {@code n} кнопок в строке {@code row}, по центру: 1 → середина, 2..4 — через один, 5..9 — подряд.
     */
    public static int[] centered(final int n, final int row) {
        final int base = row * 9;
        final int count = Math.max(0, Math.min(9, n));
        final int[] out = new int[count];
        if (count <= 4) {
            final int start = 4 - (count - 1);
            for (int i = 0; i < count; i++) {
                out[i] = base + start + i * 2;
            }
        } else {
            final int start = (9 - count) / 2;
            for (int i = 0; i < count; i++) {
                out[i] = base + start + i;
            }
        }
        return out;
    }
}
