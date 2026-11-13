package core.amazake.systems.corerpg.seals;

import core.amazake.systems.corerpg.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Клики в меню мастера: кнопка действия, входные слоты только для подходящих расходников, возврат при закрытии. */
final class SealMenuListener implements Listener {

    private final SealsModule module;

    SealMenuListener(final SealsModule module) {
        this.module = module;
    }

    private static SealMenu menuOf(final Inventory top) {
        return top != null && top.getHolder(false) instanceof final SealMenu menu ? menu : null;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(final InventoryClickEvent event) {
        final Inventory top = event.getView().getTopInventory();
        final SealMenu menu = menuOf(top);
        if (menu == null || !(event.getWhoClicked() instanceof final Player player)) {
            return;
        }
        if (menu.finished()) {
            event.setCancelled(true);
            return;
        }
        final int raw = event.getRawSlot();
        final boolean topClick = raw >= 0 && raw < top.getSize();

        // Двойной клик собирает одинаковые предметы со всего окна — в том числе декор меню.
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        if (topClick && raw == SealMenu.SLOT_ACTION) {
            event.setCancelled(true);
            final SealsModule.Reply reply = this.module.perform(player, menu);
            reply.send(player);
            SealsModule.playResult(player, reply.success());
            return;
        }

        if (topClick && SealMenu.INPUT_SLOTS.contains(raw)) {
            final ItemStack incoming = switch (event.getClick()) {
                case NUMBER_KEY -> event.getHotbarButton() >= 0
                    ? player.getInventory().getItem(event.getHotbarButton()) : null;
                case SWAP_OFFHAND -> player.getInventory().getItemInOffHand();
                default -> event.getCursor();
            };
            if (incoming != null && !incoming.getType().isAir()) {
                final Consumable consumable = this.module.items().consumable(incoming);
                if (consumable == null) {
                    event.setCancelled(true);
                    Text.send(player, "<red>Сюда можно класть только расходники печатей.</red>");
                    return;
                }
                if (!SealMenu.allowed(raw, consumable)) {
                    event.setCancelled(true);
                    Text.send(player, "<red>Этот расходник не подходит для данного слота.</red>");
                    return;
                }
            }
            // Обычный клик: положить, забрать, поменять.
            return;
        }

        if (topClick) {
            // Декор и подсказки.
            event.setCancelled(true);
            return;
        }

        // Клик в своём инвентаре: shift-клик раскладываем сами, чтобы ничего не упало в декор.
        if (event.isShiftClick() || event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT
            || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            final ItemStack current = event.getCurrentItem();
            if (current == null || current.getType().isAir()) {
                return;
            }
            final Consumable consumable = this.module.items().consumable(current);
            if (consumable == null) {
                return;
            }
            this.shiftIn(player, menu, event.getSlot(), current, consumable);
        }
    }

    /** Переложить расходник из инвентаря игрока в его слот меню (сколько влезет). */
    private void shiftIn(final Player player, final SealMenu menu, final int playerSlot, final ItemStack current,
                         final Consumable consumable) {
        final int slot = SealMenu.slotFor(consumable);
        if (slot < 0) {
            return;
        }
        final Inventory inv = menu.getInventory();
        final ItemStack there = inv.getItem(slot);
        if (there == null || there.getType().isAir()) {
            inv.setItem(slot, current.clone());
            player.getInventory().setItem(playerSlot, null);
            return;
        }
        if (!there.isSimilar(current)) {
            Text.send(player, "<yellow>Слот уже занят другим расходником: заберите его или положите вручную.</yellow>");
            return;
        }
        final int room = there.getMaxStackSize() - there.getAmount();
        if (room <= 0) {
            return;
        }
        final int move = Math.min(room, current.getAmount());
        there.setAmount(there.getAmount() + move);
        inv.setItem(slot, there);
        if (move >= current.getAmount()) {
            player.getInventory().setItem(playerSlot, null);
        } else {
            current.setAmount(current.getAmount() - move);
            player.getInventory().setItem(playerSlot, current);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(final InventoryDragEvent event) {
        final Inventory top = event.getView().getTopInventory();
        final SealMenu menu = menuOf(top);
        if (menu == null) {
            return;
        }
        boolean touchesTop = false;
        for (final int slot : event.getRawSlots()) {
            if (slot < top.getSize()) {
                touchesTop = true;
                if (!SealMenu.INPUT_SLOTS.contains(slot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (!touchesTop) {
            return;
        }
        final Consumable consumable = this.module.items().consumable(event.getOldCursor());
        for (final int slot : event.getRawSlots()) {
            if (slot < top.getSize() && !SealMenu.allowed(slot, consumable)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        final SealMenu menu = menuOf(event.getInventory());
        if (menu != null && event.getPlayer() instanceof final Player player) {
            this.module.menuClosed(player, menu);
        }
    }
}
