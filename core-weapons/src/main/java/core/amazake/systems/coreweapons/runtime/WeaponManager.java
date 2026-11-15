package core.amazake.systems.coreweapons.runtime;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreweapons.Settings;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Игроки (по {@link Holder} на каждого онлайн) и события: взмах — кадры и след, крит — дрожь земли. Карты —
 * потокобезопасные; Holder трогает только поток своего игрока.
 */
public final class WeaponManager implements Listener {

    /** Метка копий блоков дрожи (значение — id экземпляра системы). */
    public static final NamespacedKey MARK = new NamespacedKey("coreweapons", "tremor");

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final String instance = UUID.randomUUID().toString();
    private final Map<UUID, Holder> holders = new ConcurrentHashMap<>();
    private final Set<BlockDisplay> displays = ConcurrentHashMap.newKeySet();

    public WeaponManager(final SystemContext context, final Supplier<Settings> settings) {
        this.context = context;
        this.settings = settings;
        Tremor.preload();
        Holder.class.getName();
    }

    /** Включение (глобальный поток): все, кто уже онлайн — в их потоках. */
    public void start() {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            this.context.runOnceForPlayer(p, () -> this.attach(p));
        }
    }

    /** Выключение (глобальный поток): кадры снимаются в потоках игроков, копии блоков убираются. */
    public void shutdown() {
        for (final Holder h : List.copyOf(this.holders.values())) {
            h.cancelTask();
            this.context.runOnceForPlayer(h.player(), h::stop);
        }
        this.holders.clear();
        Tremor.removeAll(this.context, this.displays);
    }

    /** Любой поток: сколько копий блоков сейчас стоит. */
    public int displays() {
        return this.displays.size();
    }

    /** Любой поток: сколько игроков отслеживается. */
    public int players() {
        return this.holders.size();
    }

    private Holder attach(final Player p) {
        final Holder have = this.holders.get(p.getUniqueId());
        if (have != null && have.player() == p) {
            return have;
        }
        final Holder h = new Holder(this.context, this.settings, p);
        final Holder old = this.holders.put(p.getUniqueId(), h);
        if (old != null) {
            old.stop();
        }
        h.start();
        return h;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        this.attach(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final Holder h = this.holders.remove(event.getPlayer().getUniqueId());
        if (h != null) {
            h.stop(); // кадр не уезжает в файл игрока
        }
    }

    // --------------------------------------------------------------------------------------------- замах

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmSwing(final PlayerArmSwingEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Holder h = this.holders.get(event.getPlayer().getUniqueId());
        if (h != null) {
            h.onArmSwing();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDig(final BlockDamageEvent event) {
        final Holder h = this.holders.get(event.getPlayer().getUniqueId());
        if (h != null && !event.getInstaBreak()) {
            h.digging = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDigAbort(final BlockDamageAbortEvent event) {
        final Holder h = this.holders.get(event.getPlayer().getUniqueId());
        if (h != null) {
            h.digging = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(final BlockBreakEvent event) {
        final Holder h = this.holders.get(event.getPlayer().getUniqueId());
        if (h != null) {
            h.digging = false;
        }
    }

    /** Предмет с кадром не должен уйти из рук с кадром: выброс и смерть. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        final ItemStack fixed = ItemTools.withFrame(event.getItemDrop().getItemStack(), null);
        if (fixed != null) {
            event.getItemDrop().setItemStack(fixed);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final PlayerDeathEvent event) {
        final List<ItemStack> drops = event.getDrops();
        for (int i = 0; i < drops.size(); i++) {
            final ItemStack fixed = ItemTools.withFrame(drops.get(i), null);
            if (fixed != null) {
                drops.set(i, fixed);
            }
        }
    }

    // --------------------------------------------------------------------------------------------- крит

    /** Поток региона удара: атакующий и цель стоят рядом, поток владеет обоими. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final EntityDamageByEntityEvent event) {
        if (!event.isCritical() || !(event.getDamager() instanceof Player p)) {
            return;
        }
        final Settings.Weapon w = this.settings.get().weapon(ItemTools.id(p.getInventory().getItemInMainHand()));
        if (w == null || w.tremor() == null) {
            return;
        }
        final Holder h = this.holders.get(p.getUniqueId());
        final long now = Bukkit.getCurrentTick();
        if (h != null) {
            if (now - h.lastTremor < w.tremor().cooldown()) {
                return;
            }
            h.lastTremor = now;
        }
        final Entity target = event.getEntity();
        new Tremor(this.context, w.tremor(), target.getLocation(), p.getUniqueId(), this.displays, this.instance).start();
    }
}
