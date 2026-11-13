package core.amazake.systems.corerpg.seals;

import core.amazake.systems.corerpg.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Вход игрока (перенос старых предметов MagicSeals) и NPC «Мастер зачарования»: правый клик по сущности с этим
 * именем открывает меню. Citizens не нужен — его NPC тоже сущности с именем.
 */
final class SealsListener implements Listener {

    private final SealsModule module;

    SealsListener(final SealsModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        this.module.migrateInventory(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(final PlayerInteractEntityEvent event) {
        this.handle(event);
    }

    /**
     * Стойка для брони: клиент шлёт только INTERACT_AT, и сервер вызывает лишь это событие (у него свой HandlerList).
     * Прочие сущности получают ещё и обычный {@link PlayerInteractEntityEvent} — там и обрабатываются.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAt(final PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof ArmorStand) {
            this.handle(event);
        }
    }

    private void handle(final PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final String expected = this.module.settings().npcName();
        if (expected.isEmpty() || !matches(event.getRightClicked(), expected)) {
            return;
        }
        event.setCancelled(true);
        final Player player = event.getPlayer();
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof SealMenu) {
            return;
        }
        this.module.openMenu(player);
    }

    /** Имя сущности (видимое имя или ник NPC-игрока) совпадает с настройкой, без учёта цвета и регистра. */
    static boolean matches(final Entity entity, final String expected) {
        final String want = Text.plain(Text.mm(expected)).trim();
        final Component custom = entity.customName();
        if (custom != null && Text.plain(custom).trim().equalsIgnoreCase(want)) {
            return true;
        }
        return entity instanceof final Player npc && npc.getName().equalsIgnoreCase(want);
    }
}
