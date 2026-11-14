package core.amazake.systems.coreskyrift;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * «Разлом небес» — настоящий NETHERITE_INGOT, поэтому без защиты он годился бы как обычный незерит: улучшение в
 * кузнечном столе, блок незерита, магнетит, оплата маяка. Здесь эти пути закрыты. Все события — поток игрока.
 */
public final class RiftGuard implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(final PrepareItemCraftEvent event) {
        for (final ItemStack it : event.getInventory().getMatrix()) {
            if (RiftItem.is(it)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSmith(final PrepareSmithingEvent event) {
        for (final ItemStack it : event.getInventory().getContents()) {
            if (RiftItem.is(it)) {
                event.setResult(null);
                return;
            }
        }
    }

    /** Маяк: не дать положить слиток в слот оплаты (клик, shift-клик, цифры, перетаскивание). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBeaconClick(final InventoryClickEvent event) {
        final Inventory top = event.getView().getTopInventory();
        if (top.getType() != InventoryType.BEACON) {
            return;
        }
        final boolean inTop = event.getClickedInventory() == top;
        if (inTop && RiftItem.is(event.getCursor())) {
            event.setCancelled(true);
        } else if (!inTop && event.isShiftClick() && RiftItem.is(event.getCurrentItem())) {
            event.setCancelled(true);
        } else if (inTop && event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
            && RiftItem.is(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))) {
            event.setCancelled(true);
        } else if (inTop && event.getClick() == ClickType.SWAP_OFFHAND
            && RiftItem.is(event.getWhoClicked().getInventory().getItemInOffHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBeaconDrag(final InventoryDragEvent event) {
        final Inventory top = event.getView().getTopInventory();
        if (top.getType() != InventoryType.BEACON || !RiftItem.is(event.getOldCursor())) {
            return;
        }
        final int topSize = top.getSize();
        for (final int raw : event.getRawSlots()) {
            if (raw < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
