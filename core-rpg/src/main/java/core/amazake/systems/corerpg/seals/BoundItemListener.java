package core.amazake.systems.corerpg.seals;

import core.amazake.systems.corerpg.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Персональные предметы (Особый сургуч): нельзя выбросить, переложить в сундук или воронку; чужой персональный
 * предмет нельзя подобрать. Внутри своего инвентаря переставлять можно.
 */
final class BoundItemListener implements Listener {

    private static final String CANNOT_DROP = "<red>Персональный предмет нельзя выбросить.</red>";
    private static final String CANNOT_TRANSFER = "<red>Персональный предмет нельзя передать.</red>";

    private final SealsModule module;

    BoundItemListener(final SealsModule module) {
        this.module = module;
    }

    private boolean bound(final ItemStack item) {
        return item != null && !item.getType().isAir() && this.module.items().isBound(item);
    }

    /** Верхнее окно — чужой контейнер (не своя сетка крафта и не творческий инвентарь). */
    private static boolean foreignTop(final Inventory top) {
        final InventoryType type = top.getType();
        return type != InventoryType.CRAFTING && type != InventoryType.CREATIVE;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        if (this.bound(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.send(event.getPlayer(), CANNOT_DROP);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (event.getEntity() instanceof final Player player
            && this.module.items().isBoundToOther(event.getItem().getItemStack(), player.getUniqueId())) {
            event.setCancelled(true);
        } else if (!(event.getEntity() instanceof Player) && this.bound(event.getItem().getItemStack())) {
            // Мобы и лисы не уносят персональные предметы.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof final Player player)) {
            return;
        }
        final Inventory top = event.getView().getTopInventory();
        final Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            // Клик за пределами окна выбрасывает предмет с курсора.
            if (this.bound(event.getCursor())) {
                event.setCancelled(true);
                Text.send(player, CANNOT_DROP);
            }
            return;
        }
        // Выброс из инвентаря клавишей Q.
        if ((event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP)
            && this.bound(event.getCurrentItem())) {
            event.setCancelled(true);
            Text.send(player, CANNOT_DROP);
            return;
        }
        if (!foreignTop(top)) {
            return;
        }
        if (clicked == top) {
            ItemStack incoming = event.getCursor();
            if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
                incoming = player.getInventory().getItem(event.getHotbarButton());
            } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
                incoming = player.getInventory().getItemInOffHand();
            }
            if (this.bound(incoming)) {
                event.setCancelled(true);
                Text.send(player, CANNOT_TRANSFER);
            }
            return;
        }
        if (event.isShiftClick() && this.bound(event.getCurrentItem())) {
            event.setCancelled(true);
            Text.send(player, CANNOT_TRANSFER);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(final InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof final Player player) || !this.bound(event.getOldCursor())) {
            return;
        }
        final Inventory top = event.getView().getTopInventory();
        if (!foreignTop(top)) {
            return;
        }
        for (final int raw : event.getRawSlots()) {
            if (raw < top.getSize()) {
                event.setCancelled(true);
                Text.send(player, CANNOT_TRANSFER);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(final InventoryMoveItemEvent event) {
        if (this.bound(event.getItem())) {
            event.setCancelled(true);
        }
    }
}
