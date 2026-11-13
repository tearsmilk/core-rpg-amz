package core.amazake.systems.corerpg.progression;

import core.amazake.systems.corerpg.progression.menu.ProgressionMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.InventoryHolder;

/** События модуля. Все обработчики — в потоке игрока (его события, его инвентарь). */
final class ProgressionListener implements Listener {

    private final ProgressionModule module;

    ProgressionListener(final ProgressionModule module) {
        this.module = module;
    }

    /** Ванильные сферы опыта → кастомный опыт; ванильный отменяется. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onExp(final PlayerExpChangeEvent event) {
        final int amount = event.getAmount();
        if (amount <= 0) {
            return;
        }
        event.setAmount(this.module.convertVanilla(event.getPlayer(), amount));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(final PlayerRespawnEvent event) {
        this.module.reapplyLater(event.getPlayer());
    }

    /** Молоко и тотем снимают все эффекты: постоянные эффекты класса возвращаем. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotion(final EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof final Player player)) {
            return;
        }
        final EntityPotionEffectEvent.Cause cause = event.getCause();
        final EntityPotionEffectEvent.Action action = event.getAction();
        if ((cause == EntityPotionEffectEvent.Cause.MILK || cause == EntityPotionEffectEvent.Cause.TOTEM)
            && (action == EntityPotionEffectEvent.Action.CLEARED || action == EntityPotionEffectEvent.Action.REMOVED)) {
            this.module.reapplyLater(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.module.onQuit(event.getPlayer());
    }

    /** Меню модуля: любые клики отменяются (в слоты меню ничего не положить), кнопки — по слоту. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(final InventoryClickEvent event) {
        final InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (!(holder instanceof final ProgressionMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof final Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        menu.click(player, event.getSlot(), event.getClick());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(final InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ProgressionMenu) {
            event.setCancelled(true);
        }
    }
}
